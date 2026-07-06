package com.superchargedserver.config;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ConfigManager {

    private static final String[] FILES = {
            "superaccounts.yml", "discord.yml", "motd.yml", "tools/maintenance.yml", "wikis.yml",
            "ai_engine.yml", "telemetry.yml", "admin_interfaces.yml", "performance.yml",
            "tools/connections.yml"
    };

    private final SuperChargedServer plugin;
    private final Map<String, FileConfiguration> configs = new HashMap<>();

    public ConfigManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void load() {
        migrateLegacyMaintenance();
        for (String name : FILES) {
            File file = new File(plugin.getDataFolder(), "configs/" + name);
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                try {
                    plugin.saveResource("configs/" + name, false);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().severe("Missing embedded resource configs/" + name
                            + " — using empty config.");
                    configs.put(name, new YamlConfiguration());
                    continue;
                }
            }
            YamlConfiguration config = new YamlConfiguration();
            try {
                config.load(file);
                configs.put(name, config);
            } catch (Exception e) {
                // Fail-safe: broken user edit — fall back to bundled defaults
                // without interrupting the server.
                plugin.getLogger().warning("Syntax error in configs/" + name + " (" + e.getMessage()
                        + ") — falling back to built-in defaults.");
                InputStream defaults = plugin.getResource("configs/" + name);
                if (defaults != null) {
                    configs.put(name, YamlConfiguration.loadConfiguration(
                            new InputStreamReader(defaults, StandardCharsets.UTF_8)));
                } else {
                    configs.put(name, new YamlConfiguration());
                }
            }
        }
    }

    /** Pre-tools-folder installs keep their edited maintenance config. */
    private void migrateLegacyMaintenance() {
        File legacy = new File(plugin.getDataFolder(), "configs/maintenance.yml");
        File target = new File(plugin.getDataFolder(), "configs/tools/maintenance.yml");
        if (legacy.exists() && !target.exists()) {
            target.getParentFile().mkdirs();
            if (legacy.renameTo(target)) {
                plugin.getLogger().info("Moved configs/maintenance.yml to configs/tools/maintenance.yml");
            }
        }
    }

    public FileConfiguration superAccounts() { return configs.get("superaccounts.yml"); }
    public FileConfiguration discord() { return configs.get("discord.yml"); }
    public FileConfiguration motd() { return configs.get("motd.yml"); }
    public FileConfiguration maintenance() { return configs.get("tools/maintenance.yml"); }
    public FileConfiguration wikis() { return configs.get("wikis.yml"); }
    public FileConfiguration aiEngine() { return configs.get("ai_engine.yml"); }
    public FileConfiguration telemetry() { return configs.get("telemetry.yml"); }
    public FileConfiguration adminInterfaces() { return configs.get("admin_interfaces.yml"); }
    public FileConfiguration performance() { return configs.get("performance.yml"); }
    public FileConfiguration connections() { return configs.get("tools/connections.yml"); }

    public String systemName() {
        return superAccounts().getString("custom-system-name", "SuperAccount");
    }

    /** Replaces backend terminology with the configured user-facing brand. */
    public String brand(String message) {
        return message == null ? "" : message.replace("SuperAccount", systemName());
    }
}