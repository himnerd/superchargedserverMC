package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * SQLite-backed FAQ cache layer. Verified high-confidence answers are stored
 * with a normalized token hash; lookups match either the exact hash or a
 * Jaccard token similarity above the configured threshold within the expiry
 * window — skipping the LLM call entirely on a hit. Always invoked from the
 * AI support executor thread, never the main server thread.
 */
public class FAQCacheRepository {

    private final SuperChargedServer plugin;

    public FAQCacheRepository(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void init() {
        if (!plugin.getDatabaseManager().isOnline()) return;
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sca_ai_faq ("
                    + "query_hash VARCHAR(64) PRIMARY KEY,"
                    + "query_tokens TEXT,"
                    + "answer TEXT,"
                    + "confidence INT,"
                    + "created BIGINT)");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create AI FAQ cache table: " + e.getMessage());
        }
        purgeExpired();
    }

    private long cutoff() {
        int days = plugin.getConfigManager().aiEngine().getInt("ai-support.faq-cache.expiry-days", 7);
        return System.currentTimeMillis() - days * 86_400_000L;
    }

    /** Returns the cached verified answer, or null on a cache miss. */
    public String lookup(String question) {
        if (!plugin.getDatabaseManager().isOnline()) return null;
        Set<String> tokens = KnowledgeFeedIngestor.tokenize(question);
        if (tokens.isEmpty()) return null;
        String hash = hash(normalize(tokens));
        double threshold = plugin.getConfigManager().aiEngine()
                .getDouble("ai-support.faq-cache.similarity-threshold", 0.82);
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT query_hash, query_tokens, answer FROM sca_ai_faq WHERE created > ?")) {
            ps.setLong(1, cutoff());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (hash.equals(rs.getString("query_hash"))) {
                        return rs.getString("answer");
                    }
                    String stored = rs.getString("query_tokens");
                    if (stored == null || stored.isEmpty()) continue;
                    Set<String> other = new HashSet<>(Arrays.asList(stored.split(" ")));
                    if (jaccard(tokens, other) >= threshold) {
                        return rs.getString("answer");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("AI FAQ cache lookup failed: " + e.getMessage());
        }
        return null;
    }

    public void store(String question, String answer, int confidence) {
        if (!plugin.getDatabaseManager().isOnline()) return;
        Set<String> tokens = KnowledgeFeedIngestor.tokenize(question);
        if (tokens.isEmpty()) return;
        String normalized = normalize(tokens);
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "REPLACE INTO sca_ai_faq (query_hash, query_tokens, answer, confidence, created) "
                             + "VALUES (?,?,?,?,?)")) {
            ps.setString(1, hash(normalized));
            ps.setString(2, normalized);
            ps.setString(3, answer);
            ps.setInt(4, confidence);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("AI FAQ cache store failed: " + e.getMessage());
        }
    }

    public void purgeExpired() {
        if (!plugin.getDatabaseManager().isOnline()) return;
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = connection.prepareStatement("DELETE FROM sca_ai_faq WHERE created <= ?")) {
            ps.setLong(1, cutoff());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("AI FAQ cache purge failed: " + e.getMessage());
        }
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    private String normalize(Set<String> tokens) {
        return String.join(" ", new TreeSet<>(tokens));
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}