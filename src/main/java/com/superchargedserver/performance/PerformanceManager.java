package com.superchargedserver.performance;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.SuperAccount;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking production pipeline: worker thread pool for database work,
 * write-behind batching for SuperAccount saves, IP-based ping throttling,
 * inactive profile cache expiration and weekly /AI/ telemetry pruning.
 */
@Getter
public class PerformanceManager {

    private final SuperChargedServer plugin;
    private PerformanceConfig config;
    private PingRateLimiter rateLimiter;
    private ExecutorService workerPool;

    /** Write-ahead cache: dirty accounts flushed to the database in bulk. */
    private final Set<SuperAccount> pendingSaves = ConcurrentHashMap.newKeySet();
    /** player uuid -> logout timestamp (millis), for cache expiration. */
    private final java.util.Map<UUID, Long> quitTimes = new ConcurrentHashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    public PerformanceManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void load() {
        config = PerformanceConfig.load(plugin);
        rateLimiter = new PingRateLimiter(config.getMaxPingsPerSecond());
    }

    public void start() {
        AtomicInteger threadId = new AtomicInteger();
        workerPool = Executors.newFixedThreadPool(config.getTelemetryPoolSize(), runnable -> {
            Thread thread = new Thread(runnable, "SCS-Worker-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        long flushTicks = config.getBatchFlushIntervalSeconds() * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            flushPendingSaves();
            rateLimiter.prune();
        }, flushTicks, flushTicks));

        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::checkExpirations, 1200L, 1200L));

        if (config.isTelemetryPruningEnabled()) {
            tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::pruneTelemetry, 20L * 3600L, 20L * 604800L));
        }
    }

    /** Netty-thread hot path — pure in-memory counter check. */
    public boolean allowPing(InetAddress address) {
        if (!config.isPingRateLimitEnabled() || address == null) return true;
        return rateLimiter.allow(address.getHostAddress());
    }

    public void queueSave(SuperAccount account) {
        pendingSaves.add(account);
    }

    /** Pushes work onto the shared worker pool, off the main thread. */
    public void async(Runnable task) {
        workerPool.execute(task);
    }

    public void markJoin(UUID playerUuid) {
        quitTimes.remove(playerUuid);
    }

    public void markQuit(UUID playerUuid) {
        quitTimes.put(playerUuid, System.currentTimeMillis());
    }

    private void flushPendingSaves() {
        if (pendingSaves.isEmpty()) return;
        List<SuperAccount> batch = new ArrayList<>(pendingSaves);
        batch.forEach(pendingSaves::remove);
        workerPool.execute(() -> {
            for (SuperAccount account : batch) {
                plugin.getDatabaseManager().saveAccount(account);
            }
        });
    }

    private void checkExpirations() {
        long cutoff = System.currentTimeMillis() - config.getProfileExpirationSeconds() * 1000L;
        List<UUID> expired = new ArrayList<>();
        quitTimes.forEach((uuid, time) -> {
            if (time < cutoff) expired.add(uuid);
        });
        if (expired.isEmpty()) return;
        expired.forEach(quitTimes::remove);
        // Eviction touches the online-player list and account maps — main thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID uuid : expired) {
                plugin.getAccountManager().evictIfOffline(uuid);
            }
        });
    }

    private void pruneTelemetry() {
        File aiFolder = new File(plugin.getDataFolder(), "AI");
        File[] files = aiFolder.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - config.getTelemetryRetentionDays() * 86400000L;
        int pruned = 0;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff && file.delete()) {
                pruned++;
            }
        }
        if (pruned > 0) {
            plugin.getLogger().info("Pruned " + pruned + " stale telemetry file(s) from /AI/.");
        }
    }

    public void shutdown() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        // Final synchronous drain — REPLACE INTO makes double-saves harmless.
        for (SuperAccount account : pendingSaves) {
            plugin.getDatabaseManager().saveAccount(account);
        }
        pendingSaves.clear();
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                workerPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}