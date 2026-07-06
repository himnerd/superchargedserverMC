package com.superchargedserver.command;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.SuperAccount;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LinkCommand implements CommandExecutor {

    private final SuperChargedServer plugin;

    public LinkCommand(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!plugin.getDiscordManager().isReady()) {
            plugin.msg(player, "<red>The Discord bridge is currently offline — try again later.");
            return true;
        }
        SuperAccount account = plugin.getAccountManager().getOrCreate(player);
        if (account.isLinkedToDiscord()) {
            plugin.msg(player, "<green>Your SuperAccount is already linked to Discord.");
            return true;
        }
        if (!plugin.getAccountManager().canLink(player.getUniqueId())) {
            plugin.msg(player, "<red>This account recently unlinked — linking is on cooldown.");
            return true;
        }
        String code = plugin.getLinkCodeManager().generateShortForAccount(account.getAccountId());
        int minutes = plugin.getConfigManager().superAccounts()
                .getInt("superlogin.code-expiration-seconds", 300) / 60;
        plugin.msg(player, "<gray>Your link code: <gradient:#00E5FF:#7B2FFF><bold>" + code + "</bold></gradient>");
        plugin.msg(player, "<gray>Type <white>/link " + code + "<gray> in our Discord server within <white>"
                + minutes + " minute" + (minutes == 1 ? "" : "s") + "<gray> to finish linking.");
        return true;
    }
}