package com.superchargedserver;

import com.superchargedserver.account.LinkCodeManager;
import com.superchargedserver.account.SuperAccountManager;
import com.superchargedserver.ai.AnomalyEngine;
import com.superchargedserver.ai.TelemetryListener;
import com.superchargedserver.ai.TelemetryManager;
import com.superchargedserver.ai.support.AISupportManager;
import com.superchargedserver.ai.support.InGameHelpExecutor;
import com.superchargedserver.api.SuperChargedAPI;
import com.superchargedserver.command.BanCommand;
import com.superchargedserver.command.LinkCommand;
import com.superchargedserver.command.ProfileCommand;
import com.superchargedserver.command.SuperAdminCommand;
import com.superchargedserver.command.SuperLoginCommand;
import com.superchargedserver.command.WhitelistCommand;
import com.superchargedserver.command.WikisCommand;
import com.superchargedserver.config.ConfigManager;
import com.superchargedserver.discord.DiscordManager;
import com.superchargedserver.inventory.gui.GUIListener;
import com.superchargedserver.inventory.gui.GUIManager;
import com.superchargedserver.listener.MotdListener;
import com.superchargedserver.listener.PlayerConnectionListener;
import com.superchargedserver.listener.DiscordGateListener;
import com.superchargedserver.motd.IconManager;
import com.superchargedserver.motd.MaintenanceManager;
import com.superchargedserver.motd.MotdManager;
import com.superchargedserver.performance.PerformanceManager;
import com.superchargedserver.security.MfaListener;
import com.superchargedserver.security.SecurityManager;
import com.superchargedserver.storage.DatabaseManager;
import com.superchargedserver.tools.ConnectionMessagesManager;
import com.superchargedserver.util.ColorUtil;
import com.superchargedserver.wiki.WikiManager;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

@Getter
public class SuperChargedServer extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PerformanceManager performanceManager;
    private SuperAccountManager accountManager;
    private LinkCodeManager linkCodeManager;
    private SecurityManager securityManager;
    private AISupportManager aiSupportManager;
    private DiscordManager discordManager;
    private MotdManager motdManager;
    private MaintenanceManager maintenanceManager;
    private ConnectionMessagesManager connectionMessagesManager;
    private IconManager iconManager;
    private TelemetryManager telemetryManager;
    private AnomalyEngine anomalyEngine;
    private WikiManager wikiManager;
    private GUIManager guiManager;
    private ChargedServerBridge chargedServerBridge;

    @Override
    public void onEnable() {
        createDirectories();

        configManager = new ConfigManager(this);
        configManager.load();

        databaseManager = new DatabaseManager(this);
        databaseManager.init();

        performanceManager = new PerformanceManager(this);
        performanceManager.load();
        performanceManager.start();

        accountManager = new SuperAccountManager(this);
        accountManager.loadAll();
        accountManager.startRewardTask();

        linkCodeManager = new LinkCodeManager(this);
        securityManager = new SecurityManager(this);

        aiSupportManager = new AISupportManager(this);
        aiSupportManager.start();

        discordManager = new DiscordManager(this);
        discordManager.start();

        motdManager = new MotdManager(this);
        motdManager.load();

        maintenanceManager = new MaintenanceManager(this);
        maintenanceManager.load();

        connectionMessagesManager = new ConnectionMessagesManager(this);
        connectionMessagesManager.load();

        iconManager = new IconManager(this);
        iconManager.load();

        telemetryManager = new TelemetryManager(this);
        telemetryManager.start();

        anomalyEngine = new AnomalyEngine(this);
        anomalyEngine.start();

        wikiManager = new WikiManager(this);
        wikiManager.load();

        guiManager = new GUIManager();
        Bukkit.getPluginManager().registerEvents(new GUIListener(guiManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MotdListener(this), this);
        Bukkit.getPluginManager().registerEvents(maintenanceManager, this);
        Bukkit.getPluginManager().registerEvents(new MfaListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TelemetryListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DiscordGateListener(this), this);

        getCommand("superlogin").setExecutor(new SuperLoginCommand(this));
        getCommand("profile").setExecutor(new ProfileCommand(this));
        getCommand("wikis").setExecutor(new WikisCommand(this));
        getCommand("superadmin").setExecutor(new SuperAdminCommand(this));
        BanCommand banCommand = new BanCommand(this);
        getCommand("ban").setExecutor(banCommand);
        getCommand("unban").setExecutor(banCommand);
        getCommand("whitelist").setExecutor(new WhitelistCommand(this));
        getCommand("link").setExecutor(new LinkCommand(this));
        getCommand("aihelp").setExecutor(new InGameHelpExecutor(this));

        SuperChargedAPI.init(this);
        chargedServerBridge = new ChargedServerBridge(this);
        chargedServerBridge.hook();
        getLogger().info("SuperChargedServer enabled — " + configManager.systemName() + " system online.");
    }

    @Override
    public void onDisable() {
        if (chargedServerBridge != null) chargedServerBridge.onUnload();
        if (telemetryManager != null) telemetryManager.shutdown();
        if (anomalyEngine != null) anomalyEngine.shutdown();
        if (accountManager != null) accountManager.saveAllSync();
        if (performanceManager != null) performanceManager.shutdown();
        if (aiSupportManager != null) aiSupportManager.shutdown();
        if (discordManager != null) discordManager.shutdown();
        if (databaseManager != null) databaseManager.shutdown();
    }

    private void createDirectories() {
        new File(getDataFolder(), "configs").mkdirs();
        new File(getDataFolder(), "configs/tools").mkdirs();
        new File(getDataFolder(), "data/accounts").mkdirs();
        new File(getDataFolder(), "server-profiles").mkdirs();
        new File(getDataFolder(), "AI").mkdirs();
        new File(getDataFolder(), "AI/feeds").mkdirs();
        new File(getDataFolder(), "icons/cache").mkdirs();
    }

    public void msg(CommandSender sender, String message) {
        sender.sendMessage(ColorUtil.colorize(ColorUtil.PREFIX + configManager.brand(message)));
    }

    public void actionBar(Player player, String message) {
        player.sendActionBar(ColorUtil.colorize(configManager.brand(message)));
    }
}