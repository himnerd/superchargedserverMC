package com.superchargedserver.command;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.LinkCodeManager;
import com.superchargedserver.account.LinkCodeManager.PendingLink;
import com.superchargedserver.account.SuperAccount;
import com.superchargedserver.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SuperLoginCommand implements CommandExecutor {

    private final SuperChargedServer plugin;

    public SuperLoginCommand(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ColorUtil.colorize("<red>Only players can use this command."));
            return true;
        }

        SuperAccount account = plugin.getAccountManager().getOrCreate(player);

        if (args.length == 0) {
            // Generate a code for linking
            LinkCodeManager codes = plugin.getLinkCodeManager();
            String code = codes.generateForAccount(account.getAccountId());
            player.sendMessage(ColorUtil.colorize(
                    "<green>Your linking code: <white><bold>" + code
                            + "</bold></white></green>\n<gray>DM this to the Discord bot via "
                            + "<white>/superlogin <code></white><gray> within 5 minutes."));
            return true;
        }

        String code = args[0];
        String ip = player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();

        if (plugin.getLinkCodeManager().isLockedOut(ip)) {
            player.sendMessage(ColorUtil.colorize(
                    "<red>Too many failed attempts. Try again in a few minutes."));
            return true;
        }

        // Try Discord-side code first
        PendingLink link = plugin.getLinkCodeManager().redeem(code, ip);
        if (link == null || link.discordId() == null) {
            player.sendMessage(ColorUtil.colorize(
                    "<red>Invalid or expired code. Generate a new one with <white>/superlogin</white> and try again."));
            return true;
        }

        plugin.getAccountManager().linkDiscord(account, link.discordId());
        player.sendMessage(ColorUtil.colorize(
                "<green>Your Discord account has been linked to your SuperAccount!"));
        return true;
    }
}