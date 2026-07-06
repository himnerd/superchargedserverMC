package com.superchargedserver.command;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class WikisCommand implements CommandExecutor {

    private final SuperChargedServer plugin;

    public WikisCommand(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ColorUtil.colorize("<red>Only players can use this command."));
            return true;
        }

        int page = 0;
        if (args.length > 0) {
            try {
                page = Math.max(0, Integer.parseInt(args[0]) - 1);
            } catch (NumberFormatException ignored) {
            }
        }

        plugin.getWikiManager().openGUI(player, page);
        return true;
    }
}