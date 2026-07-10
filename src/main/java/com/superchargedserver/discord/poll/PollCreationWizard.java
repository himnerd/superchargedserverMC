package com.superchargedserver.discord.poll;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.discord.DraftSession;
import com.superchargedserver.discord.DraftSessionManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fully component-driven /poll creation studio. The slash command is a bare
 * trigger; everything else flows through select menus, a content modal and a
 * live review canvas. Every custom ID is anchored to the drafting staff
 * member ({@code poll:gui:<action>:<ownerId>}) so concurrent drafts can never
 * be intercepted or corrupted by other users.
 */
public class PollCreationWizard extends ListenerAdapter {

    private static final Color BRAND = new Color(0x7B2FFF);
    private static final String PREFIX = "poll:gui:";

    private final SuperChargedServer plugin;

    public PollCreationWizard(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    private DraftSessionManager drafts() {
        return plugin.getDiscordManager().getDraftSessions();
    }

    // ── Step 1: configuration canvas ─────────────────────────────────────

    public void begin(SlashCommandInteractionEvent event) {
        String ownerId = event.getUser().getId();
        DraftSession session = drafts().create(ownerId);
        event.replyEmbeds(renderSetupEmbed(session, event.getJDA()))
                .setComponents(setupRows(session, ownerId))
                .setEphemeral(true)
                .queue();
    }

    private MessageEmbed renderSetupEmbed(DraftSession session, JDA jda) {
        TextChannel channel = channelOf(jda, session);
        String roles = session.getPollAllowedRoleIds().isEmpty()
                ? "Everyone can vote"
                : session.getPollAllowedRoleIds().stream()
                        .map(id -> "<@&" + id + ">")
                        .collect(Collectors.joining(" "));
        return new EmbedBuilder()
                .setTitle("📊 Poll Studio — Step 1 of 3")
                .setDescription("Pick the destination channel and optional voter roles, then configure the poll content.")
                .setColor(BRAND)
                .addField("Destination", channel == null ? "⚠ Not selected yet" : channel.getAsMention(), true)
                .addField("Voter Roles", roles, true)
                .setFooter("Live draft — only you can see this • Expires after 15 min idle")
                .build();
    }

    private List<ActionRow> setupRows(DraftSession session, String ownerId) {
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(EntitySelectMenu.create(PREFIX + "channel:" + ownerId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Where should this poll be sent?")
                .build()));
        rows.add(ActionRow.of(EntitySelectMenu.create(PREFIX + "roles:" + ownerId, EntitySelectMenu.SelectTarget.ROLE)
                .setMinValues(0)
                .setMaxValues(10)
                .setPlaceholder("Which roles are allowed to vote? (Leave blank for everyone)")
                .build()));
        rows.add(ActionRow.of(Button.primary(PREFIX + "content:" + ownerId, "⚙️ Configure Poll Content")
                .withDisabled(session.getPollChannelId().isBlank())));
        return rows;
    }

    // ── Component routing ────────────────────────────────────────────────

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;
        String[] parts = id.split(":");
        if (parts.length != 4) return;
        if (rejectForeignUser(parts[3], event.getUser().getId(),
                message -> event.reply(message).setEphemeral(true).queue())) return;

        DraftSession session = drafts().get(parts[3]);
        if (session == null) {
            event.editMessage("⌛ This poll draft expired — run `/poll` again.").setEmbeds().setComponents().queue();
            return;
        }

        switch (parts[2]) {
            case "channel" -> event.getMentions().getChannels().stream()
                    .filter(TextChannel.class::isInstance)
                    .findFirst()
                    .ifPresent(channel -> session.setPollChannelId(channel.getId()));
            case "roles" -> {
                session.getPollAllowedRoleIds().clear();
                event.getMentions().getRoles().forEach(role -> session.getPollAllowedRoleIds().add(role.getId()));
            }
            default -> {
                return;
            }
        }
        event.editMessageEmbeds(renderSetupEmbed(session, event.getJDA()))
                .setComponents(setupRows(session, parts[3]))
                .queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;
        String[] parts = id.split(":");
        if (parts.length != 4) return;
        if (rejectForeignUser(parts[3], event.getUser().getId(),
                message -> event.reply(message).setEphemeral(true).queue())) return;

        DraftSession session = drafts().get(parts[3]);
        if (session == null) {
            event.editMessage("⌛ This poll draft expired — run `/poll` again.").setEmbeds().setComponents().queue();
            return;
        }

        switch (parts[2]) {
            case "content" -> event.replyModal(contentModal(session, parts[3])).queue();
            case "deploy" -> deploy(event, session);
            case "schedule" -> event.replyModal(scheduleModal(parts[3])).queue();
            case "cancel" -> {
                drafts().destroy(parts[3]);
                event.editMessage("🗑 Poll draft discarded.").setEmbeds().setComponents().queue();
            }
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith(PREFIX)) return;
        String[] parts = id.split(":");
        if (parts.length != 4) return;
        if (!parts[3].equals(event.getUser().getId())) return;

        DraftSession session = drafts().get(parts[3]);
        if (session == null) {
            event.reply("⌛ This poll draft expired — run `/poll` again.").setEphemeral(true).queue();
            return;
        }

        switch (parts[2]) {
            case "content-modal" -> submitContent(event, session, parts[3]);
            case "schedule-modal" -> submitSchedule(event, session, parts[3]);
        }
    }

