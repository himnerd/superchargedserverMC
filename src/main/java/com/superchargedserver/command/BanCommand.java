package com.superchargedserver.command;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.SuperAccount;
import com.superchargedserver.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class BanCommand implements CommandExecutor {

    private final SuperChargedServer plugin;

    public BanCommand(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ColorUtil.colorize(
                    "<red>Usage: /" + label + " <name> [reason]"));
            return true;
        }

        String targetName = args[0];
        StringBuilder reason = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (reason.length() > 0) reason.append(" ");
            reason.append(args[i]);
        }

        SuperAccount account = plugin.getAccountManager().getByName(targetName);
        if (account == null) {
            sender.sendMessage(ColorUtil.colorize(
                    "<red>No SuperAccount found for '" + targetName + "'."));
            return true;
        }

        String finalReason = reason.isEmpty() ? "Banned by " + sender.getName() : reason.toString();
        if (label.equalsIgnoreCase("unban")) {
            plugin.getAccountManager().unbanAccount(account);
            sender.sendMessage(ColorUtil.colorize(
                    "<green>Unbanned " + account.getPrimaryName() + "."));
        } else {
            plugin.getAccountManager().banAccount(account, finalReason, sender.getName());
            sender.sendMessage(ColorUtil.colorize(
                    "<red>Banned " + account.getPrimaryName() + " — " + finalReason));
        }
        return true;
    }
}