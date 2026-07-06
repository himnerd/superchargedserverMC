package com.superchargedserver.account;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, single-use, expiring handshake codes for /superlogin.
 * Codes are removed atomically on redemption (ConcurrentHashMap#remove),
 * making double-redemption races impossible. Failed redemptions are
 * tracked per IP with automatic lockout.
 */
public class LinkCodeManager {

    public record PendingLink(UUID accountId, String discordId, long expiresAt) {
        public boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SuperChargedServer plugin;
    private final ConcurrentHashMap<String, PendingLink> codes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lockouts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public LinkCodeManager(SuperChargedServer plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> codes.entrySet().removeIf(e -> e.getValue().expired()), 1200L, 1200L);
    }

    public String generateForAccount(UUID accountId) {
        return generate(new PendingLink(accountId, null, expiry()));
    }

    public String generateForDiscord(String discordId) {
        return generate(new PendingLink(null, discordId, expiry()));
    }

    /** Short handshake code for the in-game /link command (Discord-side redemption). */
    public String generateShortForAccount(UUID accountId) {
        return generate(new PendingLink(accountId, null, expiry()),
                cfg().getInt("superlogin.link-code-length", 6));
    }

    private String generate(PendingLink link) {
        return generate(link, cfg().getInt("superlogin.code-length", 10));
    }

    private String generate(PendingLink link, int length) {
        String code;
        do {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            code = sb.toString();
        } while (codes.putIfAbsent(code, link) != null);
        return code;
    }

    public boolean isLockedOut(String ip) {
        Long until = lockouts.get(ip);
        return until != null && until > System.currentTimeMillis();
    }

    /** Non-destructive lookup for pre-redemption validation. */
    public PendingLink peek(String code) {
        PendingLink link = codes.get(normalize(code));
        return (link == null || link.expired()) ? null : link;
    }

    /** Atomic single-use redemption. Returns null when invalid or locked out. */
    public PendingLink redeem(String code, String ip) {
        if (isLockedOut(ip)) return null;
        PendingLink link = codes.remove(normalize(code));
        if (link == null || link.expired()) {
            registerFailure(ip);
            return null;
        }
        failedAttempts.remove(ip);
        return link;
    }

    private void registerFailure(String ip) {
        int max = cfg().getInt("superlogin.max-attempts", 5);
        int count = failedAttempts.merge(ip, 1, Integer::sum);
        if (count >= max) {
            lockouts.put(ip, System.currentTimeMillis()
                    + cfg().getInt("superlogin.lockout-minutes", 10) * 60000L);
            failedAttempts.remove(ip);
            plugin.getLogger().warning("IP " + ip + " locked out of /superlogin (too many failed codes).");
        }
    }

    private long expiry() {
        return System.currentTimeMillis() + cfg().getInt("superlogin.code-expiration-seconds", 300) * 1000L;
    }

    private String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private FileConfiguration cfg() {
        return plugin.getConfigManager().superAccounts();
    }
}