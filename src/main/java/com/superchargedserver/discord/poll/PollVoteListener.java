package com.superchargedserver.discord.poll;

import com.superchargedserver.SuperChargedServer;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.Map;

/**
 * Handles {@code poll:vote:<pollId>:<optionIndex>} clicks: registers or
 * switches the vote in SQLite and instantly re-renders the live tallies.
 * Runs entirely on JDA callback threads — never touches the main tick loop.
 */
public class PollVoteListener extends ListenerAdapter {

    private final SuperChargedServer plugin;
    private final PollRepository repository;
    private final PollScheduler scheduler;

    public PollVoteListener(SuperChargedServer plugin, PollRepository repository, PollScheduler scheduler) {
        this.plugin = plugin;
        this.repository = repository;
        this.scheduler = scheduler;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("poll:vote:")) return;

        String[] parts = id.split(":");
        if (parts.length != 4) return;
        String pollId = parts[2];
        int optionIndex;
        try {
            optionIndex = Integer.parseInt(parts[3]);
        } catch (NumberFormatException ex) {
            return;
        }

        Poll poll = repository.getPoll(pollId);
        if (poll == null || poll.isClosed() || poll.isExpired()) {
            if (poll != null && !poll.isClosed()) scheduler.closeNow(pollId);
            event.reply("🔒 This poll has closed — votes are frozen.").setEphemeral(true).queue();
            return;
        }
        if (optionIndex < 0 || optionIndex >= poll.getOptions().size()) return;

        if (!poll.getAllowedRoleIds().isEmpty()) {
            Member member = event.getMember();
            boolean permitted = member != null && member.getRoles().stream()
                    .anyMatch(role -> poll.getAllowedRoleIds().contains(role.getId()));
            if (!permitted) {
                event.reply("🔒 This poll is restricted to specific roles — you are not eligible to vote.")
                        .setEphemeral(true).queue();
                return;
            }
        }

        String userId = event.getUser().getId();
        Integer previous = repository.getUserVote(pollId, userId);
        if (previous != null && previous == optionIndex) {
            event.reply("🗳 You already voted for this option.").setEphemeral(true).queue();
            return;
        }
        if (previous != null && !poll.isAllowVoteSwitch()) {
            event.reply("🔒 This poll locks your first choice — votes cannot be changed.").setEphemeral(true).queue();
            return;
        }

        repository.setVote(pollId, userId, optionIndex);
        Map<Integer, Integer> tallies = repository.countVotes(pollId);
        event.editMessageEmbeds(PollButtonBuilder.buildEmbed(poll, tallies)).queue(
                null,
                error -> plugin.getLogger().warning("Failed to refresh poll " + pollId + ": " + error.getMessage()));
    }
}