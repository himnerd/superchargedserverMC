package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.IncomingWebhookClient;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.WebhookClient;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Injects the AI agent into ticket channels: greets via a webhook that
 * assumes the configured identity the second a ticket opens, answers every
 * player message after a natural 2–4 second processing simulation, and
 * attaches human-escalation / close-ticket buttons below each response.
 */
public class AISupportTicketBridge extends ListenerAdapter {

    private static final String BUTTON_HUMAN = "scai:human";
    private static final String BUTTON_CLOSE = "scai:close";

    private final SuperChargedServer plugin;
    private final Map<Long, IncomingWebhookClient> webhooks = new ConcurrentHashMap<>();

    public AISupportTicketBridge(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfigManager().aiEngine();
    }

    private AISupportManager manager() {
        return plugin.getAiSupportManager();
    }

    private boolean active() {
        AISupportManager manager = manager();
        return manager != null && manager.isEnabled()
                && cfg().getBoolean("ai-support.tickets.enabled", true);
    }

    private boolean isTicket(String channelName) {
        return channelName.startsWith(cfg().getString("ai-support.tickets.channel-prefix", "ticket-"));
    }

    @Override
    public void onChannelCreate(ChannelCreateEvent event) {
        if (!active() || event.getChannelType() != ChannelType.TEXT) return;
        TextChannel channel = event.getChannel().asTextChannel();
        if (!isTicket(channel.getName())) return;
        String greeting = cfg().getString("ai-support.tickets.greeting",
                        "Hello! I am {display-name}, your automated assistant. Describe your issue in detail "
                                + "and I will start working on a solution right away!")
                .replace("{display-name}", manager().displayName());
        CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS)
                .execute(() -> sendAsIdentity(channel, greeting));
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!active() || !event.isFromGuild() || event.getChannelType() != ChannelType.TEXT) return;
        if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
        TextChannel channel = event.getChannel().asTextChannel();
        if (!isTicket(channel.getName())) return;
        String question = event.getMessage().getContentRaw().trim();
        if (question.length() < 3) return;

        channel.sendTyping().queue(null, error -> { });
        long min = cfg().getLong("ai-support.tickets.min-delay-ms", 2000);
        long max = Math.max(min, cfg().getLong("ai-support.tickets.max-delay-ms", 4000));
        long delay = ThreadLocalRandom.current().nextLong(min, max + 1);
        manager().answer(question, answer ->
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                        .execute(() -> deliver(channel, answer)));
    }

    private void deliver(TextChannel channel, AIAnswer answer) {
        if (answer.text() != null && !answer.text().isEmpty()) {
            for (String part : split(answer.text(), 1900)) {
                sendAsIdentity(channel, part);
            }
        }
        // Regular webhooks cannot carry components, so the buttons ride on a
        // small bot follow-up message right below the identity response.
        if (answer.escalate()) {
            String pings = rolePings();
            channel.sendMessage((pings.isEmpty() ? "" : pings + " — ")
                            + "This ticket needs human attention. A staff member has been notified and will take over shortly.")
                    .setComponents(ActionRow.of(closeButton()))
                    .queue(null, error -> { });
        } else {
            channel.sendMessage("_Was this helpful?_")
                    .setComponents(ActionRow.of(humanButton(), closeButton()))
                    .queue(null, error -> { });
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        switch (event.getComponentId()) {
            case BUTTON_HUMAN -> {
                String pings = rolePings();
                event.reply((pings.isEmpty() ? "" : pings + " — ") + event.getUser().getAsMention()
                        + " has requested human support on this ticket.").queue();
            }
            case BUTTON_CLOSE -> {
                if (event.getChannelType() != ChannelType.TEXT) {
                    event.deferEdit().queue();
                    return;
                }
                event.reply("🔒 Closing this ticket in 5 seconds...").queue();
                event.getChannel().asTextChannel().delete().queueAfter(5, TimeUnit.SECONDS);
            }
        }
    }

    private Button humanButton() {
        return Button.primary(BUTTON_HUMAN, "✋ I Still Need Human Help");
    }

    private Button closeButton() {
        return Button.danger(BUTTON_CLOSE, "Close Ticket");
    }

    private String rolePings() {
        return cfg().getStringList("ai-support.tickets.human-support-role-ids").stream()
                .filter(id -> !id.isBlank())
                .map(id -> "<@&" + id + ">")
                .collect(Collectors.joining(" "));
    }

    /** Sends via the channel webhook using the configured identity, with a plain-message fallback. */
    private void sendAsIdentity(TextChannel channel, String content) {
        withWebhook(channel, client -> {
            WebhookMessageCreateAction<Message> action = client.sendMessage(content)
                    .setUsername(manager().displayName());
            String avatar = manager().avatarUrl();
            if (!avatar.isBlank()) {
                action.setAvatarUrl(avatar);
            }
            action.queue(null, error -> channel.sendMessage(content).queue(null, e -> { }));
        }, () -> channel.sendMessage(content).queue(null, e -> { }));
    }

    private void withWebhook(TextChannel channel, Consumer<IncomingWebhookClient> action, Runnable fallback) {
        IncomingWebhookClient cached = webhooks.get(channel.getIdLong());
        if (cached != null) {
            action.accept(cached);
            return;
        }
        JDA jda = channel.getJDA();
        String selfId = jda.getSelfUser().getId();
        channel.retrieveWebhooks().queue(hooks -> {
            Webhook mine = hooks.stream()
                    .filter(hook -> hook.getToken() != null)
                    .filter(hook -> hook.getOwnerAsUser() != null && hook.getOwnerAsUser().getId().equals(selfId))
                    .findFirst().orElse(null);
            if (mine != null) {
                IncomingWebhookClient client = WebhookClient.createClient(jda, mine.getUrl());
                webhooks.put(channel.getIdLong(), client);
                action.accept(client);
                return;
            }
            channel.createWebhook(manager().displayName()).queue(created -> {
                IncomingWebhookClient client = WebhookClient.createClient(jda, created.getUrl());
                webhooks.put(channel.getIdLong(), client);
                action.accept(client);
            }, error -> fallback.run());
        }, error -> fallback.run());
    }

    private List<String> split(String text, int limit) {
        List<String> parts = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > limit) {
            int cut = remaining.lastIndexOf('\n', limit);
            if (cut < limit / 2) cut = limit;
            parts.add(remaining.substring(0, cut));
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) {
            parts.add(remaining);
        }
        return parts;
    }
}