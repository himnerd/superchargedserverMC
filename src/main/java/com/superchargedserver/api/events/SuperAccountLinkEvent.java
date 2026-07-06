package com.superchargedserver.api.events;

import com.superchargedserver.account.SuperAccount;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired when a UUID is linked to an existing SuperAccount (via
 * /superlogin in-game handshake or Floodgate auto-merge). The event
 * fires on the thread that initiated the link.
 */
public class SuperAccountLinkEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SuperAccount account;
    private final UUID playerUuid;
    private final boolean bedrock;

    public SuperAccountLinkEvent(SuperAccount account, UUID playerUuid, boolean bedrock) {
        super(true);
        this.account = account;
        this.playerUuid = playerUuid;
        this.bedrock = bedrock;
    }

    public SuperAccount getAccount() { return account; }
    public UUID getPlayerUuid() { return playerUuid; }
    public boolean isBedrock() { return bedrock; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}