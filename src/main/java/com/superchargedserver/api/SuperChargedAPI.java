package com.superchargedserver.api;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.SuperAccount;
import com.superchargedserver.account.SuperAccountManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Global singleton bridge for third-party plugin integration.
 * Access via {@code SuperChargedServerAPI.getInstance()}.
 */
public final class SuperChargedAPI {

    private static SuperChargedAPI instance;
    private final SuperChargedServer plugin;

    private SuperChargedAPI(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public static void init(SuperChargedServer plugin) {
        if (instance == null) {
            instance = new SuperChargedAPI(plugin);
        }
    }

    public static SuperChargedAPI getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SuperChargedAPI not initialized — SuperChargedServer must be enabled first.");
        }
        return instance;
    }

    public SuperAccountManager getAccountManager() {
        return plugin.getAccountManager();
    }

    public SuperAccount getAccount(UUID playerUuid) {
        return plugin.getAccountManager().getByPlayer(playerUuid);
    }

    public CompletableFuture<SuperAccount> getAccountAsync(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> getAccount(playerUuid));
    }

    public String applySystemName(String message) {
        return plugin.getConfigManager().brand(message);
    }

    public boolean isMaintenanceActive() {
        return plugin.getMaintenanceManager().isEnabled();
    }

    public SuperChargedServer getPlugin() {
        return plugin;
    }
}