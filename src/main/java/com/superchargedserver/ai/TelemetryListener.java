package com.superchargedserver.ai;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class TelemetryListener implements Listener {

    private final SuperChargedServer plugin;

    public TelemetryListener(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        plugin.getAnomalyEngine().getVector(event.getPlayer().getUniqueId()).blocksBpM().incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        plugin.getAnomalyEngine().getVector(event.getPlayer().getUniqueId()).blocksBpM().incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        plugin.getAnomalyEngine().getVector(event.getPlayer().getUniqueId()).chatMessages().incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof org.bukkit.entity.Player player) {
            plugin.getAnomalyEngine().getVector(player.getUniqueId()).combatAttacks().incrementAndGet();
        }
    }
}