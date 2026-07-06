package com.superchargedserver.command;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.util.ColorUtil;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class WhitelistCommand implements CommandExecutor {

    private final SuperChargedServer plugin;

    public WhitelistCommand(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.colorize("<red>Usage: /whitelist <add|remove> <name>"));
            return true;
        }

        String action = args[0].toLowerCase();
        String name = args[1];

        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) {
            sender.sendMessage(ColorUtil.colorize("<red>Player '" + name + "' not found."));
            return true;
        }

        BanList<?> whitelist = Bukkit.getBanList(BanList.Type.PROFILE);
        if (action.equals("add")) {
            whitelist.addBan(target.getName(), null, null, sender.getName());
            sender.sendMessage(ColorUtil.colorize("<green>Added " + name + " to the whitelist."));
        } else if (action.equals("remove")) {
            whitelist.pardon(target.getName());
            sender.sendMessage(ColorUtil.colorize("<green>Removed " + name + " from the whitelist."));
        } else {
            sender.sendMessage(ColorUtil.colorize("<red>Usage: /whitelist <add|remove> <name>"));
        }
        return true;
    }
}