    private boolean rejectForeignUser(String ownerId, String userId, java.util.function.Consumer<String> reply) {
        if (ownerId.equals(userId)) return false;
        reply.accept("🔒 Only the staff member who started this poll draft can use it.");
        return true;
    }

    // ── Step 2: content modal ────────────────────────────────────────────

    private Modal contentModal(DraftSession session, String ownerId) {
        TextInput.Builder question = TextInput.create("question", "Poll Question", TextInputStyle.SHORT)
                .setRequired(true)
                .setMaxLength(100);
        if (!session.getPollQuestion().isBlank()) question.setValue(session.getPollQuestion());

        TextInput.Builder description = TextInput.create("description", "Poll Message Description", TextInputStyle.PARAGRAPH)
                .setRequired(false)
                .setMaxLength(2000)
                .setPlaceholder("Optional body text shown above the poll question — supports Discord formatting");
        if (!session.getPollDescription().isBlank()) description.setValue(session.getPollDescription());

        TextInput.Builder options = TextInput.create("options", "Poll Options & Short Descriptions", TextInputStyle.PARAGRAPH)
                .setRequired(true)
                .setMaxLength(3000)
                .setPlaceholder("Kit PvP | Fast-paced arena combat  (one option per line, description optional)");
        if (!session.getPollOptions().isEmpty()) {
            options.setValue(session.getPollOptions().stream()
                    .map(option -> option.description().isEmpty()
                            ? option.title()
                            : option.title() + " | " + option.description())
                    .collect(Collectors.joining("\n")));
        }

        TextInput.Builder duration = TextInput.create("duration", "Voting Duration (minutes, 1 - 10080)", TextInputStyle.SHORT)
                .setRequired(true)
                .setMaxLength(5)
                .setValue(String.valueOf(session.getPollDurationMinutes()));

        return Modal.create(PREFIX + "content-modal:" + ownerId, "Poll — Content")
                .addComponents(
                        ActionRow.of(question.build()),
                        ActionRow.of(description.build()),
                        ActionRow.of(options.build()),
                        ActionRow.of(duration.build()))
                .build();
    }

