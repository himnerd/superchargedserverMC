package com.superchargedserver.discord;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.LinkCodeManager;
import com.superchargedserver.account.SuperAccount;
import com.superchargedserver.discord.poll.Poll;
import com.superchargedserver.discord.poll.PollButtonBuilder;
import com.superchargedserver.util.ColorUtil;import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class DiscordCommandListener extends ListenerAdapter {

    private static final Color BRAND = new Color(0x7B2FFF);
    private static final int MAX_FIELDS = 25;

    private final SuperChargedServer plugin;
    private final FieldMutationManager fieldMutations = new FieldMutationManager();
    private final SessionStateSafetyFilter safetyFilter = new SessionStateSafetyFilter();

    public DiscordCommandListener(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onReady(ReadyEvent event) {
        FileConfiguration config = plugin.getConfigManager().discord();
        String guildId = config.getString("bot-settings.guild-id", "");
        Guild guild = guildId == null || guildId.isBlank() ? null : event.getJDA().getGuildById(guildId);
        if (guild == null) {
            plugin.getLogger().warning("Discord guild-id is not configured or the bot is not in that guild — slash commands not registered.");
            return;
        }
        guild.updateCommands().addCommands(
                Commands.slash("link", "Link your Discord account using your in-game code")
                        .addOptions(new OptionData(OptionType.STRING, "code", "The code shown by /link in-game", true)),
                Commands.slash("embed", "Open the live embed builder dashboard")
                        .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Pre-select the destination channel", false)
                                .setChannelTypes(ChannelType.TEXT)),
                Commands.slash("announce", "Compose an announcement with a live Discord + in-game preview"),
                Commands.slash("poll", "Open the interactive poll creation studio"),
                Commands.slash("profile", "Customize the announcement webhook identity")
                        .addOptions(
                                new OptionData(OptionType.STRING, "name", "New webhook display name", false),
                                new OptionData(OptionType.STRING, "avatar-url", "New webhook avatar image URL", false)),
                Commands.slash("status", "Live server performance and online players"),
                Commands.slash("execute", "Run a console command on the server")
                        .addOptions(new OptionData(OptionType.STRING, "command", "Command to execute (without /)", true)),
                Commands.slash("rewards", "Rewards for linked SuperAccounts"),
                Commands.slash("account-lookup", "Look up a SuperAccount by player name or Discord user")
                        .addOptions(
                                new OptionData(OptionType.STRING, "player", "Minecraft player name", false),
                                new OptionData(OptionType.USER, "discord", "Linked Discord user", false)),
                Commands.slash("ai-status", "Show the AI anomaly engine status"),
                Commands.slash("console", "Run a console command on the server")
                        .addOptions(new OptionData(OptionType.STRING, "command", "Command to execute (without /)", true))
        ).queue(
                cmds -> plugin.getLogger().info("Registered " + cmds.size() + " Discord slash commands on guild " + guild.getName() + "."),
                error -> plugin.getLogger().warning("Failed to register Discord slash commands: " + error.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        // Open to every guild member — no role gate.
        switch (event.getName()) {
            case "link" -> {
                handleLink(event);
                return;
            }
            case "rewards" -> {
                handleRewards(event);
                return;
            }
        }

        String gate = switch (event.getName()) {
            case "account-lookup" -> "account-lookup";
            case "ai-status" -> "ai-commands";
            case "console", "execute" -> "console";
            case "embed" -> "embed";
            case "announce" -> "announce";
            case "poll" -> "poll";
            case "profile" -> "profile";
            case "status" -> "status";
            default -> null;
        };
        if (gate == null) return;

        if (!isAuthorized(event.getMember(), gate)) {
            event.reply("❌ You don't have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        switch (event.getName()) {
            case "account-lookup" -> handleAccountLookup(event);
            case "ai-status" -> handleAiStatus(event);
            case "console", "execute" -> handleConsole(event);
            case "embed" -> handleEmbed(event);
            case "announce" -> handleAnnounce(event);
            case "poll" -> handlePoll(event);
            case "profile" -> handleProfile(event);
            case "status" -> handleStatus(event);
        }
    }

    private boolean isAuthorized(Member member, String gate) {
        if (member == null) return false;
        List<String> roleIds = plugin.getConfigManager().discord().getStringList("command-permissions." + gate);
        if (roleIds.isEmpty()) {
            return member.hasPermission(Permission.ADMINISTRATOR);
        }
        for (Role role : member.getRoles()) {
            if (roleIds.contains(role.getId())) return true;
        }
        return member.hasPermission(Permission.ADMINISTRATOR);
    }

    private DraftSessionManager drafts() {
        return plugin.getDiscordManager().getDraftSessions();
    }

    // ── /link ────────────────────────────────────────────────────────────

    private void handleLink(SlashCommandInteractionEvent event) {
        String discordId = event.getUser().getId();
        if (plugin.getAccountManager().getByDiscord(discordId) != null) {
            event.reply("✅ Your Discord account is already linked.").setEphemeral(true).queue();
            return;
        }
        if (!plugin.getAccountManager().canLinkDiscord(discordId)) {
            event.reply("⏳ This Discord account recently unlinked — linking is on cooldown.").setEphemeral(true).queue();
            return;
        }
        String code = event.getOption("code").getAsString();
        LinkCodeManager.PendingLink pending = plugin.getLinkCodeManager().redeem(code, "discord:" + discordId);
        if (pending == null || pending.accountId() == null) {
            event.reply("❌ Invalid or expired code. Run `/link` in-game to get a fresh one.").setEphemeral(true).queue();
            return;
        }
        SuperAccount account = plugin.getAccountManager().getAccount(pending.accountId());
        if (account == null) {
            event.reply("❌ That SuperAccount is no longer available. Run `/link` in-game again.").setEphemeral(true).queue();
            return;
        }
        if (account.isLinkedToDiscord()) {
            event.reply("❌ That SuperAccount is already linked to another Discord user.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getAccountManager().linkDiscord(account, discordId);
            for (UUID uuid : account.allUuids()) {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    plugin.msg(online, "<green>Discord linked to <white>" + event.getUser().getName()
                            + "<green> — linked rewards are now active!");
                }
            }
            event.getHook().editOriginal("✅ Linked to SuperAccount **" + account.getPrimaryName()
                    + "** — linked rewards are now active!").queue();
        });
    }

    // ── /embed — live builder dashboard ──────────────────────────────────

    private void handleEmbed(SlashCommandInteractionEvent event) {
        DraftSession session = drafts().create(event.getUser().getId());

        if (event.getOption("channel") != null) {
            session.setChannelId(event.getOption("channel").getAsChannel().getId());
        } else {
            String fallback = plugin.getConfigManager().discord().getString("announcements.channel-id", "");
            if (fallback != null && !fallback.isBlank() && event.getJDA().getTextChannelById(fallback) != null) {
                session.setChannelId(fallback);
            }
        }

        event.replyEmbeds(renderEmbedPreview(session))
                .setComponents(dashboardRows(session, event.getJDA()))
                .setEphemeral(true)
                .queue();
    }

    private MessageEmbed renderEmbedPreview(DraftSession session) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(session.getTitle().isBlank() ? "📝 Untitled Embed" : session.getTitle())
                .setDescription(session.getDescription().isBlank()
                        ? "*Use **Edit Base Info** to set title, description, color and images.*"
                        : session.getDescription())
                .setColor(parseColor(session.getColorHex()))
                .setFooter("Live Preview — only you can see this • Draft expires after 15 min idle");
        if (session.getThumbnail().startsWith("http")) embed.setThumbnail(session.getThumbnail());
        if (session.getImage().startsWith("http")) embed.setImage(session.getImage());
        for (DraftSession.Field field : session.getFields()) {
            embed.addField(field.title(), field.value(), field.inline());
        }
        return embed.build();
    }

    private List<ActionRow> dashboardRows(DraftSession session, JDA jda) {
        TextChannel channel = resolveChannel(jda, session);

        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(EntitySelectMenu.create("sc-eb:channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(channel == null ? "Select destination channel" : "Destination: #" + channel.getName())
                .build()));
        rows.add(ActionRow.of(
                Button.primary("sc-eb:base", "Edit Base Info"),
                Button.secondary("sc-eb:addfield", "Add Field")
                        .withDisabled(session.getFields().size() >= MAX_FIELDS),
                Button.secondary("sc-eb:editfields", "\u270f\ufe0f Edit Fields")
                        .withDisabled(session.getFields().isEmpty())));

        if (!session.getFields().isEmpty()) {
            List<SelectOption> options = new ArrayList<>();
            List<DraftSession.Field> fields = session.getFields();
            for (int i = 0; i < fields.size(); i++) {
                String label = (i + 1) + ". " + fields.get(i).title();
                options.add(SelectOption.of(label.length() > 100 ? label.substring(0, 97) + "..." : label,
                        String.valueOf(i)));
            }
            rows.add(ActionRow.of(StringSelectMenu.create("sc-eb:removefield")
                    .setPlaceholder("🗑 Remove a field…")
                    .addOptions(options)
                    .build()));
        }

        rows.add(ActionRow.of(
                Button.success("sc-eb:send", "Send Now").withDisabled(channel == null),
                Button.secondary("sc-eb:schedule", "Schedule").withDisabled(channel == null),
                Button.danger("sc-eb:cancel", "Cancel")));
        return rows;
    }

    private TextChannel resolveChannel(JDA jda, DraftSession session) {
        String id = session.getChannelId();
        return id == null || id.isBlank() ? null : jda.getTextChannelById(id);
    }

    private void refreshDashboard(ButtonInteractionEvent event, DraftSession session) {
        event.editMessageEmbeds(renderEmbedPreview(session))
                .setComponents(dashboardRows(session, event.getJDA()))
                .queue();
    }

    // ── /announce — preview loop ─────────────────────────────────────────

    private void handleAnnounce(SlashCommandInteractionEvent event) {
        DraftSession session = drafts().getOrCreate(event.getUser().getId());
        event.replyModal(announceModal(session)).queue();
    }

    private Modal announceModal(DraftSession session) {
        TextInput.Builder message = TextInput.create("message", "Announcement Message", TextInputStyle.PARAGRAPH)
                .setRequired(true)
                .setMaxLength(1800)
                .setPlaceholder("Supports MiniMessage / hex colors for the in-game broadcast");
        if (!session.getAnnouncementText().isBlank()) {
            message.setValue(session.getAnnouncementText());
        }
        return Modal.create("sc-an-m:text", "Quick Announcement")
                .addComponents(ActionRow.of(message.build()))
                .build();
    }

    private MessageEmbed renderAnnouncePreview(DraftSession session) {
        String format = plugin.getConfigManager().discord().getString("announcements.in-game-format",
                "<gradient:#00E5FF:#7B2FFF><bold>ANNOUNCEMENT</bold></gradient> <dark_gray>» <white>{message}");
        String minecraftPreview = ColorUtil.plain(format.replace("{message}", session.getAnnouncementText()));
        if (minecraftPreview.length() > 1000) minecraftPreview = minecraftPreview.substring(0, 997) + "...";

        String avatar = plugin.getDiscordManager().getWebhookAvatar();
        EmbedBuilder embed = new EmbedBuilder()
                .setAuthor(plugin.getDiscordManager().getWebhookName(), null,
                        avatar == null || avatar.isBlank() ? null : avatar)
                .setTitle("📣 Announcement Preview")
                .setDescription(session.getAnnouncementText())
                .setColor(BRAND)
                .addField("Minecraft Chat Preview", "```" + minecraftPreview + "```", false)
                .setFooter("Live Preview — only you can see this • Draft expires after 15 min idle");
        return embed.build();
    }

    private List<ActionRow> announceRows() {
        return List.of(ActionRow.of(
                Button.success("sc-an:confirm", "Confirm & Broadcast"),
                Button.secondary("sc-an:edit", "Edit Text"),
                Button.danger("sc-an:cancel", "Cancel")));
    }

    // ── Component interactions ───────────────────────────────────────────

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        if (!event.getComponentId().equals("sc-eb:channel")) return;
        DraftSession session = drafts().get(event.getUser().getId());
        if (session == null) {
            expireMessage(event.editMessage("⌛ This draft expired — run `/embed` again."));
            return;
        }
        List<TextChannel> selected = event.getMentions().getChannels().stream()
                .filter(TextChannel.class::isInstance)
                .map(TextChannel.class::cast)
                .collect(Collectors.toList());
        if (!selected.isEmpty()) {
            session.setChannelId(selected.get(0).getId());
        }
        event.editMessageEmbeds(renderEmbedPreview(session))
                .setComponents(dashboardRows(session, event.getJDA()))
                .queue();
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getComponentId().equals("sc-eb:editfields-pick")) {
            DraftSession picked = drafts().get(event.getUser().getId());
            if (picked == null) {
                expireMessage(event.editMessage("\u231b This draft expired \u2014 run `/embed` again."));
                return;
            }
            fieldMutations.handleFieldPick(event, picked);
            return;
        }
        if (!event.getComponentId().equals("sc-eb:removefield")) return;
        DraftSession session = drafts().get(event.getUser().getId());
        if (session == null) {
            expireMessage(event.editMessage("⌛ This draft expired — run `/embed` again."));
            return;
        }
        try {
            int index = Integer.parseInt(event.getValues().get(0));
            if (index >= 0 && index < session.getFields().size()) {
                session.getFields().remove(index);
            }
        } catch (NumberFormatException ignored) {
        }
        event.editMessageEmbeds(renderEmbedPreview(session))
                .setComponents(dashboardRows(session, event.getJDA()))
                .queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("sc-eb:") && !id.startsWith("sc-an:")) return;

        String userId = event.getUser().getId();
        DraftSession session = drafts().get(userId);
        if (session == null) {
            expireMessage(event.editMessage("⌛ This draft expired — run the command again."));
            return;
        }

        if (id.startsWith("sc-eb:mut:")) {
            fieldMutations.handleMutationButton(event, session);
            return;
        }

        switch (id) {
            case "sc-eb:base" -> event.replyModal(baseInfoModal(session)).queue();
            case "sc-eb:addfield" -> {
                if (session.getFields().size() >= MAX_FIELDS) {
                    event.reply("❌ Embeds support at most " + MAX_FIELDS + " fields.").setEphemeral(true).queue();
                    return;
                }
                event.replyModal(fieldModal()).queue();
            }
            case "sc-eb:editfields" -> {
                if (session.getFields().isEmpty()) {
                    event.reply("\u274c There are no fields to edit yet.").setEphemeral(true).queue();
                    return;
                }
                event.reply("\u270f\ufe0f Pick a field to edit:")
                        .setComponents(ActionRow.of(fieldMutations.buildFieldSelect(session)))
                        .setEphemeral(true)
                        .queue();
            }
            case "sc-eb:chunks-apply" -> applyPendingChunks(event, session);
            case "sc-eb:chunks-discard" -> {
                session.getPendingChunks().clear();
                expireMessage(event.editMessage("\ud83d\uddd1 Extra document text discarded."));
            }
            case "sc-eb:send" -> sendEmbedNow(event, session);
            case "sc-eb:schedule" -> {
                TextChannel channel = resolveChannel(event.getJDA(), session);
                if (channel == null) {
                    event.reply("❌ Pick a destination channel first.").setEphemeral(true).queue();
                    return;
                }
                if (session.getTitle().isBlank() && session.getDescription().isBlank()) {
                    event.reply("❌ Set a title or description before scheduling.").setEphemeral(true).queue();
                    return;
                }
                event.replyModal(scheduleModal()).queue();
            }
            case "sc-eb:cancel" -> {
                drafts().destroy(userId);
                expireMessage(event.editMessage("🗑 Embed draft discarded."));
            }
            case "sc-an:edit" -> event.replyModal(announceModal(session)).queue();
            case "sc-an:confirm" -> confirmAnnouncement(event, session);
            case "sc-an:cancel" -> {
                drafts().destroy(userId);
                expireMessage(event.editMessage("🗑 Announcement draft discarded."));
            }
        }
    }

    private void sendEmbedNow(ButtonInteractionEvent event, DraftSession session) {
        TextChannel channel = resolveChannel(event.getJDA(), session);
        if (channel == null) {
            event.reply("❌ Pick a destination channel first.").setEphemeral(true).queue();
            return;
        }
        if (session.getTitle().isBlank() && session.getDescription().isBlank()) {
            event.reply("❌ Set a title or description before sending.").setEphemeral(true).queue();
            return;
        }
        MessageEmbed built = buildFinalEmbed(session, event.getUser().getName());
        drafts().destroy(event.getUser().getId());
        event.deferEdit().queue();
        channel.sendMessageEmbeds(built).queue(
                message -> event.getHook().editOriginal("📣 Embed published in " + channel.getAsMention() + ".")
                        .setEmbeds().setComponents().queue(),
                error -> event.getHook().editOriginal("⚠ Failed to send embed: " + error.getMessage())
                        .setEmbeds().setComponents().queue());
    }

    private void confirmAnnouncement(ButtonInteractionEvent event, DraftSession session) {
        String text = session.getAnnouncementText();
        if (text.isBlank()) {
            event.reply("❌ The announcement text is empty — use **Edit Text** first.").setEphemeral(true).queue();
            return;
        }
        drafts().destroy(event.getUser().getId());
        event.deferEdit().queue();

        plugin.getDiscordManager().sendAnnouncement(text,
                () -> event.getHook().editOriginal("📣 Announcement published to Discord and in-game.")
                        .setEmbeds().setComponents().queue(),
                error -> event.getHook().editOriginal("⚠ Discord webhook failed (" + error
                        + ") — the in-game broadcast was still sent.").setEmbeds().setComponents().queue());

        String format = plugin.getConfigManager().discord().getString("announcements.in-game-format",
                "<gradient:#00E5FF:#7B2FFF><bold>ANNOUNCEMENT</bold></gradient> <dark_gray>» <white>{message}");
        String broadcast = format.replace("{message}", text);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(ColorUtil.colorize(broadcast)));
    }

    // ── Modals ───────────────────────────────────────────────────────────

    private Modal baseInfoModal(DraftSession session) {
        return Modal.create("sc-eb-m:base", "Embed — Base Info")
                .addComponents(
                        ActionRow.of(prefilled(TextInput.create("title", "Title", TextInputStyle.SHORT)
                                .setRequired(false).setMaxLength(256), session.getTitle())),
                        ActionRow.of(prefilled(TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
                                .setRequired(false).setMaxLength(4000)
                                .setPlaceholder("Type text \u2014 or paste a .txt / .pdf link (Drive, Dropbox, iCloud supported)"),
                                session.getDescription())),
                        ActionRow.of(prefilled(TextInput.create("color", "Color (hex)", TextInputStyle.SHORT)
                                .setRequired(false).setPlaceholder("#7B2FFF").setMaxLength(7), session.getColorHex())),
                        ActionRow.of(prefilled(TextInput.create("thumbnail", "Thumbnail URL", TextInputStyle.SHORT)
                                .setRequired(false), session.getThumbnail())),
                        ActionRow.of(prefilled(TextInput.create("image", "Image URL", TextInputStyle.SHORT)
                                .setRequired(false), session.getImage())))
                .build();
    }

    private Modal fieldModal() {
        return Modal.create("sc-eb-m:field", "Embed — Add Field")
                .addComponents(
                        ActionRow.of(TextInput.create("title", "Field Title", TextInputStyle.SHORT)
                                .setRequired(true).setMaxLength(256).build()),
                        ActionRow.of(TextInput.create("value", "Field Value / Text", TextInputStyle.PARAGRAPH)
                                .setRequired(true).setMaxLength(1024)
                                .setPlaceholder("Type text \u2014 or paste a .txt / .pdf link (Drive, Dropbox, iCloud supported)")
                                .build()),
                        ActionRow.of(TextInput.create("inline", "Inline (true / false)", TextInputStyle.SHORT)
                                .setRequired(false).setPlaceholder("false").setMaxLength(5).build()))
                .build();
    }

    private Modal scheduleModal() {
        return Modal.create("sc-eb-m:schedule", "Embed — Schedule")
                .addComponents(ActionRow.of(TextInput.create("minutes", "Minutes from now (1 - 10080)", TextInputStyle.SHORT)
                        .setRequired(true).setPlaceholder("60").setMaxLength(5).build()))
                .build();
    }

    private TextInput prefilled(TextInput.Builder builder, String current) {
        if (current != null && !current.isBlank()) {
            builder.setValue(current);
        }
        return builder.build();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith("sc-eb-m:") && !id.startsWith("sc-an-m:")) return;

        String userId = event.getUser().getId();
        DraftSession session = drafts().get(userId);
        if (session == null) {
            event.reply("⌛ This draft expired — run the command again.").setEphemeral(true).queue();
            return;
        }

        if (id.startsWith("sc-eb-m:mut-")) {
            fieldMutations.handleMutationModal(event, session);
            return;
        }

        switch (id) {
            case "sc-eb-m:base" -> {
                session.setTitle(orEmpty(modalValue(event, "title")));
                String description = orEmpty(modalValue(event, "description"));
                session.setColorHex(orEmpty(modalValue(event, "color")));
                session.setThumbnail(orEmpty(modalValue(event, "thumbnail")));
                session.setImage(orEmpty(modalValue(event, "image")));
                if (ExternalFileIngestor.isIngestableLink(description)) {
                    session.setDescription("\u23f3 Importing document\u2026");
                    beginIngestion(event, session, description.trim(), -1);
                    return;
                }
                session.setDescription(description);
                event.editMessageEmbeds(renderEmbedPreview(session))
                        .setComponents(dashboardRows(session, event.getJDA()))
                        .queue();
            }
            case "sc-eb-m:field" -> {
                if (session.getFields().size() < MAX_FIELDS) {
                    boolean inline = "true".equalsIgnoreCase(orEmpty(modalValue(event, "inline")).trim());
                    String value = orEmpty(modalValue(event, "value"));
                    if (ExternalFileIngestor.isIngestableLink(value)) {
                        int target = session.getFields().size();
                        session.getFields().add(new DraftSession.Field(
                                modalValue(event, "title"), "\u23f3 Importing document\u2026", inline));
                        beginIngestion(event, session, value.trim(), target);
                        return;
                    }
                    session.getFields().add(new DraftSession.Field(
                            modalValue(event, "title"), value, inline));
                }
                event.editMessageEmbeds(renderEmbedPreview(session))
                        .setComponents(dashboardRows(session, event.getJDA()))
                        .queue();
            }
            case "sc-eb-m:schedule" -> scheduleEmbed(event, session);
            case "sc-an-m:text" -> {
                session.setAnnouncementText(orEmpty(modalValue(event, "message")));
                if (event.getMessage() != null) {
                    event.editMessageEmbeds(renderAnnouncePreview(session))
                            .setComponents(announceRows())
                            .queue();
                } else {
                    event.replyEmbeds(renderAnnouncePreview(session))
                            .setComponents(announceRows())
                            .setEphemeral(true)
                            .queue();
                }
            }
        }
    }

    private void applyPendingChunks(ButtonInteractionEvent event, DraftSession session) {
        List<String> pending = new ArrayList<>(session.getPendingChunks());
        session.getPendingChunks().clear();
        if (pending.isEmpty()) {
            expireMessage(event.editMessage("\u231b No pending document segments remain."));
            return;
        }
        int added = 0;
        for (String chunk : pending) {
            if (session.getFields().size() >= MAX_FIELDS) break;
            session.getFields().add(new DraftSession.Field(
                    "\ud83d\udcc4 Imported Content " + (session.getFields().size() + 1), chunk, false));
            added++;
        }
        String note = added < pending.size()
                ? " \u2014 " + (pending.size() - added) + " segment(s) dropped (embeds cap at " + MAX_FIELDS + " fields)."
                : ".";
        expireMessage(event.editMessage("\u2705 Added " + added + " field(s) from the imported document" + note
                + " The dashboard preview refreshes on your next action there."));
    }

    private void beginIngestion(ModalInteractionEvent event, DraftSession session, String url, int fieldIndex) {
        String userId = event.getUser().getId();
        String channelId = event.getChannel().getId();
        String token = safetyFilter.issue(userId, channelId);
        event.deferEdit().queue();
        ExternalFileIngestor.ingestAsync(url, result -> {
            if (!safetyFilter.consume(token, userId, channelId)) return;
            if (drafts().get(userId) != session) return;
            if (!result.success()) {
                if (fieldIndex < 0) {
                    session.setDescription("");
                } else if (fieldIndex < session.getFields().size()) {
                    session.getFields().remove(fieldIndex);
                }
                event.getHook().editOriginalEmbeds(renderEmbedPreview(session))
                        .setComponents(dashboardRows(session, event.getJDA()))
                        .queue();
                event.getHook().sendMessage("\u26a0 Document import failed: " + result.error())
                        .setEphemeral(true).queue();
                return;
            }
            List<String> chunks = ExternalFileIngestor.chunk(result.text(), fieldIndex < 0 ? 4000 : 1024, 1024);
            String first = chunks.isEmpty() ? "(empty document)" : chunks.get(0);
            if (fieldIndex < 0) {
                session.setDescription(first);
            } else if (fieldIndex < session.getFields().size()) {
                DraftSession.Field old = session.getFields().get(fieldIndex);
                session.getFields().set(fieldIndex, new DraftSession.Field(old.title(), first, old.inline()));
            }
            session.getPendingChunks().clear();
            if (chunks.size() > 1) {
                session.getPendingChunks().addAll(chunks.subList(1, chunks.size()));
            }
            event.getHook().editOriginalEmbeds(renderEmbedPreview(session))
                    .setComponents(dashboardRows(session, event.getJDA()))
                    .queue();
            if (!session.getPendingChunks().isEmpty()) {
                event.getHook().sendMessage("\ud83d\udcc4 The document exceeded Discord's limits \u2014 **"
                                + session.getPendingChunks().size()
                                + "** extra segment(s) are waiting. Assign them to new embed fields?")
                        .addActionRow(
                                Button.success("sc-eb:chunks-apply", "\u2795 Add as Fields"),
                                Button.danger("sc-eb:chunks-discard", "Discard Extra Text"))
                        .setEphemeral(true)
                        .queue();
            }
        });
    }

    private void scheduleEmbed(ModalInteractionEvent event, DraftSession session) {
        long minutes;
        try {
            minutes = Long.parseLong(modalValue(event, "minutes").trim());
        } catch (NumberFormatException ex) {
            event.reply("❌ Enter a whole number of minutes.").setEphemeral(true).queue();
            return;
        }
        if (minutes < 1 || minutes > 10080) {
            event.reply("❌ Minutes must be between 1 and 10080 (one week).").setEphemeral(true).queue();
            return;
        }
        TextChannel channel = resolveChannel(event.getJDA(), session);
        if (channel == null) {
            event.reply("❌ The destination channel no longer exists.").setEphemeral(true).queue();
            return;
        }
        MessageEmbed built = buildFinalEmbed(session, event.getUser().getName());
        String channelId = channel.getId();
        drafts().destroy(event.getUser().getId());

        if (event.getMessage() != null) {
            event.editMessage("🕒 Embed scheduled for " + channel.getAsMention() + " in **" + minutes + " minute(s)**.")
                    .setEmbeds().setComponents().queue();
        } else {
            event.reply("🕒 Embed scheduled for " + channel.getAsMention() + " in **" + minutes + " minute(s)**.")
                    .setEphemeral(true).queue();
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            TextChannel resolved = plugin.getDiscordManager().isReady()
                    ? plugin.getDiscordManager().getJda().getTextChannelById(channelId) : null;
            if (resolved != null) {
                resolved.sendMessageEmbeds(built).queue(null,
                        error -> plugin.getLogger().warning("Scheduled embed failed: " + error.getMessage()));
            }
        }, minutes * 1200L);
    }

    private MessageEmbed buildFinalEmbed(DraftSession session, String authorName) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(parseColor(session.getColorHex()))
                .setFooter(authorName)
                .setTimestamp(Instant.now());
        if (!session.getTitle().isBlank()) embed.setTitle(session.getTitle());
        if (!session.getDescription().isBlank()) embed.setDescription(session.getDescription());
        if (session.getThumbnail().startsWith("http")) embed.setThumbnail(session.getThumbnail());
        if (session.getImage().startsWith("http")) embed.setImage(session.getImage());
        for (DraftSession.Field field : session.getFields()) {
            embed.addField(field.title(), field.value(), field.inline());
        }
        return embed.build();
    }

    private void expireMessage(net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction action) {
        action.setEmbeds().setComponents().queue();
    }

    private String modalValue(ModalInteractionEvent event, String id) {
        return event.getValue(id) == null ? null : event.getValue(id).getAsString().trim();
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private Color parseColor(String hex) {
        if (hex == null || hex.isBlank()) return BRAND;
        try {
            return Color.decode(hex.startsWith("#") ? hex : "#" + hex);
        } catch (NumberFormatException ex) {
            return BRAND;
        }
    }

    // ── /poll — interactive creation studio ─────────────────────────────

    private void handlePoll(SlashCommandInteractionEvent event) {
        plugin.getDiscordManager().getPollWizard().begin(event);
    }

    // ── /profile ─────────────────────────────────────────────────────────

    private void handleProfile(SlashCommandInteractionEvent event) {
        String name = event.getOption("name") == null ? null : event.getOption("name").getAsString();
        String avatar = event.getOption("avatar-url") == null ? null : event.getOption("avatar-url").getAsString();
        if (name == null && avatar == null) {
            event.reply("Provide `name` and/or `avatar-url`.").setEphemeral(true).queue();
            return;
        }
        if (avatar != null && !avatar.isBlank() && !avatar.startsWith("http")) {
            event.reply("❌ `avatar-url` must be a valid http(s) image URL.").setEphemeral(true).queue();
            return;
        }
        plugin.getDiscordManager().setWebhookProfile(name, avatar);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Announcer Identity Updated")
                .setColor(BRAND)
                .addField("Display Name", plugin.getDiscordManager().getWebhookName(), true)
                .addField("Avatar", plugin.getDiscordManager().getWebhookAvatar().isBlank()
                        ? "Default" : plugin.getDiscordManager().getWebhookAvatar(), true)
                .setFooter("SuperChargedServer")
                .setTimestamp(Instant.now());
        if (!plugin.getDiscordManager().getWebhookAvatar().isBlank()) {
            embed.setThumbnail(plugin.getDiscordManager().getWebhookAvatar());
        }
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    // ── /status ──────────────────────────────────────────────────────────

    private void handleStatus(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        Bukkit.getScheduler().runTask(plugin, () -> {
            double tps = Math.min(20.0, Bukkit.getTPS()[0]);
            Runtime runtime = Runtime.getRuntime();
            long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
            long maxMb = runtime.maxMemory() / 1048576L;

            List<String> names = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
            String playerList = names.isEmpty() ? "Nobody online" : String.join(", ", names);
            if (playerList.length() > 1000) playerList = playerList.substring(0, 997) + "...";

            Color color = tps >= 18.0 ? new Color(0x2ECC71) : tps >= 15.0 ? new Color(0xE67E22) : new Color(0xE74C3C);
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("⚡ Server Status")
                    .setColor(color)
                    .addField("TPS", String.format(Locale.ROOT, "%.2f / 20.00", tps), true)
                    .addField("RAM", usedMb + " / " + maxMb + " MB " + bar(usedMb, maxMb), true)
                    .addField("Online", names.size() + " / " + Bukkit.getMaxPlayers(), true)
                    .addField("Players", playerList, false)
                    .setFooter("SuperChargedServer")
                    .setTimestamp(Instant.now());
            event.getHook().sendMessageEmbeds(embed.build()).queue();
        });
    }

    private String bar(long value, long max) {
        int filled = max <= 0 ? 0 : (int) Math.round(10.0 * value / max);
        return "▰".repeat(Math.min(10, filled)) + "▱".repeat(Math.max(0, 10 - filled));
    }

    // ── /rewards ─────────────────────────────────────────────────────────

    private void handleRewards(SlashCommandInteractionEvent event) {
        SuperAccount account = plugin.getAccountManager().getByDiscord(event.getUser().getId());
        long perMinute = plugin.getConfigManager().superAccounts()
                .getLong("rewards.linked-bonus-points-per-minute", 1);

        String perks = plugin.getConfigManager().discord().getStringList("rewards-display").stream()
                .map(line -> line.replace("{points}", String.valueOf(perMinute)))
                .collect(Collectors.joining("\n"));
        if (perks.isBlank()) {
            perks = "🎁 +" + perMinute + " Play Points every online minute";
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🎁 Linked Account Rewards")
                .setColor(account != null ? new Color(0x2ECC71) : BRAND)
                .setDescription(perks)
                .addField("Link Status", account != null
                        ? "🟢 Linked as **" + account.getPrimaryName() + "**"
                        : "⚪ Not linked — run `/link` in-game to get your code", false)
                .setFooter("SuperChargedServer")
                .setTimestamp(Instant.now());
        if (account != null) {
            embed.addField("Play Points", String.valueOf(account.getPlayPoints()), true);
        }
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    // ── /account-lookup ──────────────────────────────────────────────────

    private void handleAccountLookup(SlashCommandInteractionEvent event) {
        SuperAccount account = null;
        if (event.getOption("player") != null) {
            account = plugin.getAccountManager().getByName(event.getOption("player").getAsString());
        } else if (event.getOption("discord") != null) {
            account = plugin.getAccountManager().getByDiscord(event.getOption("discord").getAsUser().getId());
        } else {
            event.reply("Provide either `player` or `discord`.").setEphemeral(true).queue();
            return;
        }
        if (account == null) {
            event.reply("No SuperAccount found.").setEphemeral(true).queue();
            return;
        }

        String uuids = account.allUuids().stream().map(UUID::toString).collect(Collectors.joining("\n"));
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("SuperAccount — " + account.getPrimaryName())
                .setColor(account.isBanned() ? new Color(0xE74C3C) : new Color(0x3498DB))
                .addField("Account ID", account.getAccountId().toString(), false)
                .addField("Discord", account.isLinkedToDiscord() ? "<@" + account.getDiscordId() + ">" : "Not linked", true)
                .addField("Play Points", String.valueOf(account.getPlayPoints()), true)
                .addField("Status", account.isBanned() ? "🔴 Banned" : "🟢 Active", true)
                .addField("MFA", account.isMfaEnabled() ? "Enabled" : "Disabled", true)
                .addField("Java UUIDs", String.valueOf(account.getJavaUuids().size()), true)
                .addField("Bedrock UUIDs", String.valueOf(account.getBedrockUuids().size()), true)
                .addField("Linked UUIDs", uuids.isEmpty() ? "None" : uuids, false)
                .setFooter("SuperChargedServer")
                .setTimestamp(Instant.now());
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    // ── /ai-status ───────────────────────────────────────────────────────

    private void handleAiStatus(SlashCommandInteractionEvent event) {
        FileConfiguration ai = plugin.getConfigManager().aiEngine();
        boolean enabled = ai.getBoolean("engine.enabled", true);
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("AI Anomaly Engine")
                .setColor(enabled ? new Color(0x2ECC71) : new Color(0x95A5A6))
                .addField("Status", enabled ? "🟢 Running" : "⚪ Disabled", true)
                .addField("Scan Interval", ai.getInt("engine.scan-interval-seconds", 60) + "s", true)
                .addField("Tracked Players", String.valueOf(plugin.getAnomalyEngine().trackedCount()), true)
                .addField("Thresholds", "Low " + ai.getDouble("thresholds.low", 40.0)
                        + " / Med " + ai.getDouble("thresholds.medium", 70.0)
                        + " / Crit " + ai.getDouble("thresholds.critical", 90.0), false)
                .setFooter("SuperChargedServer")
                .setTimestamp(Instant.now());
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    // ── /console + /execute ──────────────────────────────────────────────

    private void handleConsole(SlashCommandInteractionEvent event) {
        FileConfiguration config = plugin.getConfigManager().discord();

        String secureChannel = config.getString("console.channel-id", "");
        if (secureChannel != null && !secureChannel.isBlank() && !secureChannel.equals(event.getChannel().getId())) {
            event.reply("🔒 This command may only be used in the secure console channel.").setEphemeral(true).queue();
            return;
        }

        String command = event.getOption("command").getAsString().trim();
        if (command.startsWith("/")) command = command.substring(1);

        if (config.getBoolean("console.two-man-rule.enabled", true)) {
            String root = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
            List<String> blocked = config.getStringList("console.two-man-rule.commands");
            if (blocked.contains(root)) {
                event.reply("🔒 `" + root + "` is protected by the two-man rule and cannot be run from Discord.")
                        .setEphemeral(true).queue();
                return;
            }
        }

        final String finalCommand = command;
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            plugin.getLogger().info("Discord console command by " + event.getUser().getName() + ": /" + finalCommand);
            event.getHook().editOriginal(dispatched
                    ? "✅ Executed: `/" + finalCommand + "`"
                    : "⚠ Command not recognized: `/" + finalCommand + "`").queue();
        });
    }
}