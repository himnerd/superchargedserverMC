package com.superchargedserver.command;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.SuperAccount;
import com.superchargedserver.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ProfileCommand implements CommandExecutor {

    private final SuperChargedServer plugin;

    public ProfileCommand(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ColorUtil.colorize("<red>Only players can use this command."));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("status") && args.length > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(args[i]);
            }
            String status = sb.toString();
            SuperAccount account = plugin.getAccountManager().getOrCreate(player);
            account.setStatusMessage(status);
            plugin.getAccountManager().saveAsync(account);
            player.sendMessage(ColorUtil.colorize("<green>Status updated."));
            return true;
        }

        SuperAccount account = plugin.getAccountManager().getOrCreate(player);
        String name = account.getPrimaryName();
        String discord = account.isLinkedToDiscord() ? "<green>Linked" : "<red>Not linked";
        String points = String.valueOf(account.getPlayPoints());
        String banStatus = account.isBanned() ? "<red>Banned" : "<green>Clean";
        String mfaStatus = account.isMfaEnabled() ? "<green>Enabled" : "<gray>Disabled";
        String status = account.getStatusMessage().isEmpty() ? "" : "\n" + account.getStatusMessage();

        Component profile = ColorUtil.colorize(
                "<gradient:#00E5FF:#7B2FFF><bold>── " + name + "'s Profile ──</bold></gradient>"
                        + "\n<gray>Account: <white>" + account.getAccountId().toString().substring(0, 8) + "..."
                        + "\n<gray>Discord: " + discord
                        + "\n<gray>Play Points: <yellow>" + points
                        + "\n<gray>Status: " + banStatus
                        + "\n<gray>MFA: " + mfaStatus
                        + status)
                .clickEvent(ClickEvent.openUrl("https://discord.gg/supercharged"));
        player.sendMessage(profile);
        return true;
    }
}