    private void submitContent(ModalInteractionEvent event, DraftSession session, String ownerId) {
        String question = value(event, "question");
        String description = value(event, "description");
        long duration;
        try {
            duration = Long.parseLong(value(event, "duration").trim());
        } catch (NumberFormatException ex) {
            event.reply("❌ Duration must be a whole number of minutes.").setEphemeral(true).queue();
            return;
        }
        if (duration < 1 || duration > 10080) {
            event.reply("❌ Duration must be between 1 and 10080 minutes (one week).").setEphemeral(true).queue();
            return;
        }

        List<DraftSession.PollOption> parsed = new ArrayList<>();
        for (String line : value(event, "options").split("\\R")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int split = line.indexOf('|');
            String title = (split < 0 ? line : line.substring(0, split)).trim();
            String optionDescription = split < 0 ? "" : line.substring(split + 1).trim();
            if (title.isEmpty()) continue;
            parsed.add(new DraftSession.PollOption(title, optionDescription));
        }
        if (parsed.size() < 2 || parsed.size() > PollButtonBuilder.maxOptions()) {
            event.reply("❌ Provide between 2 and " + PollButtonBuilder.maxOptions()
                    + " options — one per line, formatted as `Option Title | Short Description`.").setEphemeral(true).queue();
            return;
        }

        session.setPollQuestion(question);
        session.setPollDescription(description);
        session.setPollDurationMinutes(duration);
        session.getPollOptions().clear();
        session.getPollOptions().addAll(parsed);

        event.editMessageEmbeds(renderReviewEmbeds(session, event.getJDA()))
                .setComponents(reviewRows(session, ownerId))
                .queue();
    }

    // ── Step 3: live review canvas ───────────────────────────────────────

    private List<MessageEmbed> renderReviewEmbeds(DraftSession session, JDA jda) {
        Poll preview = pollFrom(session);
        TextChannel channel = channelOf(jda, session);
        String roles = session.getPollAllowedRoleIds().isEmpty()
                ? "Everyone can vote"
                : session.getPollAllowedRoleIds().stream()
                        .map(id -> "<@&" + id + ">")
                        .collect(Collectors.joining(" "));
        MessageEmbed settings = new EmbedBuilder()
                .setTitle("📊 Poll Studio — Step 3 of 3: Review")
                .setColor(BRAND)
                .addField("Destination", channel == null ? "⚠ Channel deleted" : channel.getAsMention(), true)
                .addField("Voter Roles", roles, true)
                .addField("Duration", session.getPollDurationMinutes() + " minute(s)", true)
                .setFooter("The embed below is exactly what will be posted • Sample buttons are disabled")
                .build();
        return List.of(settings, PollButtonBuilder.buildEmbed(preview, Map.of()));
    }

    private List<ActionRow> reviewRows(DraftSession session, String ownerId) {
        List<ActionRow> rows = new ArrayList<>();
        List<ActionRow> sample = PollButtonBuilder.buildRows(pollFrom(session), true);
        if (!sample.isEmpty()) {
            rows.add(sample.get(0));
        }
        rows.add(ActionRow.of(
                Button.success(PREFIX + "deploy:" + ownerId, "🚀 Send Poll Now"),
                Button.secondary(PREFIX + "schedule:" + ownerId, "⏳ Schedule Poll"),
                Button.primary(PREFIX + "content:" + ownerId, "✏️ Edit Content"),
                Button.danger(PREFIX + "cancel:" + ownerId, "❌ Cancel / Discard")));
        return rows;
    }

    // ── Deployment ───────────────────────────────────────────────────────

    private Poll pollFrom(DraftSession session) {
        Poll poll = new Poll();
        poll.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        poll.setChannelId(session.getPollChannelId());
        poll.setQuestion(session.getPollQuestion());
        poll.setDescription(session.getPollDescription());
        poll.setAllowedRoleIds(new ArrayList<>(session.getPollAllowedRoleIds()));
        poll.setOptions(session.getPollOptions().stream()
                .map(option -> option.description().isEmpty()
                        ? option.title()
                        : option.title() + " :: " + option.description())
                .collect(Collectors.toList()));
        poll.setEndTime(System.currentTimeMillis() + session.getPollDurationMinutes() * 60000L);
        return poll;
    }

