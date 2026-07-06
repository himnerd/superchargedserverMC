package com.superchargedserver.tools;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.motd.MotdManager;
import com.superchargedserver.util.ColorUtil;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tools-folder feature: admin-controlled join/quit messages loaded from
 * configs/tools/connections.yml. Profiles use the same ASL presentation
 * syntax as motd.yml (MiniMessage + legacy color codes, priority ordering,
 * schedule conditions) extended with permission / first-join / world
 * conditions. Per-player overrides set via /superadmin joinmsg|quitmsg
 * take precedence over every profile.
 */
@Getter
public class ConnectionMessagesManager {

    private final SuperChargedServer plugin;
    private final List<ConnectionMessageProfile> profiles = new ArrayList<>();
    private boolean enabled;
    private String mode;

    public ConnectionMessagesManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void load() {
        profiles.clear();
        FileConfiguration config = plugin.getConfigManager().connections();
        enabled = config.getBoolean("settings.enabled", true);
        mode = config.getString("settings.mode", "RANDOM");

        ConfigurationSection profilesSection = config.getConfigurationSection("profiles");
        if (profilesSection != null) {
            for (String key : profilesSection.getKeys(false)) {
                ConfigurationSection sec = profilesSection.getConfigurationSection(key);
                if (sec == null || !sec.getBoolean("enabled", true)) continue;
                profiles.add(parseProfile(key, sec));
            }
        }
        profiles.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        plugin.getLogger().info("Loaded " + profiles.size() + " connection message profiles.");
    }

    private ConnectionMessageProfile parseProfile(String name, ConfigurationSection sec) {
        ConnectionMessageProfile profile = new ConnectionMessageProfile();
        profile.setName(name);
        profile.setPriority(sec.getInt("priority", 0));
        ConfigurationSection conditions = sec.getConfigurationSection("conditions");
        if (conditions != null) {
            profile.setSchedule(conditions.getString("schedule", "always"));
            profile.setPermission(conditions.getString("permission", ""));
            profile.setFirstJoinOnly(conditions.getBoolean("first-join", false));
            profile.setWorlds(conditions.getStringList("worlds"));
        }
        profile.setJoinMessages(sec.getStringList("join"));
        profile.setQuitMessages(sec.getStringList("quit"));
        return profile;
    }

    public void applyJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        String override = override(player.getUniqueId(), "join");
        if (override != null) {
            event.joinMessage(isHidden(override) ? null : render(override, player));
            return;
        }
        ConnectionMessageProfile profile = selectProfile(player, true);
        if (profile == null) return;
        event.joinMessage(render(pick(profile, true), player));
    }

    public void applyQuit(PlayerQuitEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        String override = override(player.getUniqueId(), "quit");
        if (override != null) {
            event.quitMessage(isHidden(override) ? null : render(override, player));
            return;
        }
        ConnectionMessageProfile profile = selectProfile(player, false);
        if (profile == null) return;
        event.quitMessage(render(pick(profile, false), player));
    }

    /**
     * Highest-priority profile whose every condition matches and that has
     * lines for the requested event wins; null keeps the vanilla message.
     */
    private ConnectionMessageProfile selectProfile(Player player, boolean join) {
        for (ConnectionMessageProfile profile : profiles) {
            if (profile.isFirstJoinOnly() && (!join || player.hasPlayedBefore())) continue;
            if (!profile.getPermission().isEmpty() && !player.hasPermission(profile.getPermission())) continue;
            if (!profile.getWorlds().isEmpty() && !profile.getWorlds().contains(player.getWorld().getName())) continue;
            if (!MotdManager.scheduleMatches(profile.getSchedule())) continue;
            if ((join ? profile.getJoinMessages() : profile.getQuitMessages()).isEmpty()) continue;
            return profile;
        }
        return null;
    }

    private String pick(ConnectionMessageProfile profile, boolean join) {
        List<String> lines = join ? profile.getJoinMessages() : profile.getQuitMessages();
        if ("SEQUENTIAL".equalsIgnoreCase(mode)) {
            if (join) {
                profile.setJoinIndex((profile.getJoinIndex() + 1) % lines.size());
                return lines.get(profile.getJoinIndex());
            }
            profile.setQuitIndex((profile.getQuitIndex() + 1) % lines.size());
            return lines.get(profile.getQuitIndex());
        }
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    private Component render(String raw, Player player) {
        raw = raw.replace("{player}", player.getName())
                .replace("{displayname}", player.getDisplayName())
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()));
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, raw);
        }
        return ColorUtil.colorize(raw);
    }

    private String override(UUID uuid, String key) {
        return plugin.getConfigManager().connections().getString("players." + uuid + "." + key);
    }

    /** Override values that silence the message entirely. */
    public boolean isHidden(String value) {
        return value.equalsIgnoreCase("hide") || value.equalsIgnoreCase("off") || value.equalsIgnoreCase("none");
    }

    public void setOverride(UUID uuid, String name, boolean join, String message) {
        FileConfiguration config = plugin.getConfigManager().connections();
        config.set("players." + uuid + ".name", name);
        config.set("players." + uuid + (join ? ".join" : ".quit"), message);
        save(config);
    }

    public void clearOverride(UUID uuid, boolean join) {
        FileConfiguration config = plugin.getConfigManager().connections();
        config.set("players." + uuid + (join ? ".join" : ".quit"), null);
        ConfigurationSection sec = config.getConfigurationSection("players." + uuid);
        if (sec != null && sec.getString("join") == null && sec.getString("quit") == null) {
            config.set("players." + uuid, null);
        }
        save(config);
    }

    private void save(FileConfiguration config) {
        try {
            config.save(new File(plugin.getDataFolder(), "configs/tools/connections.yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save configs/tools/connections.yml: " + e.getMessage());
        }
    }
}