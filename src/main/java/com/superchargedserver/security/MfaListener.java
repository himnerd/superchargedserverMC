package com.superchargedserver.security;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class MfaListener implements Listener {

    private final SuperChargedServer plugin;

    public MfaListener(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getSecurityManager().isFrozen(player)) return;
        event.setCancelled(true);
        plugin.getSecurityManager().verifyMfa(player, event.getMessage());
    }
}