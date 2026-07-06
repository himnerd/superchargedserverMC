package com.superchargedserver.discord;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds live draft sessions keyed by Discord user ID with a 15-minute
 * idle expiration so abandoned dashboards never leak memory.
 */
public class DraftSessionManager {

    private static final long TTL_MILLIS = 15L * 60L * 1000L;

    private final Map<String, DraftSession> sessions = new ConcurrentHashMap<>();
    private BukkitTask purgeTask;

    public void startPurgeTask(Plugin plugin) {
        if (purgeTask != null) return;
        purgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> sessions.values().removeIf(session -> session.isExpired(TTL_MILLIS)),
                1200L, 1200L);
    }

    public void shutdown() {
        if (purgeTask != null) {
            purgeTask.cancel();
            purgeTask = null;
        }
        sessions.clear();
    }

    /** Returns a fresh session, discarding any previous draft for the user. */
    public DraftSession create(String userId) {
        DraftSession session = new DraftSession(userId);
        sessions.put(userId, session);
        return session;
    }

    public DraftSession get(String userId) {
        DraftSession session = sessions.get(userId);
        if (session == null || session.isExpired(TTL_MILLIS)) {
            sessions.remove(userId);
            return null;
        }
        session.touch();
        return session;
    }

    public DraftSession getOrCreate(String userId) {
        DraftSession session = get(userId);
        return session != null ? session : create(userId);
    }

    public void destroy(String userId) {
        sessions.remove(userId);
    }
}