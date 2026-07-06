package com.superchargedserver.performance;

import com.superchargedserver.SuperChargedServer;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Immutable, fully validated snapshot of configs/performance.yml.
 * Loading is crash-proof: any malformed key logs a precise warning and
 * falls back to a hardcoded safe default so the plugin keeps running.
 */
@Getter
public final class PerformanceConfig {

    public enum ViolationAction {
        DROP_CONNECTION,
        SERVE_STATIC_FALLBACK
    }

    private final int telemetryPoolSize;
    private final long profileExpirationSeconds;
    private final boolean pingRateLimitEnabled;
    private final int maxPingsPerSecond;
    private final ViolationAction violationAction;
    private final long batchFlushIntervalSeconds;
    private final boolean telemetryPruningEnabled;
    private final int telemetryRetentionDays;

    private PerformanceConfig(int telemetryPoolSize, long profileExpirationSeconds,
                              boolean pingRateLimitEnabled, int maxPingsPerSecond,
                              ViolationAction violationAction, long batchFlushIntervalSeconds,
                              boolean telemetryPruningEnabled, int telemetryRetentionDays) {
        this.telemetryPoolSize = telemetryPoolSize;
        this.profileExpirationSeconds = profileExpirationSeconds;
        this.pingRateLimitEnabled = pingRateLimitEnabled;
        this.maxPingsPerSecond = maxPingsPerSecond;
        this.violationAction = violationAction;
        this.batchFlushIntervalSeconds = batchFlushIntervalSeconds;
        this.telemetryPruningEnabled = telemetryPruningEnabled;
        this.telemetryRetentionDays = telemetryRetentionDays;
    }

    public static PerformanceConfig load(SuperChargedServer plugin) {
        Logger log = plugin.getLogger();
        FileConfiguration config;
        try {
            config = plugin.getConfigManager().performance();
            if (config == null) {
                throw new IllegalStateException("configs/performance.yml is missing from the config cache");
            }
        } catch (Exception e) {
            log.warning("[performance.yml] Could not be read (" + e.getMessage()
                    + ") — running entirely on built-in defaults.");
            config = new YamlConfiguration();
        }

        int poolSize = readInt(config, log,
                "threading.telemetry-pool-size", 2, 1, 8);
        long expiration = readLong(config, log,
                "cache-settings.profile-expiration-seconds", 900L, 61L, Long.MAX_VALUE);
        boolean rateLimitEnabled = readBoolean(config, log,
                "network-protection.ping-rate-limiting.enabled", true);
        int maxPings = readInt(config, log,
                "network-protection.ping-rate-limiting.max-pings-per-second", 5, 1, 50);
        ViolationAction action = readAction(config, log,
                "network-protection.ping-rate-limiting.violation-action", ViolationAction.SERVE_STATIC_FALLBACK);
        long flushInterval = readLong(config, log,
                "database.batch-flush-interval-seconds", 30L, 1L, 3600L);
        boolean pruningEnabled = readBoolean(config, log,
                "database.telemetry-pruning.enabled", true);
        int retentionDays = readInt(config, log,
                "database.telemetry-pruning.retention-days", 30, 1, 3650);

        return new PerformanceConfig(poolSize, expiration, rateLimitEnabled, maxPings,
                action, flushInterval, pruningEnabled, retentionDays);
    }

    private static int readInt(FileConfiguration config, Logger log, String path,
                               int def, int min, int max) {
        try {
            if (!config.isSet(path)) return def;
            if (!config.isInt(path)) {
                log.warning("[performance.yml] '" + path + "' is set to '" + config.get(path)
                        + "' which is not a whole number — using default " + def + ".");
                return def;
            }
            int value = config.getInt(path);
            if (value < min || value > max) {
                int clamped = Math.max(min, Math.min(max, value));
                log.warning("[performance.yml] '" + path + "' = " + value
                        + " is outside the allowed range " + min + "-" + max
                        + " — clamped to " + clamped + ".");
                return clamped;
            }
            return value;
        } catch (Exception e) {
            log.warning("[performance.yml] Failed to read '" + path + "' ("
                    + e.getMessage() + ") — using default " + def + ".");
            return def;
        }
    }

    private static long readLong(FileConfiguration config, Logger log, String path,
                                 long def, long min, long max) {
        try {
            if (!config.isSet(path)) return def;
            if (!config.isInt(path) && !config.isLong(path)) {
                log.warning("[performance.yml] '" + path + "' is set to '" + config.get(path)
                        + "' which is not a valid number — using default " + def + ".");
                return def;
            }
            long value = config.getLong(path);
            if (value < min || value > max) {
                long clamped = Math.max(min, Math.min(max, value));
                log.warning("[performance.yml] '" + path + "' = " + value
                        + " must be at least " + min + " — clamped to " + clamped + ".");
                return clamped;
            }
            return value;
        } catch (Exception e) {
            log.warning("[performance.yml] Failed to read '" + path + "' ("
                    + e.getMessage() + ") — using default " + def + ".");
            return def;
        }
    }

    private static boolean readBoolean(FileConfiguration config, Logger log, String path, boolean def) {
        try {
            if (!config.isSet(path)) return def;
            if (!config.isBoolean(path)) {
                log.warning("[performance.yml] '" + path + "' is set to '" + config.get(path)
                        + "' which is not true/false — using default " + def + ".");
                return def;
            }
            return config.getBoolean(path);
        } catch (Exception e) {
            log.warning("[performance.yml] Failed to read '" + path + "' ("
                    + e.getMessage() + ") — using default " + def + ".");
            return def;
        }
    }

    private static ViolationAction readAction(FileConfiguration config, Logger log,
                                              String path, ViolationAction def) {
        try {
            String raw = config.getString(path, def.name());
            return ViolationAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            log.warning("[performance.yml] '" + path + "' must be DROP_CONNECTION or "
                    + "SERVE_STATIC_FALLBACK — using default " + def.name() + ".");
            return def;
        }
    }
}