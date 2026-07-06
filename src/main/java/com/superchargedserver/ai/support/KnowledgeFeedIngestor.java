package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * Asynchronously ingests every .txt / .md / .json file inside AI/feeds,
 * chunks the content by paragraphs and markdown headers, and serves scored
 * keyword-overlap retrieval so only the most relevant fragments reach the
 * LLM context window. All disk IO happens off the main server thread.
 */
public class KnowledgeFeedIngestor {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "to", "of", "in", "on", "for", "and",
            "or", "how", "do", "does", "did", "i", "my", "me", "can", "what", "why", "when",
            "with", "it", "this", "that", "you", "your", "not", "have", "has", "get", "but");

    public record Chunk(String source, String text, Set<String> tokens) {
    }

    public record ScoredChunk(Chunk chunk, double score) {
    }

    private final SuperChargedServer plugin;
    private volatile List<Chunk> chunks = List.of();

    public KnowledgeFeedIngestor(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public File feedsDirectory() {
        return new File(plugin.getDataFolder(), "AI/feeds");
    }

    public int chunkCount() {
        return chunks.size();
    }

    public void reloadAsync(Executor executor, IntConsumer onComplete) {
        executor.execute(() -> {
            List<Chunk> loaded = new ArrayList<>();
            File directory = feedsDirectory();
            directory.mkdirs();
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().toLowerCase(Locale.ROOT);
                    if (!file.isFile()
                            || !(name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".json"))) {
                        continue;
                    }
                    try {
                        chunk(file.getName(), Files.readString(file.toPath(), StandardCharsets.UTF_8), loaded);
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed to ingest knowledge feed " + file.getName()
                                + ": " + ex.getMessage());
                    }
                }
            }
            chunks = List.copyOf(loaded);
            onComplete.accept(loaded.size());
        });
    }

    private void chunk(String source, String content, List<Chunk> out) {
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n")) {
            boolean boundary = line.isBlank() || line.startsWith("#");
            if ((boundary && current.length() > 0) || current.length() > 1500) {
                flush(source, current, out);
            }
            if (!line.isBlank()) {
                current.append(line).append('\n');
            }
        }
        flush(source, current, out);
    }

    private void flush(String source, StringBuilder current, List<Chunk> out) {
        String text = current.toString().trim();
        current.setLength(0);
        if (text.length() >= 20) {
            out.add(new Chunk(source, text, tokenize(text)));
        }
    }

    /** Best-matching chunks for a query, highest score first, capped at max. */
    public List<ScoredChunk> retrieve(String query, int max) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return List.of();
        List<ScoredChunk> scored = new ArrayList<>();
        for (Chunk chunk : chunks) {
            long overlap = queryTokens.stream().filter(chunk.tokens()::contains).count();
            if (overlap == 0) continue;
            double score = (double) overlap / queryTokens.size();
            if (score >= 0.15) {
                scored.add(new ScoredChunk(chunk, score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.size() > max ? List.copyOf(scored.subList(0, max)) : scored;
    }

    public static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() >= 3 && !STOPWORDS.contains(raw)) {
                tokens.add(raw);
            }
        }
        return tokens;
    }
}