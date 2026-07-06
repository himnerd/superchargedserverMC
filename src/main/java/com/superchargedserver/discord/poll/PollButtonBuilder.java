package com.superchargedserver.discord.poll;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the option-to-button layout for polls: number-emoji decorations,
 * keyword-aware button coloring, balanced 5-per-row grids, live tally
 * embeds and the disabled-state matrix used when a poll is frozen.
 */
public final class PollButtonBuilder {

    private static final Color BRAND = new Color(0x7B2FFF);
    private static final int BUTTONS_PER_ROW = 5;
    private static final int MAX_ROWS = 5;
    private static final int MAX_LABEL = 30;

    /** 1️⃣–🔟 for the first ten options, regional letters for 11-25. */
    private static final String[] NUMBER_EMOJIS = {
            "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟",
            "🇦", "🇧", "🇨", "🇩", "🇪", "🇫", "🇬", "🇭", "🇮", "🇯",
            "🇰", "🇱", "🇲", "🇳", "🇴"
    };

    private static final String[] NEGATIVE_KEYWORDS = {
            "none of the above", "cancel", "no thanks", "against", "decline",
            "reject", "veto", "oppose", "disagree"
    };

    private static final String[] NEUTRAL_KEYWORDS = {
            "abstain", "neutral", "skip", "undecided", "no preference",
            "don't care", "dont care", "either", "unsure", "idk"
    };

    private PollButtonBuilder() {
    }

    public static int maxOptions() {
        return BUTTONS_PER_ROW * MAX_ROWS;
    }

    /**
     * Structures the voting buttons into balanced rows of five with unique
     * component IDs ({@code poll:vote:<pollId>:<optionIndex>}).
     */
    public static List<ActionRow> buildRows(Poll poll, boolean disabled) {
        List<Button> buttons = new ArrayList<>();
        List<String> options = poll.getOptions();
        for (int i = 0; i < options.size() && i < maxOptions(); i++) {
            String label = labelOf(options.get(i));
            Button button = Button.of(styleFor(label),
                            "poll:vote:" + poll.getId() + ":" + i,
                            buttonLabel(i, label))
                    .withEmoji(Emoji.fromUnicode(emojiFor(i, label)));
            buttons.add(disabled ? button.asDisabled() : button);
        }

        List<ActionRow> rows = new ArrayList<>();
        for (int start = 0; start < buttons.size(); start += BUTTONS_PER_ROW) {
            rows.add(ActionRow.of(buttons.subList(start, Math.min(start + BUTTONS_PER_ROW, buttons.size()))));
        }
        return rows;
    }

    /**
     * The state-switching matrix: rebuilds every existing action row with
     * each button shifted to its disabled state via {@link Button#asDisabled()},
     * preserving labels, emojis and final tallies.
     */
    public static List<ActionRow> disableAll(List<ActionRow> rows) {
        return rows.stream()
                .map(row -> ActionRow.of(row.getButtons().stream()
                        .map(Button::asDisabled)
                        .collect(Collectors.toList())))
                .collect(Collectors.toList());
    }

    /** Renders the poll embed with per-option progress bars, counts and percentages. */
    public static MessageEmbed buildEmbed(Poll poll, Map<Integer, Integer> tallies) {
        int total = tallies.values().stream().mapToInt(Integer::intValue).sum();

        StringBuilder body = new StringBuilder();
        if (poll.getDescription() != null && !poll.getDescription().isBlank()) {
            body.append(poll.getDescription()).append("\n\n");
        }
        List<String> options = poll.getOptions();
        for (int i = 0; i < options.size(); i++) {
            String label = labelOf(options.get(i));
            String description = descriptionOf(options.get(i));
            int votes = tallies.getOrDefault(i, 0);
            double pct = total == 0 ? 0.0 : 100.0 * votes / total;

            body.append(emojiFor(i, label)).append(" **").append(label).append("**\n");
            if (description != null) {
                body.append("-# ").append(description).append("\n");
            }
            body.append(bar(pct)).append(" `").append(votes)
                    .append(votes == 1 ? " vote" : " votes")
                    .append(String.format(Locale.ROOT, " (%.1f%%)`", pct))
                    .append("\n\n");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📊 " + poll.getQuestion())
                .setDescription(body.toString())
                .setColor(poll.isClosed() ? new Color(0x95A5A6) : BRAND)
                .setTimestamp(Instant.ofEpochMilli(poll.getEndTime()))
                .setFooter(poll.isClosed()
                        ? "🔒 Poll closed — final results • " + total + " total votes"
                        : "Poll closes");
        return embed.build();
    }

    private static String buttonLabel(int index, String label) {
        if (label.length() <= MAX_LABEL) return label;
        String prefix = (index + 1) + ". ";
        return prefix + label.substring(0, MAX_LABEL - prefix.length() - 1).trim() + "…";
    }

    private static String emojiFor(int index, String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        for (String keyword : NEGATIVE_KEYWORDS) {
            if (lower.contains(keyword)) return "🚫";
        }
        for (String keyword : NEUTRAL_KEYWORDS) {
            if (lower.contains(keyword)) return "⚪";
        }
        return index < NUMBER_EMOJIS.length ? NUMBER_EMOJIS[index] : "🔹";
    }

    private static ButtonStyle styleFor(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        for (String keyword : NEGATIVE_KEYWORDS) {
            if (lower.contains(keyword)) return ButtonStyle.DANGER;
        }
        for (String keyword : NEUTRAL_KEYWORDS) {
            if (lower.contains(keyword)) return ButtonStyle.SECONDARY;
        }
        return ButtonStyle.PRIMARY;
    }

    /** Options may carry an inline description: {@code "Kit PvP :: Fast-paced arena combat"}. */
    private static String labelOf(String option) {
        int split = option.indexOf("::");
        return (split < 0 ? option : option.substring(0, split)).trim();
    }

    private static String descriptionOf(String option) {
        int split = option.indexOf("::");
        if (split < 0) return null;
        String description = option.substring(split + 2).trim();
        return description.isEmpty() ? null : description;
    }

    private static String bar(double pct) {
        int filled = (int) Math.round(pct / 10.0);
        return "▰".repeat(Math.min(10, filled)) + "▱".repeat(Math.max(0, 10 - filled));
    }
}