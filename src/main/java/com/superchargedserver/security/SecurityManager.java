package com.superchargedserver.security;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.SuperAccount;
import lombok.Data;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SecurityManager {

    @Data
    public static class MfaSession {
        private final String code;
        private final long expiresAt;
        private int attempts;
    }

    private final SuperChargedServer plugin;
    private final Map<UUID, MfaSession> mfaSessions = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    public SecurityManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void handleJoin(Player player, SuperAccount account) {
        if (player.getAddress() == null) return;
        String ip = player.getAddress().getAddress().getHostAddress();
        ConfigurationSection security = plugin.getConfigManager().superAccounts()
                .getConfigurationSection("security");
        boolean newIp = !account.getLastIp().isEmpty() && !account.getLastIp().equals(ip);

        if (security != null && security.getBoolean("ip-session-lock.enabled", true) && newIp) {
            long windowMillis = security.getInt("ip-session-lock.window-minutes", 30) * 60000L;
            if (System.currentTimeMillis() - account.getLastLogin() < windowMillis) {
                plugin.getLogger().warning("Security breach flag: " + account.getPrimaryName()
                        + " logged in from a new IP within the session-lock window.");
                plugin.getDiscordManager().logSecurity("IP-Session Lock triggered",
                        "**" + account.getPrimaryName() + "** logged in from a new IP within "
                                + security.getInt("ip-session-lock.window-minutes", 30) + " minutes.");
            }
        }

        if (security != null && security.getBoolean("vpn-detection.enabled", false)) {
            checkVpnAsync(ip, account.getPrimaryName());
        }

        if (security != null && security.getBoolean("mfa.enabled", true) && newIp
                && (isPrivileged(player, security) || account.isMfaEnabled())) {
            startMfa(player, account);
        }

        account.setLastIp(ip);
        account.setLastLogin(System.currentTimeMillis());
        plugin.getAccountManager().saveAsync(account);
    }

    private boolean isPrivileged(Player player, ConfigurationSection security) {
        if (player.isOp()) return true;
        for (String node : security.getStringList("mfa.privileged-permissions")) {
            if (player.hasPermission(node)) return true;
        }
        return false;
    }

    public void startMfa(Player player, SuperAccount account) {
        ConfigurationSection security = plugin.getConfigManager().superAccounts()
                .getConfigurationSection("security");
        int expiry = security == null ? 300 : security.getInt("mfa.code-expiration-seconds", 300);
        String code = String.format("%06d", random.nextInt(1_000_000));
        mfaSessions.put(player.getUniqueId(),
                new MfaSession(code, System.currentTimeMillis() + expiry * 1000L));
        freeze(player);
        plugin.msg(player, "<red><bold>MFA required.</bold> <gray>A 6-digit code was sent to your linked Discord. Type it in chat to unlock.");
        if (account.isLinkedToDiscord()) {
            plugin.getDiscordManager().dmCode(account.getDiscordId(),
                    "🔐 **MFA verification** — a login from a new IP was detected on your account `"
                            + account.getPrimaryName() + "`. Your code: **" + code + "** (expires in "
                            + (expiry / 60) + " minutes). If this wasn't you, contact staff immediately.");
        } else {
            plugin.msg(player, "<red>Your account is not linked to Discord — contact staff to unlock your account.");
        }
    }

    public void freeze(Player player) {
        frozen.add(player.getUniqueId());
    }

    public void unfreeze(Player player) {
        frozen.remove(player.getUniqueId());
        mfaSessions.remove(player.getUniqueId());
    }

    public boolean isFrozen(Player player) {
        return frozen.contains(player.getUniqueId());
    }

    public boolean verifyMfa(Player player, String input) {
        MfaSession session = mfaSessions.get(player.getUniqueId());
        if (session == null) {
            unfreeze(player);
            return true;
        }
        if (System.currentTimeMillis() > session.getExpiresAt()) {
            unfreeze(player);
            kickSync(player, "Your MFA code expired. Please rejoin to receive a new one.");
            return false;
        }
        if (MessageDigest.isEqual(session.getCode().getBytes(StandardCharsets.UTF_8),
                input.trim().getBytes(StandardCharsets.UTF_8))) {
            unfreeze(player);
            plugin.msg(player, "<green><bold>MFA verified.</bold> <gray>Your session has been unlocked.");
            return true;
        }
        session.setAttempts(session.getAttempts() + 1);
        ConfigurationSection security = plugin.getConfigManager().superAccounts()
                .getConfigurationSection("security");
        int maxAttempts = security == null ? 3 : security.getInt("mfa.max-attempts", 3);
        if (session.getAttempts() >= maxAttempts) {
            unfreeze(player);
            kickSync(player, "Too many incorrect MFA attempts.");
            plugin.getDiscordManager().logSecurity("MFA verification failed",
                    "**" + player.getName() + "** exceeded the maximum MFA attempts and was kicked.");
            return false;
        }
        plugin.msg(player, "<red>Incorrect code. <gray>" + (maxAttempts - session.getAttempts())
                + " attempt(s) remaining.");
        return false;
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        mfaSessions.entrySet().removeIf(entry -> now > entry.getValue().getExpiresAt());
    }

    private void kickSync(Player player, String reason) {
        Bukkit.getScheduler().runTask(plugin, () -> player.kickPlayer(reason));
    }

    private void checkVpnAsync(String ip, String name) {
        ConfigurationSection security = plugin.getConfigManager().superAccounts()
                .getConfigurationSection("security");
        String apiUrl = security == null
                ? "https://proxycheck.io/v2/%ip%?vpn=1"
                : security.getString("vpn-detection.api-url", "https://proxycheck.io/v2/%ip%?vpn=1");
        String url = apiUrl.replace("%ip%", ip);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                String body = response.toString().replace(" ", "");
                if (body.contains("\"proxy\":\"yes\"") || body.contains("\"vpn\":\"yes\"")) {
                    plugin.getLogger().warning("VPN/proxy detected for " + name + " (" + ip + ")");
                    plugin.getDiscordManager().logSecurity("VPN detected",
                            "**" + name + "** joined using a VPN/proxy (`" + ip + "`).");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("VPN check failed for " + ip + ": " + e.getMessage());
            }
        });
    }
}