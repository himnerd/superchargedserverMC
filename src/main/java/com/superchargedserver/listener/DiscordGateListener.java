package com.superchargedserver.listener;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.SuperAccount;
import com.superchargedserver.util.ColorUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Enforces {@code require-discord-presence} from discord.yml: players must
 * have a SuperAccount linked to a Discord identity that is currently a
 * member of the configured guild, or login is denied with instructions.
 */
public class DiscordGateListener implements Listener {

    private final SuperChargedServer plugin;

    public DiscordGateListener(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        FileConfiguration config = plugin.getConfigManager().discord();
        if (!config.getBoolean("require-discord-presence.enabled", false)) return;
        if (!plugin.getDiscordManager().isReady()) return;

        SuperAccount account = plugin.getAccountManager().getByPlayer(event.getUniqueId());
        if (account == null) {
            account = plugin.getAccountManager().loadIntoCache(event.getUniqueId());
        }

        if (account == null || !account.isLinkedToDiscord()) {
            deny(event, config.getString("require-discord-presence.kick-messages.not-linked",
                    "<red>You must link a Discord account to play."));
            return;
        }

        if (account.isBanned()) {
            deny(event, config.getString("require-discord-presence.kick-messages.banned",
                    "<dark_red>Your SuperAccount has been banned."));
            return;
        }

        if (!plugin.getDiscordManager().isGuildMember(account.getDiscordId())) {
            deny(event, config.getString("require-discord-presence.kick-messages.not-in-guild",
                    "<red>You must be a member of our Discord server."));
        }
    }

    private void deny(AsyncPlayerPreLoginEvent event, String message) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                ColorUtil.colorize(plugin.getConfigManager().brand(message)));
    }
}