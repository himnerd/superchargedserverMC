package com.superchargedserver.motd;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.motd.MotdManager;
import com.superchargedserver.motd.MotdProfile;
import com.superchargedserver.util.ColorUtil;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated maintenance state loaded from configs/tools/maintenance.yml.
 * While active (manual toggle or scheduled window), the ping listener
 * serves {@link #getPresentation()} exclusively and motd.yml profiles
 * are never evaluated.
 */
@Getter
public class MaintenanceManager implements Listener {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SuperChargedServer plugin;

    private boolean enabled;
    private volatile boolean scheduleActive;
    private String bypassPermission;
    private String kickMessage;
    private MotdProfile presentation;

    private boolean scheduleEnabled;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String warningMessage;
    private List<Integer> warningThresholds;
    private final Set<Integer> firedWarnings = ConcurrentHashMap.newKeySet();
    private BukkitTask schedulerTask;

    private boolean commandLockdownEnabled;
    private Set<String> commandWhitelist;
    private String deniedCommandMessage;

    private String activatedBroadcast;
    private String liftedBroadcast;

    public MaintenanceManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    /** Maintenance is live if manually enabled OR a scheduled window is active. */
    public boolean isEnabled() {
        return enabled || scheduleActive;
    }

    public void load() {
        boolean wasActive = isEnabled();
        FileConfiguration config = plugin.getConfigManager().maintenance();
        enabled = config.getBoolean("enabled", false);
        bypassPermission = config.getString("bypass-permission", "supercharged.maintenance.bypass");
        kickMessage = config.getString("kick-message", "<red>The server is under maintenance.");
        presentation = MotdManager.parseProfile("maintenance", config);

        activatedBroadcast = config.getString("broadcast.activated", "<red>Maintenance mode is now ACTIVE.");
        liftedBroadcast = config.getString("broadcast.lifted", "<green>Maintenance mode has been lifted.");

        commandLockdownEnabled = config.getBoolean("command-lockdown.enabled", true);
        Set<String> whitelist = new HashSet<>();
        for (String entry : config.getStringList("command-lockdown.whitelist")) {
            whitelist.add(entry.toLowerCase(Locale.ROOT).replace("/", "").trim());
        }
        commandWhitelist = whitelist;
        deniedCommandMessage = config.getString("command-lockdown.denied-command-message",
                "<red>Command /{command} is disabled during maintenance.");

        scheduleEnabled = config.getBoolean("schedule.enabled", false);
        startTime = parseTime(config.getString("schedule.start-time", ""));
        endTime = parseTime(config.getString("schedule.end-time", ""));
        warningMessage = config.getString("schedule.warning-message",
                "<yellow>Scheduled maintenance begins in {time}!");
        List<Integer> thresholds = new ArrayList<>(config.getIntegerList("schedule.warning-thresholds-seconds"));
        if (thresholds.isEmpty()) {
            thresholds = new ArrayList<>(List.of(600, 300, 60, 10));
        }
        thresholds.sort(Comparator.reverseOrder());
        warningThresholds = thresholds;
        firedWarnings.clear();
        int interval = Math.max(1, config.getInt("schedule.check-interval-seconds", 5));

        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
        if (scheduleEnabled && startTime != null && endTime != null && endTime.isAfter(startTime)) {
            schedulerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::tickSchedule, 20L * interval, 20L * interval);
            plugin.getLogger().info("[Maintenance] Scheduler armed: " + startTime.format(TIME_FORMAT)
                    + " -> " + endTime.format(TIME_FORMAT));
        } else {
            if (scheduleEnabled) {
                plugin.getLogger().warning("[Maintenance] Schedule enabled but start/end times are invalid — scheduler disarmed.");
            }
            scheduleActive = false;
        }

        if (isEnabled() && !wasActive) {
            kickNonBypassPlayers();
        }
        if (isEnabled()) {
            plugin.getLogger().warning("Maintenance mode is ACTIVE — motd.yml profiles are bypassed.");
        }
    }

    /** Programmatic manual toggle — must be called on the main thread. */
    public void setMaintenance(boolean active) {
        if (enabled == active) return;
        enabled = active;
        applyStateChange(active);
    }

    private LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.trim(), TIME_FORMAT);
        } catch (DateTimeParseException ex) {
            plugin.getLogger().warning("[Maintenance] Invalid schedule time '" + raw
                    + "' — expected format yyyy-MM-dd HH:mm:ss.");
            return null;
        }
    }

    /**
     * Runs on the async scheduler thread. Only reads the system clock and
     * plain state; every Bukkit interaction is dispatched back to the main
     * thread so the game loop is never touched from here.
     */
    private void tickSchedule() {
        LocalDateTime now = LocalDateTime.now();
        boolean inWindow = !now.isBefore(startTime) && now.isBefore(endTime);

        if (inWindow && !scheduleActive) {
            scheduleActive = true;
            Bukkit.getScheduler().runTask(plugin, () -> applyStateChange(true));
        } else if (!inWindow && scheduleActive) {
            scheduleActive = false;
            Bukkit.getScheduler().runTask(plugin, () -> applyStateChange(false));
        } else if (!inWindow && now.isBefore(startTime)) {
            long untilStart = Duration.between(now, startTime).getSeconds();
            boolean fire = false;
            for (int threshold : warningThresholds) {
                if (untilStart <= threshold && firedWarnings.add(threshold)) {
                    fire = true;
                }
            }
            if (fire) {
                String message = warningMessage.replace("{time}", formatDuration(untilStart));
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.broadcast(ColorUtil.colorize(message)));
            }
        }
    }

    /** Main-thread only: kicks, broadcasts and Discord sync on state flip. */
    private void applyStateChange(boolean active) {
        if (active) {
            kickNonBypassPlayers();
            Bukkit.broadcast(ColorUtil.colorize(activatedBroadcast));
            plugin.getLogger().warning("[Maintenance] Lockdown ACTIVATED.");
        } else {
            firedWarnings.clear();
            Bukkit.broadcast(ColorUtil.colorize(liftedBroadcast));
            plugin.getLogger().info("[Maintenance] Lockdown lifted — server back online.");
        }
        plugin.getDiscordManager().announceMaintenance(active);
    }

    private void kickNonBypassPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(bypassPermission)) {
                player.kick(ColorUtil.colorize(kickMessage));
            }
        }
    }

    private String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        if (minutes > 0 && remainder > 0) return minutes + "m " + remainder + "s";
        if (minutes > 0) return minutes + "m";
        return remainder + "s";
    }

    @EventHandler
    public void onLogin(PlayerLoginEvent event) {
        if (isEnabled() && !event.getPlayer().hasPermission(bypassPermission)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, ColorUtil.colorize(kickMessage));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!commandLockdownEnabled || !isEnabled()) return;
        String base = event.getMessage().substring(1);
        int space = base.indexOf(' ');
        if (space != -1) base = base.substring(0, space);
        int colon = base.indexOf(':');
        if (colon != -1) base = base.substring(colon + 1);
        base = base.toLowerCase(Locale.ROOT);
        if (commandWhitelist.contains(base)) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ColorUtil.colorize(deniedCommandMessage.replace("{command}", base)));
    }
}