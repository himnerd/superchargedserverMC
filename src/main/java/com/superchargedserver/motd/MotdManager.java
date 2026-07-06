package com.superchargedserver.motd;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class MotdManager {

    private final SuperChargedServer plugin;
    private final List<MotdProfile> profiles = new ArrayList<>();

    public MotdManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void load() {
        profiles.clear();
        FileConfiguration config = plugin.getConfigManager().motd();
        ConfigurationSection profilesSection = config.getConfigurationSection("profiles");
        if (profilesSection == null) return;

        for (String key : profilesSection.getKeys(false)) {
            ConfigurationSection sec = profilesSection.getConfigurationSection(key);
            if (sec == null || !sec.getBoolean("enabled", true)) continue;
            profiles.add(parseProfile(key, sec));
        }

        profiles.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        plugin.getLogger().info("Loaded " + profiles.size() + " MOTD profiles.");
    }

    /**
     * Parses an ASL-formatted profile block. Shared with MaintenanceManager
     * so maintenance.yml keeps the exact same presentation syntax.
     */
    public static MotdProfile parseProfile(String name, ConfigurationSection sec) {
        MotdProfile profile = new MotdProfile();
        profile.setName(name);
        profile.setPriority(sec.getInt("priority", 0));
        profile.setSchedule(sec.getString("schedule", "always"));
        profile.setMotd(sec.getStringList("motd"));
        profile.setIcon(sec.getString("icon", ""));
        profile.setAnimated(sec.getBoolean("animated", false));
        profile.setUpdateIntervalTicks(sec.getInt("update-interval-ticks", 20));

        ConfigurationSection pc = sec.getConfigurationSection("playerCount");
        if (pc != null) {
            profile.setHidePlayers(pc.getBoolean("hidePlayers", false));
            profile.setText(pc.getString("text", ""));
            profile.setExtraPlayers(pc.getInt("extraPlayers", 0));
            profile.setMaxPlayers(pc.getInt("maxPlayers", -1));
            profile.setHover(pc.getStringList("hover"));
        }
        return profile;
    }

    public List<MotdProfile> getProfiles() {
        return profiles;
    }

    /**
     * Priority-based registry evaluation: profiles are pre-sorted highest
     * priority first; the first profile whose schedule condition matches
     * the current time wins.
     */
    public MotdProfile getActiveProfile() {
        for (MotdProfile profile : profiles) {
            if (scheduleMatches(profile.getSchedule())) {
                return profile;
            }
        }
        return null;
    }

    public static boolean scheduleMatches(String schedule) {
        if (schedule == null || schedule.isEmpty() || "always".equalsIgnoreCase(schedule)) return true;
        String[] parts = schedule.split("-");
        if (parts.length != 2) return true;
        try {
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            LocalTime now = LocalTime.now();
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            // Overnight window, e.g. 22:00-06:00
            return !now.isBefore(start) || now.isBefore(end);
        } catch (Exception e) {
            return true;
        }
    }

    public String getMotdLine(MotdProfile profile) {
        if (profile == null || profile.getMotd().isEmpty()) return "SuperChargedServer";
        List<String> motds = profile.getMotd();
        String mode = plugin.getConfigManager().motd().getString("settings.mode", "RANDOM");
        if ("SEQUENTIAL".equalsIgnoreCase(mode)) {
            profile.setCurrentIndex((profile.getCurrentIndex() + 1) % motds.size());
            return motds.get(profile.getCurrentIndex());
        }
        return motds.get(ThreadLocalRandom.current().nextInt(motds.size()));
    }
}