package com.superchargedserver.listener;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.motd.MaintenanceManager;
import com.superchargedserver.motd.MotdProfile;
import com.superchargedserver.performance.PerformanceConfig;
import com.superchargedserver.performance.PerformanceManager;
import com.superchargedserver.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.CachedServerIcon;

import java.util.List;
import java.util.UUID;

public class MotdListener implements Listener {

    private final SuperChargedServer plugin;

    public MotdListener(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerListPing(PaperServerListPingEvent event) {
        // DoS shield: violating IPs never reach icon resolution or
        // MiniMessage parsing. Pure in-memory counter check.
        PerformanceManager performance = plugin.getPerformanceManager();
        if (!performance.allowPing(event.getAddress())) {
            if (performance.getConfig().getViolationAction() == PerformanceConfig.ViolationAction.DROP_CONNECTION) {
                event.setCancelled(true);
            }
            return; // SERVE_STATIC_FALLBACK: untouched vanilla ping, zero work
        }

        // Maintenance-first intercept: short-circuit before any motd.yml
        // profile is evaluated.
        MaintenanceManager maintenance = plugin.getMaintenanceManager();
        if (maintenance.isEnabled()) {
            apply(event, maintenance.getPresentation());
            return;
        }

        MotdProfile profile = plugin.getMotdManager().getActiveProfile();
        if (profile != null) {
            apply(event, profile);
        }
    }

    private void apply(PaperServerListPingEvent event, MotdProfile profile) {
        String raw = plugin.getMotdManager().getMotdLine(profile);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(null, raw);
        }
        event.motd(ColorUtil.colorize(raw));

        CachedServerIcon icon = plugin.getIconManager().resolve(profile);
        if (icon != null) {
            event.setServerIcon(icon);
        }

        if (profile.isHidePlayers()) {
            // Absolute masking: hides the count and suppresses all hover
            // metrics, overriding every other playerCount manipulation.
            event.setHidePlayers(true);
            if (!profile.getText().isEmpty()) {
                event.setVersion(ColorUtil.legacy(profile.getText()));
                event.setProtocolVersion(-1);
            }
            return;
        }

        if (profile.getExtraPlayers() > 0) {
            event.setNumPlayers(event.getNumPlayers() + profile.getExtraPlayers());
        }
        if (profile.getMaxPlayers() >= 0) {
            event.setMaxPlayers(profile.getMaxPlayers());
        }
        if (!profile.getText().isEmpty()) {
            event.setVersion(ColorUtil.legacy(profile.getText()));
            event.setProtocolVersion(-1);
        }

        List<String> hover = profile.getHover();
        if (!hover.isEmpty()) {
            applyHover(event, hover);
        }
    }

    private void applyHover(PaperServerListPingEvent event, List<String> hover) {
        List<PaperServerListPingEvent.ListedPlayerInfo> listed = event.getListedPlayers();
        listed.clear();
        for (String line : hover) {
            listed.add(new PaperServerListPingEvent.ListedPlayerInfo(
                    ColorUtil.legacy(line), UUID.randomUUID()));
        }
    }
}