package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /aihelp <question> — in-game gateway into the same AI support core the
 * Discord tickets use. The LLM/cache pipeline runs fully async; delivery
 * hops back onto the main tick loop before touching the player.
 */
public class InGameHelpExecutor implements CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long COOLDOWN_MS = 8000;

    private final SuperChargedServer plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public InGameHelpExecutor(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /aihelp.");
            return true;
        }
        AISupportManager manager = plugin.getAiSupportManager();
        if (manager == null || !manager.isEnabled()) {
            player.sendMessage(MM.deserialize("<red>The AI support assistant is currently disabled.</red>"));
            return true;
        }
        String prefix = "<gray>[</gray><gradient:gold:yellow>" + manager.displayName()
                + "</gradient><gray>]</gray> ";
        if (args.length == 0) {
            player.sendMessage(MM.deserialize(prefix + "<yellow>Usage: /aihelp <question></yellow>"));
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            player.sendMessage(MM.deserialize(prefix
                    + "<red>Please wait a moment before asking another question.</red>"));
            return true;
        }
        cooldowns.put(player.getUniqueId(), now);

        String question = String.join(" ", args);
        player.sendMessage(MM.deserialize(prefix + "<yellow>Analyzing your request, please wait...</yellow>"));

        manager.answer(question, answer -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            Component prefixComponent = MM.deserialize(prefix);
            if (answer.escalate() && (answer.text() == null || answer.text().isEmpty())) {
                player.sendMessage(MM.deserialize(prefix + "<red>I couldn't resolve this with enough "
                        + "confidence — please contact a staff member or open a support ticket.</red>"));
                return;
            }
            for (String line : answer.text().split("\n")) {
                player.sendMessage(prefixComponent.append(Component.text(line, NamedTextColor.WHITE)));
            }
            if (answer.fromCache()) {
                player.sendMessage(MM.deserialize(prefix
                        + "<dark_gray><italic>(answered from verified FAQ cache)</italic></dark_gray>"));
            }
        }));
        return true;
    }
}