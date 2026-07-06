package com.superchargedserver.api.events;

import com.superchargedserver.account.SuperAccount;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Fired on a worker thread when an old SuperAccount is fully absorbed
 * into a target account during a link merge. The absorbed account has
 * already been deleted from the database by the time this fires.
 */
public class SuperAccountMergeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SuperAccount target;
    private final SuperAccount absorbed;
    private final UUID playerUuid;
    private final boolean bedrock;

    public SuperAccountMergeEvent(SuperAccount target, @Nullable SuperAccount absorbed,
                                   UUID playerUuid, boolean bedrock) {
        super(true);
        this.target = target;
        this.absorbed = absorbed;
        this.playerUuid = playerUuid;
        this.bedrock = bedrock;
    }

    public SuperAccount getTarget() { return target; }
    @Nullable public SuperAccount getAbsorbed() { return absorbed; }
    public UUID getPlayerUuid() { return playerUuid; }
    public boolean isBedrock() { return bedrock; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}