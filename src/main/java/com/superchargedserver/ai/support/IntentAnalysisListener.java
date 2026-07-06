package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.configuration.file.FileConfiguration;

import java.awt.Color;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * High-speed local classification of incoming ticket messages. Critical
 * terms (dupe, exploit, crash, hacker, chargeback, …) instantly flag the
 * ticket high-priority and ping the emergency administrator roles — once
 * per channel to avoid alert spam.
 */
public class IntentAnalysisListener extends ListenerAdapter {

    private final SuperChargedServer plugin;
    private final Set<Long> flaggedChannels = ConcurrentHashMap.newKeySet();

    public IntentAnalysisListener(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfigManager().aiEngine();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        AISupportManager manager = plugin.getAiSupportManager();
        if (manager == null || !manager.isEnabled()) return;
        if (!event.isFromGuild() || event.getChannelType() != ChannelType.TEXT) return;
        if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
        TextChannel channel = event.getChannel().asTextChannel();
        if (!channel.getName().startsWith(cfg().getString("ai-support.tickets.channel-prefix", "ticket-"))) return;

        String content = event.getMessage().getContentRaw().toLowerCase(Locale.ROOT);
        String matched = null;
        for (String keyword : cfg().getStringList("ai-support.intent.critical-keywords")) {
            if (!keyword.isBlank() && content.contains(keyword.toLowerCase(Locale.ROOT))) {
                matched = keyword;
                break;
            }
        }
        if (matched == null || !flaggedChannels.add(channel.getIdLong())) return;

        String pings = cfg().getStringList("ai-support.intent.emergency-role-ids").stream()
                .filter(id -> !id.isBlank())
                .map(id -> "<@&" + id + ">")
                .collect(Collectors.joining(" "));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🚨 High-Priority Ticket Flagged")
                .setDescription("Critical term detected: `" + matched + "` — this ticket has been escalated "
                        + "for immediate administrator review.")
                .setColor(new Color(0xE74C3C))
                .setFooter("SuperCharged Intent Analysis")
                .setTimestamp(Instant.now());

        if (pings.isEmpty()) {
            channel.sendMessageEmbeds(embed.build()).queue(null, error -> { });
        } else {
            channel.sendMessage(pings).addEmbeds(embed.build()).queue(null, error -> { });
        }
    }
}