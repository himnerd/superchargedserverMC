package com.superchargedserver.command;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.tools.ConnectionMessagesManager;
import com.superchargedserver.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public class SuperAdminCommand implements CommandExecutor {

    private final SuperChargedServer plugin;

    public SuperAdminCommand(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ColorUtil.colorize("<red>Usage: /superadmin <maintenance|joinmsg|quitmsg|ai|ai-reload|reload>"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "maintenance" -> {
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.colorize(
                            "<yellow>Maintenance is currently " + (plugin.getMaintenanceManager().isEnabled() ? "<red>ACTIVE" : "<green>OFF")));
                    return true;
                }
                boolean active = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                plugin.getMaintenanceManager().setMaintenance(active);
                sender.sendMessage(ColorUtil.colorize(
                        "<gray>Maintenance set to " + (active ? "<red>ACTIVE" : "<green>OFF")));
            }
            case "joinmsg", "quitmsg" -> {
                boolean join = args[0].equalsIgnoreCase("joinmsg");
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize("<red>Usage: /superadmin "
                            + (join ? "joinmsg" : "quitmsg") + " <player> <message...|hide|clear>"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                ConnectionMessagesManager messages = plugin.getConnectionMessagesManager();
                String type = join ? "join" : "quit";
                if (value.equalsIgnoreCase("clear")) {
                    messages.clearOverride(target.getUniqueId(), join);
                    sender.sendMessage(ColorUtil.colorize("<gray>Custom " + type + " message for <white>"
                            + args[1] + "</white> cleared — profiles apply again."));
                } else if (messages.isHidden(value)) {
                    messages.setOverride(target.getUniqueId(), args[1], join, value);
                    sender.sendMessage(ColorUtil.colorize("<gray>The " + type + " message of <white>"
                            + args[1] + "</white> is now <red>hidden</red>."));
                } else {
                    messages.setOverride(target.getUniqueId(), args[1], join, value);
                    sender.sendMessage(ColorUtil.colorize("<gray>Custom " + type + " message for <white>"
                            + args[1] + "</white> set. Preview:"));
                    sender.sendMessage(ColorUtil.colorize(value.replace("{player}", args[1])));
                }
            }
            case "ai" -> {
                sender.sendMessage(ColorUtil.colorize(
                        "<gray>AI engine status — use <white>/superadmin ai</white> once the GUI is built."));
            }
            case "ai-reload" -> {
                plugin.getConfigManager().load();
                if (plugin.getAiSupportManager() != null) plugin.getAiSupportManager().reload();
                sender.sendMessage(ColorUtil.colorize(
                        "<green>AI support engine reloading — knowledge feeds are re-ingesting asynchronously."));
            }
            case "reload" -> {
                plugin.getConfigManager().load();
                plugin.getMotdManager().load();
                plugin.getMaintenanceManager().load();
                plugin.getConnectionMessagesManager().load();
                plugin.getIconManager().load();
                sender.sendMessage(ColorUtil.colorize("<green>All configs and caches reloaded."));
            }
            default -> {
                sender.sendMessage(ColorUtil.colorize(
                        "<red>Usage: /superadmin <maintenance|joinmsg|quitmsg|ai|ai-reload|reload>"));
            }
        }
        return true;
    }
}