    private void deploy(ButtonInteractionEvent event, DraftSession session) {
        if (session.getPollOptions().size() < 2) {
            event.reply("❌ Configure the poll content first.").setEphemeral(true).queue();
            return;
        }
        TextChannel channel = channelOf(event.getJDA(), session);
        if (channel == null) {
            event.reply("❌ The destination channel no longer exists — pick another one.").setEphemeral(true).queue();
            return;
        }
        Poll poll = pollFrom(session);
        drafts().destroy(event.getUser().getId());
        event.deferEdit().queue();
        plugin.getDiscordManager().sendEmbed(channel, PollButtonBuilder.buildEmbed(poll, Map.of()),
                PollButtonBuilder.buildRows(poll, false),
                message -> {
                    poll.setMessageId(message.getId());
                    plugin.getDiscordManager().getPollRepository().save(poll);
                    plugin.getDiscordManager().getPollScheduler().schedule(poll);
                    event.getHook().editOriginal("Poll deployed in " + channel.getAsMention()
                                    + " \u2014 auto-closes <t:" + poll.getEndTime() / 1000L + ":R>.")
                            .setEmbeds().setComponents().queue();
                }, error -> event.getHook().editOriginal("Failed to deploy poll: " + error)
                        .setEmbeds().setComponents().queue());
    }

    private Modal scheduleModal(String ownerId) {
        return Modal.create(PREFIX + "schedule-modal:" + ownerId, "Poll — Schedule Deployment")
                .addComponents(ActionRow.of(TextInput.create("minutes", "Deploy in how many minutes? (1 - 10080)", TextInputStyle.SHORT)
                        .setRequired(true)
                        .setPlaceholder("60")
                        .setMaxLength(5)
                        .build()))
                .build();
    }

    private void submitSchedule(ModalInteractionEvent event, DraftSession session, String ownerId) {
        long minutes;
        try {
            minutes = Long.parseLong(value(event, "minutes").trim());
        } catch (NumberFormatException ex) {
            event.reply("❌ Enter a whole number of minutes.").setEphemeral(true).queue();
            return;
        }
        if (minutes < 1 || minutes > 10080) {
            event.reply("❌ Minutes must be between 1 and 10080 (one week).").setEphemeral(true).queue();
            return;
        }
        if (session.getPollOptions().size() < 2) {
            event.reply("❌ Configure the poll content first.").setEphemeral(true).queue();
            return;
        }
        TextChannel channel = channelOf(event.getJDA(), session);
        if (channel == null) {
            event.reply("❌ The destination channel no longer exists — pick another one.").setEphemeral(true).queue();
            return;
        }

        final String channelId = channel.getId();
        final String question = session.getPollQuestion();
        final String description = session.getPollDescription();
        final long durationMinutes = session.getPollDurationMinutes();
        final List<String> allowedRoles = new ArrayList<>(session.getPollAllowedRoleIds());
        final List<String> options = session.getPollOptions().stream()
                .map(option -> option.description().isEmpty()
                        ? option.title()
                        : option.title() + " :: " + option.description())
                .collect(Collectors.toList());
        drafts().destroy(ownerId);

        event.editMessage("🕒 Poll scheduled for " + channel.getAsMention() + " in **" + minutes + " minute(s)**.")
                .setEmbeds().setComponents().queue();

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!plugin.getDiscordManager().isReady()) return;
            TextChannel resolved = plugin.getDiscordManager().getJda().getTextChannelById(channelId);
            if (resolved == null) {
                plugin.getLogger().warning("Scheduled poll skipped — channel no longer exists: " + channelId);
                return;
            }
            Poll poll = new Poll();
            poll.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            poll.setChannelId(channelId);
            poll.setQuestion(question);
            poll.setDescription(description);
            poll.setAllowedRoleIds(allowedRoles);
            poll.setOptions(options);
            poll.setEndTime(System.currentTimeMillis() + durationMinutes * 60000L);
            plugin.getDiscordManager().sendEmbed(resolved, PollButtonBuilder.buildEmbed(poll, Map.of()),
                    PollButtonBuilder.buildRows(poll, false),
                    message -> {
                        poll.setMessageId(message.getId());
                        plugin.getDiscordManager().getPollRepository().save(poll);
                        plugin.getDiscordManager().getPollScheduler().schedule(poll);
                    }, error -> plugin.getLogger().warning("Scheduled poll failed: " + error));
        }, minutes * 1200L);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private TextChannel channelOf(JDA jda, DraftSession session) {
        String id = session.getPollChannelId();
        return id == null || id.isBlank() ? null : jda.getTextChannelById(id);
    }

    private String value(ModalInteractionEvent event, String id) {
        return event.getValue(id) == null ? "" : event.getValue(id).getAsString().trim();
    }
}