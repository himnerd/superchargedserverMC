package com.superchargedserver.ai;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.api.events.SuperAccountAIAlertEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AnomalyEngine {

    private final SuperChargedServer plugin;
    private final Map<UUID, PlayerVector> vectors = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastScans = new ConcurrentHashMap<>();

    public record PlayerVector(AtomicInteger economyOps, AtomicInteger blocksBpM, AtomicInteger chatMessages,
                               AtomicInteger combatAttacks, long windowStart) {
        public PlayerVector() {
            this(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger(),
                    Instant.now().toEpochMilli());
        }
    }

    public AnomalyEngine(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void start() {
        FileConfiguration config = plugin.getConfigManager().aiEngine();
        if (!config.getBoolean("engine.enabled", true)) {
            plugin.getLogger().info("AI engine disabled via config.");
            return;
        }
        int intervalSeconds = config.getInt("engine.scan-interval-seconds", 60);
        int pruneHours = config.getInt("engine.data-pruning-hours", 72);

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            scan();
            prune(pruneHours);
        }, 20L * intervalSeconds, 20L * intervalSeconds);

        plugin.getLogger().info("AI anomaly engine started (every " + intervalSeconds + "s).");
    }

    public PlayerVector getVector(UUID playerId) {
        return vectors.computeIfAbsent(playerId, k -> new PlayerVector());
    }

    public int trackedCount() {
        return vectors.size();
    }

    private void scan() {
        FileConfiguration config = plugin.getConfigManager().aiEngine();
        double weightEconomy = config.getDouble("weights.economy", 0.40);
        double weightSocial = config.getDouble("weights.social", 0.25);
        double weightCombat = config.getDouble("weights.combat", 0.35);
        double thresholdLow = config.getDouble("thresholds.low", 40.0);
        double thresholdMedium = config.getDouble("thresholds.medium", 70.0);
        double thresholdCritical = config.getDouble("thresholds.critical", 90.0);

        double maxCurrency = config.getDouble("telemetry-tracking.economy.max-currency-per-minute", 5000.0);
        int maxBlocks = config.getInt("telemetry-tracking.economy.max-blocks-per-minute", 250);
        int maxAttacks = config.getInt("telemetry-tracking.combat.max-attacks-per-minute", 90);

        long now = Instant.now().toEpochMilli();

        for (Map.Entry<UUID, PlayerVector> entry : vectors.entrySet()) {
            PlayerVector v = entry.getValue();
            long elapsed = now - v.windowStart();
            double minutes = Math.max(1.0, elapsed / 60000.0);

            double economyScore = 0;
            double socialScore = 0;
            double combatScore = 0;

            if (config.getBoolean("telemetry-tracking.economy.enabled", true)) {
                double rate = v.economyOps().get() / minutes;
                if (rate > maxCurrency) {
                    economyScore = Math.min(100, ((rate - maxCurrency) / maxCurrency) * 100);
                }
                double blockRate = v.blocksBpM().get() / minutes;
                if (blockRate > maxBlocks) {
                    economyScore = Math.max(economyScore, Math.min(100, ((blockRate - maxBlocks) / maxBlocks) * 100));
                }
            }

            if (config.getBoolean("telemetry-tracking.combat.enabled", true)) {
                double attackRate = v.combatAttacks().get() / minutes;
                if (attackRate > maxAttacks) {
                    combatScore = Math.min(100, ((attackRate - maxAttacks) / maxAttacks) * 100);
                }
            }

            double totalScore = economyScore * weightEconomy + socialScore * weightCombat + combatScore * weightCombat;

            if (totalScore >= thresholdLow) {
                String level = totalScore >= thresholdCritical ? "CRITICAL"
                        : totalScore >= thresholdMedium ? "MEDIUM" : "LOW";
                String message = "Anomaly [" + level + "] for " + entry.getKey()
                        + ": score=" + String.format("%.1f", totalScore);
                plugin.getLogger().warning(message);

                if (totalScore >= thresholdMedium) {
                    plugin.getDiscordManager().logSecurity("AI Alert [" + level + "]",
                            "Player `" + entry.getKey() + "` flagged with score **"
                                    + String.format("%.1f", totalScore) + "**.");
                }

                SuperAccountAIAlertEvent alertEvent = new SuperAccountAIAlertEvent(
                        entry.getKey(), totalScore, level, v);
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.getPluginManager().callEvent(alertEvent));

                if (totalScore >= thresholdCritical && config.getBoolean("automated-actions.enabled", false)) {
                    List<String> commands = config.getStringList("automated-actions.commands");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (String cmd : commands) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                    cmd.replace("%player%", entry.getKey().toString()));
                        }
                    });
                }
            }
        }
    }

    private void prune(int pruneHours) {
        long cutoff = Instant.now().minus(pruneHours, ChronoUnit.HOURS).toEpochMilli();
        vectors.entrySet().removeIf(e -> e.getValue().windowStart() < cutoff);

        File aiFolder = new File(plugin.getDataFolder(), "AI");
        if (aiFolder.isDirectory()) {
            File[] files = aiFolder.listFiles((dir, name) -> name.endsWith(".log"));
            if (files != null) {
                for (File f : files) {
                    if (f.lastModified() < cutoff) f.delete();
                }
            }
        }
    }

    public void shutdown() {
        // Cleanup handled by task cancellation
    }
}