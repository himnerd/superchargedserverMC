package com.superchargedserver.listener;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerConnectionListener implements Listener {

    private final SuperChargedServer plugin;

    public PlayerConnectionListener(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getPerformanceManager().markJoin(event.getPlayer().getUniqueId());
        plugin.getConnectionMessagesManager().applyJoin(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getPerformanceManager().markQuit(event.getPlayer().getUniqueId());
        plugin.getConnectionMessagesManager().applyQuit(event);
    }
}