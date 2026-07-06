package com.superchargedserver.motd;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.CachedServerIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Discovers, validates (64x64 .png) and caches every icon under
 * plugins/SuperChargedServer/icons/ — including all subdirectories —
 * asynchronously at startup/reload. Ping handling only ever touches
 * these in-memory caches; no disk I/O occurs during a ping.
 */
public class IconManager {

    private final SuperChargedServer plugin;

    /** relative path ("events/christmas.png") -> cached icon */
    private final Map<String, CachedServerIcon> icons = new ConcurrentHashMap<>();
    /** directory key ("animations/night_pulse") -> frame-ordered icon pool */
    private final Map<String, List<CachedServerIcon>> pools = new ConcurrentHashMap<>();
    /** directory key -> global animation frame pointer */
    private final Map<String, AtomicInteger> framePointers = new ConcurrentHashMap<>();
    private final List<BukkitTask> tickers = new ArrayList<>();

    public IconManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void load() {
        tickers.forEach(BukkitTask::cancel);
        tickers.clear();
        icons.clear();
        pools.clear();
        framePointers.clear();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            scan();
            Bukkit.getScheduler().runTask(plugin, this::startTickers);
        });
    }

    private void scan() {
        File root = new File(plugin.getDataFolder(), "icons");
        if (!root.isDirectory()) return;
        Path rootPath = root.toPath();
        Map<String, List<String>> dirFiles = new HashMap<>();

        try (Stream<Path> stream = Files.walk(rootPath)) {
            List<Path> pngs = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .toList();

            for (Path p : pngs) {
                try {
                    BufferedImage image = ImageIO.read(p.toFile());
                    if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
                        plugin.getLogger().warning("Skipping icon " + rootPath.relativize(p)
                                + " — not a valid 64x64 .png.");
                        continue;
                    }
                    CachedServerIcon cached = Bukkit.loadServerIcon(p.toFile());
                    String rel = rootPath.relativize(p).toString().replace('\\', '/');
                    icons.put(rel, cached);
                    String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
                    dirFiles.computeIfAbsent(dir, k -> new ArrayList<>()).add(rel);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to cache icon " + p + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Icon directory scan failed: " + e.getMessage());
        }

        for (Map.Entry<String, List<String>> entry : dirFiles.entrySet()) {
            List<String> files = entry.getValue();
            files.sort(FRAME_ORDER);
            List<CachedServerIcon> frames = new ArrayList<>(files.size());
            for (String rel : files) frames.add(icons.get(rel));
            pools.put(entry.getKey(), List.copyOf(frames));
        }
        plugin.getLogger().info("Cached " + icons.size() + " server icon(s) across "
                + pools.size() + " folder pool(s).");
    }

    /** Numeric-aware ordering so 2.png sorts before 10.png in animations. */
    private static final Comparator<String> FRAME_ORDER = (a, b) -> {
        String an = baseName(a);
        String bn = baseName(b);
        if (an.matches("\\d+") && bn.matches("\\d+")) {
            return Integer.compare(Integer.parseInt(an), Integer.parseInt(bn));
        }
        return a.compareTo(b);
    };

    private static String baseName(String rel) {
        int slash = rel.lastIndexOf('/') + 1;
        int dot = rel.lastIndexOf('.');
        return rel.substring(slash, dot < slash ? rel.length() : dot);
    }

    /**
     * One async repeating task per animated profile advances that folder's
     * global frame pointer. Pings only read the pointer — the AtomicInteger
     * plus immutable frame lists make this race-free on the netty threads.
     */
    private void startTickers() {
        List<MotdProfile> all = new ArrayList<>(plugin.getMotdManager().getProfiles());
        MaintenanceManager maintenance = plugin.getMaintenanceManager();
        if (maintenance != null && maintenance.getPresentation() != null) {
            all.add(maintenance.getPresentation());
        }
        for (MotdProfile profile : all) {
            if (!profile.isAnimated()) continue;
            String key = poolKey(profile.getIcon());
            if (key == null) continue;
            List<CachedServerIcon> frames = pools.get(key);
            if (frames == null || frames.isEmpty()) {
                plugin.getLogger().warning("Profile '" + profile.getName()
                        + "' is animated but no cached frames exist in icons/" + key + "/");
                continue;
            }
            AtomicInteger pointer = framePointers.computeIfAbsent(key, k -> new AtomicInteger());
            long interval = Math.max(1, profile.getUpdateIntervalTicks());
            tickers.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, pointer::incrementAndGet, interval, interval));
        }
    }

    /**
     * Resolves a profile's icon path against the cache:
     * explicit file → that icon; directory/wildcard → animated frame or
     * random pool member. Returns null when nothing is cached for the path.
     */
    public CachedServerIcon resolve(MotdProfile profile) {
        String path = normalize(profile.getIcon());
        if (path == null) return null;

        String key = poolKey(path);
        if (key != null) {
            List<CachedServerIcon> frames = pools.get(key);
            if (frames == null || frames.isEmpty()) return null;
            if (profile.isAnimated()) {
                AtomicInteger pointer = framePointers.get(key);
                int index = pointer == null ? 0 : Math.floorMod(pointer.get(), frames.size());
                return frames.get(index);
            }
            return frames.get(ThreadLocalRandom.current().nextInt(frames.size()));
        }
        return icons.get(path);
    }

    /**
     * Returns the pool directory key if the path denotes a pool
     * (trailing slash, wildcard, or a bare directory name), else null.
     */
    private String poolKey(String path) {
        path = normalize(path);
        if (path == null) return null;
        if (path.endsWith("/*.png")) return path.substring(0, path.length() - 6 - (path.length() > 6 ? 1 : 0) + 1).replaceAll("/+$", "");
        if (path.endsWith("/*")) return path.substring(0, path.length() - 2);
        if (path.endsWith("/")) return path.substring(0, path.length() - 1);
        if (!icons.containsKey(path) && pools.containsKey(path)) return path;
        return null;
    }

    private String normalize(String path) {
        if (path == null || path.isEmpty()) return null;
        path = path.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        return path.isEmpty() ? null : path;
    }
}