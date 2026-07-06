package com.superchargedserver.discord.poll;

import com.superchargedserver.SuperChargedServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Persistent poll lifecycle engine. Every deployed poll registers its exact
 * end time here; on startup all open polls are reloaded from the database so
 * closures survive restarts and still fire accurately to the second. Closing
 * fetches the live message, rebuilds every action row in a disabled state and
 * freezes the final tallies — all off the Minecraft main thread.
 */
public class PollScheduler {

    private final SuperChargedServer plugin;
    private final PollRepository repository;
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SuperCharged-PollScheduler");
        thread.setDaemon(true);
        return thread;
    });

    public PollScheduler(SuperChargedServer plugin, PollRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    /** Reloads open polls from the database and re-arms their closure timers. */
    public void start() {
        executor.execute(() -> {
            for (Poll poll : repository.loadOpenPolls()) {
                schedule(poll);
            }
        });
    }

    /** Registers (or re-registers) a poll's closure timer, accurate to the millisecond. */
    public void schedule(Poll poll) {
        ScheduledFuture<?> previous = tasks.remove(poll.getId());
        if (previous != null) previous.cancel(false);

        long delay = Math.max(0L, poll.getEndTime() - System.currentTimeMillis());
        tasks.put(poll.getId(), executor.schedule(() -> closePoll(poll.getId()), delay, TimeUnit.MILLISECONDS));
    }

    /** Immediate closure path used when an expired poll still receives a click. */
    public void closeNow(String pollId) {
        executor.execute(() -> closePoll(pollId));
    }

    private void closePoll(String pollId) {
        tasks.remove(pollId);
        Poll poll = repository.getPoll(pollId);
        if (poll == null || poll.isClosed()) return;

        repository.markClosed(pollId);
        poll.setClosed(true);

        JDA jda = plugin.getDiscordManager().getJda();
        if (jda == null) return;
        TextChannel channel = jda.getTextChannelById(poll.getChannelId());
        if (channel == null || poll.getMessageId() == null) {
            plugin.getLogger().warning("Poll " + pollId + " closed, but its channel/message is gone.");
            return;
        }

        channel.retrieveMessageById(poll.getMessageId()).queue(message -> {
            Map<Integer, Integer> tallies = repository.countVotes(pollId);
            message.editMessageEmbeds(PollButtonBuilder.buildEmbed(poll, tallies))
                    .setComponents(PollButtonBuilder.disableAll(message.getActionRows()))
                    .queue(
                            done -> plugin.getLogger().info("Poll " + pollId + " closed — voting frozen."),
                            error -> plugin.getLogger().warning("Failed to freeze poll " + pollId + ": " + error.getMessage()));
        }, error -> plugin.getLogger().warning("Failed to fetch poll message " + pollId + ": " + error.getMessage()));
    }

    public void shutdown() {
        tasks.values().forEach(task -> task.cancel(false));
        tasks.clear();
        executor.shutdownNow();
    }
}