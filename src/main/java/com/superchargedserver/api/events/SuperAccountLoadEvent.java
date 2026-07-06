package com.superchargedserver.api.events;

import com.superchargedserver.account.SuperAccount;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player's SuperAccount is loaded from the database into
 * memory (cache miss or initial join). Mirrors the async flag of the
 * calling thread.
 */
public class SuperAccountLoadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SuperAccount account;

    public SuperAccountLoadEvent(SuperAccount account) {
        super(true);
        this.account = account;
    }

    public SuperAccount getAccount() { return account; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}