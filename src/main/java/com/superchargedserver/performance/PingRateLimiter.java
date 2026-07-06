package com.superchargedserver.performance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allocation-light per-IP server-list ping throttle. Runs entirely on the
 * netty threads — a fixed one-second window counter per IP, no heavy
 * objects created on the hot path.
 */
public class PingRateLimiter {

    private static final class Window {
        long second;
        int count;

        Window(long second) {
            this.second = second;
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxPerSecond;

    public PingRateLimiter(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }

    public boolean allow(String ip) {
        long now = System.currentTimeMillis() / 1000L;
        Window window = windows.computeIfAbsent(ip, k -> new Window(now));
        synchronized (window) {
            if (window.second != now) {
                window.second = now;
                window.count = 1;
                return true;
            }
            return ++window.count <= maxPerSecond;
        }
    }

    /** Drops windows idle for 10+ seconds so the map cannot grow unbounded. */
    public void prune() {
        long cutoff = System.currentTimeMillis() / 1000L - 10;
        windows.values().removeIf(window -> {
            synchronized (window) {
                return window.second < cutoff;
            }
        });
    }
}