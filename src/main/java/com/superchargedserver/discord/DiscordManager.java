package com.superchargedserver.discord;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.SuperAccount;
import com.superchargedserver.ai.support.AISupportTicketBridge;
import com.superchargedserver.ai.support.IntentAnalysisListener;
import com.superchargedserver.discord.poll.PollCreationWizard;
import com.superchargedserver.discord.poll.PollRepository;
import com.superchargedserver.discord.poll.PollScheduler;
import com.superchargedserver.discord.poll.PollVoteListener;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.IncomingWebhookClient;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.WebhookClient;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.awt.Color;
import java.io.File;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public class DiscordManager {

    private final SuperChargedServer plugin;
    private final DraftSessionManager draftSessions = new DraftSessionManager();
    private PollRepository pollRepository;
    private PollScheduler pollScheduler;
    private PollCreationWizard pollWizard;
    private JDA jda;
    private IncomingWebhookClient announcer;
    private String webhookName = "SuperCharged Announcer";
    private String webhookAvatar = "";

    public DiscordManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void start() {
        loadWebhookProfile();
        FileConfiguration config = plugin.getConfigManager().discord();
        if (!config.getBoolean("discord-enabled", false)) {
            plugin.getLogger().info("Discord bridge disabled (discord-enabled: false).");
            return;
        }
        String token = config.getString("bot-settings.bot-token", "");
        if (token.isBlank() || token.startsWith("PASTE-")) {
            plugin.getLogger().warning("Discord bridge enabled but no bot token is configured.");
            return;
        }
        try {
            pollRepository = new PollRepository(plugin);
            pollRepository.init();
            pollScheduler = new PollScheduler(plugin, pollRepository);
            pollWizard = new PollCreationWizard(plugin);

            EnumSet<GatewayIntent> intents = EnumSet.noneOf(GatewayIntent.class);
            if (config.getBoolean("intents.guild-members", true)) intents.add(GatewayIntent.GUILD_MEMBERS);
            if (config.getBoolean("intents.guild-messages", true)) intents.add(GatewayIntent.GUILD_MESSAGES);
            if (config.getBoolean("intents.message-content", true)) intents.add(GatewayIntent.MESSAGE_CONTENT);

            jda = JDABuilder.createDefault(token)
                    .enableIntents(intents)
                    .setStatus(parseStatus(config.getString("bot-settings.status", "ONLINE")))
                    .setActivity(parseActivity(
                            config.getString("bot-settings.activity-type", "PLAYING"),
                            config.getString("bot-settings.activity-text", "SuperChargedServer")))
                    .addEventListeners(new DiscordCommandListener(plugin),
                            pollWizard,
                            new PollVoteListener(plugin, pollRepository, pollScheduler),
                            new AISupportTicketBridge(plugin),
                            new IntentAnalysisListener(plugin))
                    .build();
            draftSessions.startPurgeTask(plugin);
            pollScheduler.start();
            plugin.getLogger().info("Discord bridge started.");
        } catch (Exception ex) {
            jda = null;
            plugin.getLogger().warning("Failed to start Discord bridge: " + ex.getMessage());
        }
    }

    public void shutdown() {
        draftSessions.shutdown();
        if (pollScheduler != null) {
            pollScheduler.shutdown();
            pollScheduler = null;
        }
        pollRepository = null;
        pollWizard = null;
        announcer = null;
        if (jda != null) {
            jda.shutdown();
            jda = null;
        }
        plugin.getLogger().info("Discord bridge stopped.");
    }

    public boolean isReady() {
        return jda != null;
    }

    public JDA getJda() {
        return jda;
    }

    public DraftSessionManager getDraftSessions() {
        return draftSessions;
    }

    public PollRepository getPollRepository() {
        return pollRepository;
    }

    public PollScheduler getPollScheduler() {
        return pollScheduler;
    }

    public PollCreationWizard getPollWizard() {
        return pollWizard;
    }

    public String getWebhookName() {
        return webhookName;
    }

    public String getWebhookAvatar() {
        return webhookAvatar;
    }

    private File webhookProfileFile() {
        return new File(plugin.getDataFolder(), "data/webhook-profile.yml");
    }

    private void loadWebhookProfile() {
        String defaultName = plugin.getConfigManager().discord()
                .getString("announcements.webhook-name", "SuperCharged Announcer");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(webhookProfileFile());
        webhookName = yml.getString("name", defaultName);
        webhookAvatar = yml.getString("avatar-url", "");
    }

    /** Updates the announcer webhook identity; null keeps the current value. */
    public void setWebhookProfile(String name, String avatarUrl) {
        if (name != null && !name.isBlank()) webhookName = name;
        if (avatarUrl != null) webhookAvatar = avatarUrl.trim();
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("name", webhookName);
        yml.set("avatar-url", webhookAvatar);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                yml.save(webhookProfileFile());
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to save webhook profile: " + ex.getMessage());
            }
        });
    }

    /**
     * Sends a message through the announcements-channel webhook using the
     * custom identity set via /profile. The webhook client is resolved once
     * and cached for zero-overhead subsequent announcements.
     */
    public void sendAnnouncement(String content, Runnable success, Consumer<String> error) {
        withAnnouncer(client -> {
            WebhookMessageCreateAction<Message> action = client.sendMessage(content).setUsername(webhookName);
            if (webhookAvatar != null && !webhookAvatar.isBlank()) {
                action.setAvatarUrl(webhookAvatar);
            }
            action.queue(message -> success.run(), throwable -> error.accept(throwable.getMessage()));
        }, error);
    }

    private void withAnnouncer(Consumer<IncomingWebhookClient> action, Consumer<String> error) {
        if (jda == null) {
            error.accept("Discord bridge is offline");
            return;
        }
        if (announcer != null) {
            action.accept(announcer);
            return;
        }
        String channelId = plugin.getConfigManager().discord().getString("announcements.channel-id", "");
        if (channelId == null || channelId.isBlank()) {
            error.accept("announcements.channel-id is not configured");
            return;
        }
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            error.accept("announcement channel not found: " + channelId);
            return;
        }
        String selfId = jda.getSelfUser().getId();
        channel.retrieveWebhooks().queue(hooks -> {
            Webhook mine = hooks.stream()
                    .filter(hook -> hook.getToken() != null)
                    .filter(hook -> hook.getOwnerAsUser() != null && hook.getOwnerAsUser().getId().equals(selfId))
                    .findFirst().orElse(null);
            if (mine != null) {
                announcer = WebhookClient.createClient(jda, mine.getUrl());
                action.accept(announcer);
                return;
            }
            channel.createWebhook(webhookName).queue(created -> {
                announcer = WebhookClient.createClient(jda, created.getUrl());
                action.accept(announcer);
            }, throwable -> error.accept("failed to create webhook: " + throwable.getMessage()));
        }, throwable -> error.accept("failed to fetch webhooks: " + throwable.getMessage()));
    }

    /**
     * Pushes a styled status embed to the configured announcement channel
     * and mirrors the network state onto the bot's live presence.
     */
    public void announceMaintenance(boolean active) {
        if (jda == null) return;
        FileConfiguration config = plugin.getConfigManager().discord();
        updatePresence(active, config);

        String channelId = config.getString("maintenance-announcements.channel-id", "");
        if (channelId == null || channelId.isBlank()) return;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning("Maintenance announcement channel not found: " + channelId);
            return;
        }

        String title = active
                ? config.getString("maintenance-announcements.title-active", "🔧 Maintenance Started")
                : config.getString("maintenance-announcements.title-lifted", "✅ Server Back Online");
        String description = active
                ? config.getString("maintenance-announcements.description-active", "The network is now undergoing maintenance.")
                : config.getString("maintenance-announcements.description-lifted", "Maintenance is complete — the network is back online.");

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(title)
                .setDescription(description)
                .setColor(active ? new Color(0xE74C3C) : new Color(0x2ECC71))
                .addField("Network Status", active ? "🔴 Under Maintenance" : "🟢 Online", true)
                .setFooter("SuperChargedServer")
                .setTimestamp(Instant.now());
        channel.sendMessageEmbeds(embed.build()).queue(
                null,
                error -> plugin.getLogger().warning("Failed to send maintenance embed: " + error.getMessage()));
    }

    private void updatePresence(boolean active, FileConfiguration config) {
        if (active) {
            String presenceText = config.getString("maintenance-announcements.presence-text", "🔧 Maintenance in progress");
            jda.getPresence().setPresence(OnlineStatus.DO_NOT_DISTURB, Activity.watching(presenceText));
        } else {
            jda.getPresence().setPresence(
                    parseStatus(config.getString("bot-settings.status", "ONLINE")),
                    parseActivity(
                            config.getString("bot-settings.activity-type", "PLAYING"),
                            config.getString("bot-settings.activity-text", "SuperChargedServer")));
        }
    }

    private OnlineStatus parseStatus(String raw) {
        try {
            return OnlineStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return OnlineStatus.ONLINE;
        }
    }

    private Activity parseActivity(String type, String text) {
        switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "LISTENING":
                return Activity.listening(text);
            case "WATCHING":
                return Activity.watching(text);
            case "STREAMING":
            case "PLAYING":
            default:
                return Activity.playing(text);
        }
    }

    public void logRegistration(SuperAccount account) {
    }

    public void logLink(SuperAccount account, UUID playerUuid) {
    }

    public void applyDiscordName(SuperAccount account) {
    }

    public void logSecurity(String title, String message) {
    }

    public void dmCode(String discordId, String message) {
    }
}