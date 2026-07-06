package com.superchargedserver.account;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.api.events.SuperAccountCreateEvent;
import com.superchargedserver.api.events.SuperAccountLinkEvent;
import com.superchargedserver.api.events.SuperAccountLoadEvent;
import com.superchargedserver.api.events.SuperAccountMergeEvent;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.profile.PlayerProfile;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SuperAccountManager {

    private final SuperChargedServer plugin;
    private final Map<UUID, SuperAccount> accounts = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Long> uuidLinkCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> discordLinkCooldowns = new ConcurrentHashMap<>();

    public SuperAccountManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        for (SuperAccount account : plugin.getDatabaseManager().loadAll()) {
            accounts.put(account.getAccountId(), account);
            for (UUID uuid : account.allUuids()) {
                playerIndex.put(uuid, account.getAccountId());
            }
        }
        plugin.getLogger().info("Loaded " + accounts.size() + " SuperAccounts.");
    }

    public SuperAccount getAccount(UUID accountId) {
        return accounts.get(accountId);
    }

    public SuperAccount getByPlayer(UUID playerUuid) {
        UUID accountId = playerIndex.get(playerUuid);
        return accountId == null ? null : accounts.get(accountId);
    }

    public SuperAccount getByName(String name) {
        for (SuperAccount account : accounts.values()) {
            if (account.getPrimaryName().equalsIgnoreCase(name)) return account;
        }
        return null;
    }

    public SuperAccount getByDiscord(String discordId) {
        if (discordId == null || discordId.isEmpty()) return null;
        for (SuperAccount account : accounts.values()) {
            if (discordId.equals(account.getDiscordId())) return account;
        }
        return null;
    }

    public Collection<SuperAccount> all() {
        return accounts.values();
    }

    public SuperAccount getOrCreate(Player player) {
        SuperAccount existing = getByPlayer(player.getUniqueId());
        if (existing != null) return existing;

        // Cache-miss reload: the profile may have been expired from memory
        // by the performance layer while the row still exists in the database.
        SuperAccount persisted = loadIntoCache(player.getUniqueId());
        if (persisted != null) {
            return persisted;
        }

        boolean bedrock = isBedrock(player.getUniqueId());

        // Floodgate auto-merge: verified Bedrock link joins the Java account.
        if (bedrock && plugin.getConfigManager().superAccounts().getBoolean("geyser.auto-merge-linked", true)
                && Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            try {
                FloodgatePlayer fp = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
                if (fp != null && fp.getLinkedPlayer() != null) {
                    SuperAccount javaAccount = getByPlayer(fp.getLinkedPlayer().getJavaUniqueId());
                    if (javaAccount != null) {
                        linkPlayer(javaAccount, player.getUniqueId(), true);
                        return javaAccount;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        SuperAccount account = new SuperAccount();
        account.setAccountId(UUID.randomUUID());
        account.setPrimaryName(cleanName(player.getName()));
        if (bedrock) {
            account.getBedrockUuids().add(player.getUniqueId());
        } else {
            account.getJavaUuids().add(player.getUniqueId());
        }
        accounts.put(account.getAccountId(), account);
        playerIndex.put(player.getUniqueId(), account.getAccountId());
        callEvent(new SuperAccountCreateEvent(account));
        saveAsync(account);
        plugin.getDiscordManager().logRegistration(account);
        return account;
    }

    public void linkPlayer(SuperAccount target, UUID playerUuid, boolean bedrock) {
        UUID oldAccountId = playerIndex.get(playerUuid);
        SuperAccount absorbed = null;
        if (oldAccountId != null && !oldAccountId.equals(target.getAccountId())) {
            SuperAccount old = accounts.remove(oldAccountId);
            if (old != null) {
                absorbed = old;
                target.setPlayPoints(target.getPlayPoints() + old.getPlayPoints());
                for (UUID uuid : old.allUuids()) {
                    playerIndex.put(uuid, target.getAccountId());
                    if (old.getBedrockUuids().contains(uuid)) {
                        target.getBedrockUuids().add(uuid);
                    } else {
                        target.getJavaUuids().add(uuid);
                    }
                }
                deleteAsync(old);
            }
        }
        if (bedrock) {
            target.getBedrockUuids().add(playerUuid);
        } else {
            target.getJavaUuids().add(playerUuid);
        }
        playerIndex.put(playerUuid, target.getAccountId());
        callEvent(new SuperAccountLinkEvent(target, playerUuid, bedrock));
        callEvent(new SuperAccountMergeEvent(target, absorbed, playerUuid, bedrock));
        saveAsync(target);
        plugin.getDiscordManager().logLink(target, playerUuid);
    }

    public void linkDiscord(SuperAccount account, String discordId) {
        account.setDiscordId(discordId);
        plugin.getDiscordManager().applyDiscordName(account);
        saveAsync(account);
        plugin.getDiscordManager().logLink(account, null);
    }

    public void unlinkDiscord(SuperAccount account) {
        String discordId = account.getDiscordId();
        if (discordId == null) return;
        long until = System.currentTimeMillis()
                + plugin.getConfigManager().superAccounts().getInt("security.unlink-cooldown-days", 7) * 86400000L;
        discordLinkCooldowns.put(discordId, until);
        for (UUID uuid : account.allUuids()) {
            uuidLinkCooldowns.put(uuid, until);
        }
        account.setDiscordId(null);
        saveAsync(account);
    }

    public boolean canLink(UUID playerUuid) {
        Long until = uuidLinkCooldowns.get(playerUuid);
        return until == null || until < System.currentTimeMillis();
    }

    public boolean canLinkDiscord(String discordId) {
        Long until = discordLinkCooldowns.get(discordId);
        return until == null || until < System.currentTimeMillis();
    }

    public void banAccount(SuperAccount account, String reason, String source) {
        account.setBanned(true);
        BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
        for (UUID uuid : account.allUuids()) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            banList.addBan(offline.getPlayerProfile(), reason, (Date) null, source);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                online.kick(com.superchargedserver.util.ColorUtil.colorize("<red>" + reason));
            }
        }
        saveAsync(account);
    }

    public void unbanAccount(SuperAccount account) {
        account.setBanned(false);
        BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
        for (UUID uuid : account.allUuids()) {
            banList.pardon(Bukkit.getOfflinePlayer(uuid).getPlayerProfile());
        }
        saveAsync(account);
    }

    public void addPlayPoints(SuperAccount account, long amount) {
        account.setPlayPoints(account.getPlayPoints() + amount);
    }

    public void saveAsync(SuperAccount account) {
        plugin.getPerformanceManager().queueSave(account);
    }

    /**
     * Loads a cache-expired account from the database into memory and fires
     * {@link SuperAccountLoadEvent}. Safe to call from any thread; the
     * event's async flag mirrors the calling thread. Returns null when no
     * row exists for the player.
     */
    public SuperAccount loadIntoCache(UUID playerUuid) {
        SuperAccount persisted = plugin.getDatabaseManager().loadByPlayerUuid(playerUuid);
        if (persisted == null) return null;
        accounts.put(persisted.getAccountId(), persisted);
        for (UUID uuid : persisted.allUuids()) {
            playerIndex.put(uuid, persisted.getAccountId());
        }
        Bukkit.getPluginManager().callEvent(new SuperAccountLoadEvent(persisted));
        return persisted;
    }

    /**
     * Purges an offline player's SuperAccount from the in-memory cache to
     * keep the heap footprint clean. The row stays in the database and is
     * transparently reloaded by {@link #getOrCreate(Player)} on next join.
     * Must be called on the main thread.
     */
    public void evictIfOffline(UUID playerUuid) {
        SuperAccount account = getByPlayer(playerUuid);
        if (account == null) return;
        if (!plugin.getDatabaseManager().isOnline()) return;
        for (UUID uuid : account.allUuids()) {
            if (Bukkit.getPlayer(uuid) != null) return;
        }
        plugin.getPerformanceManager().queueSave(account);
        accounts.remove(account.getAccountId());
        for (UUID uuid : account.allUuids()) {
            playerIndex.remove(uuid);
        }
    }

    private void deleteAsync(SuperAccount account) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.getDatabaseManager().deleteAccount(account.getAccountId()));
    }

    public void saveAllSync() {
        for (SuperAccount account : accounts.values()) {
            plugin.getDatabaseManager().saveAccount(account);
        }
    }

    public void startRewardTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long bonus = plugin.getConfigManager().superAccounts()
                    .getLong("rewards.linked-bonus-points-per-minute", 1);
            if (bonus <= 0) return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                SuperAccount account = getByPlayer(player.getUniqueId());
                if (account != null && account.isLinkedToDiscord()) {
                    addPlayPoints(account, bonus);
                }
            }
        }, 1200L, 1200L);
    }

    public boolean isBedrock(UUID uuid) {
        if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            try {
                return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
            } catch (Exception ignored) {
            }
        }
        return uuid.getMostSignificantBits() == 0;
    }

    private String cleanName(String name) {
        if (plugin.getConfigManager().superAccounts().getBoolean("naming-hierarchy.strip-bedrock-prefix", true)
                && name.startsWith(".")) {
            return name.substring(1);
        }
        return name;
    }

    private void callEvent(Event event) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
        }
    }
}