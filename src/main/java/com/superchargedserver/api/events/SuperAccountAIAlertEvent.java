package com.superchargedserver.api.events;

import com.superchargedserver.ai.AnomalyEngine.PlayerVector;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired on a worker thread the instant the AI module flags an analytical
 * anomaly score exceeding the configured threshold limits. Allows other
 * security plugins to react instantly.
 */
public class SuperAccountAIAlertEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final double score;
    private final String level;
    private final PlayerVector vector;

    public SuperAccountAIAlertEvent(UUID playerId, double score, String level, PlayerVector vector) {
        super(true);
        this.playerId = playerId;
        this.score = score;
        this.level = level;
        this.vector = vector;
    }

    public UUID getPlayerId() { return playerId; }
    public double getScore() { return score; }
    public String getLevel() { return level; }
    public PlayerVector getVector() { return vector; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}