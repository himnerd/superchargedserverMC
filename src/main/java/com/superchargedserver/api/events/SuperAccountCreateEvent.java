package com.superchargedserver.api.events;

import com.superchargedserver.account.SuperAccount;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a brand-new SuperAccount is created for a player who has
 * never joined before (no cached or persisted account). Fired on the
 * main thread from the join handler.
 */
public class SuperAccountCreateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SuperAccount account;

    public SuperAccountCreateEvent(SuperAccount account) {
        super(true); // async
        this.account = account;
    }

    public SuperAccount getAccount() {
        return account;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}