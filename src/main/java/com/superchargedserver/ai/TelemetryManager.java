package com.superchargedserver.ai;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TelemetryManager {

    private final SuperChargedServer plugin;
    private final Queue<TelemetryEvent> queue = new ConcurrentLinkedQueue<>();
    private BukkitTask consumerTask;

    public record TelemetryEvent(UUID playerId, String category, double value, long timestamp) {}

    public TelemetryManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int flushTicks = plugin.getConfigManager().telemetry().getInt("ingestion.flush-interval-ticks", 100);
        int warningSize = plugin.getConfigManager().telemetry().getInt("ingestion.queue-warning-size", 10000);

        consumerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (queue.size() > warningSize) {
                plugin.getLogger().warning("Telemetry queue at " + queue.size() + " events — consider raising pool size.");
            }
            int drained = 0;
            TelemetryEvent event;
            while ((event = queue.poll()) != null) {
                writeToFile(event);
                drained++;
            }
            if (drained > 0) {
                plugin.getLogger().fine("Flushed " + drained + " telemetry event(s).");
            }
        }, flushTicks, flushTicks);

        plugin.getLogger().info("Telemetry ingestion started.");
    }

    public void push(TelemetryEvent event) {
        queue.offer(event);
    }

    public void pushEconomy(Player player, double amount) {
        push(new TelemetryEvent(player.getUniqueId(), "economy", amount, Instant.now().toEpochMilli()));
    }

    public void rejectMainThread() {
        // Telemetry is always async — noop
    }

    private void writeToFile(TelemetryEvent event) {
        File dataDir = new File(plugin.getDataFolder(), "AI");
        dataDir.mkdirs();
        File file = new File(dataDir, "telemetry.log");
        String line = event.timestamp() + "," + event.playerId() + ","
                + event.category() + "," + event.value() + System.lineSeparator();
        try {
            Files.write(file.toPath(), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write telemetry: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (consumerTask != null) consumerTask.cancel();
    }
}