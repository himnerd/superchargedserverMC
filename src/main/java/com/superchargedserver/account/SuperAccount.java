package com.superchargedserver.account;

import lombok.Data;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class SuperAccount {

    private UUID accountId;
    private String primaryName = "";
    private String discordId;
    private final Set<UUID> javaUuids = ConcurrentHashMap.newKeySet();
    private final Set<UUID> bedrockUuids = ConcurrentHashMap.newKeySet();
    private String statusMessage = "";
    private long playPoints;
    private String lastIp = "";
    private long lastLogin;
    private boolean mfaEnabled;
    private boolean banned;
    private final Map<String, String> customData = new ConcurrentHashMap<>();

    public Set<UUID> allUuids() {
        Set<UUID> all = new HashSet<>(javaUuids);
        all.addAll(bedrockUuids);
        return all;
    }

    public boolean isLinkedToDiscord() {
        return discordId != null && !discordId.isEmpty();
    }
}