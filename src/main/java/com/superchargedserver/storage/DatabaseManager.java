package com.superchargedserver.storage;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.account.SuperAccount;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class DatabaseManager {

    private final SuperChargedServer plugin;
    private HikariDataSource dataSource;
    @Getter
    private boolean online;

    public DatabaseManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            ConfigurationSection db = plugin.getConfigManager().superAccounts()
                    .getConfigurationSection("global-sync.database");
            boolean globalSync = plugin.getConfigManager().superAccounts()
                    .getBoolean("global-sync.enabled", false);
            String type = db != null ? db.getString("type", "sqlite") : "sqlite";

            HikariConfig config = new HikariConfig();
            config.setPoolName("SuperChargedServer");
            if (globalSync && "mysql".equalsIgnoreCase(type) && db != null) {
                config.setJdbcUrl("jdbc:mysql://" + db.getString("host", "localhost") + ":"
                        + db.getInt("port", 3306) + "/" + db.getString("database", "supercharged")
                        + "?useSSL=" + db.getBoolean("useSSL", false));
                config.setUsername(db.getString("username", "root"));
                config.setPassword(db.getString("password", ""));
                config.setMaximumPoolSize(db.getInt("maximum-pool-size", 8));
                config.setConnectionTimeout(db.getLong("connection-timeout", 8000));
                config.setLeakDetectionThreshold(db.getLong("leak-detection-threshold", 15000));
            } else {
                File file = new File(plugin.getDataFolder(), "data/accounts/accounts.db");
                config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
                config.setMaximumPoolSize(1);
            }
            dataSource = new HikariDataSource(config);
            createTables();
            online = true;
        } catch (Exception e) {
            online = false;
            plugin.getLogger().severe("Database initialization failed: " + e.getMessage());
        }
    }

    private void createTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sca_accounts ("
                    + "account_id VARCHAR(36) PRIMARY KEY,"
                    + "primary_name VARCHAR(64),"
                    + "discord_id VARCHAR(32),"
                    + "java_uuids TEXT,"
                    + "bedrock_uuids TEXT,"
                    + "status_message TEXT,"
                    + "play_points BIGINT,"
                    + "last_ip VARCHAR(64),"
                    + "last_login BIGINT,"
                    + "mfa_enabled INT,"
                    + "banned INT,"
                    + "custom_data TEXT)");
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE sca_accounts ADD COLUMN discord_tag VARCHAR(64)");
        } catch (Exception ignored) {
            // Column already exists on pre-existing installs.
        }
    }

    public void saveAccount(SuperAccount account) {
        if (!online) return;
        String sql = "REPLACE INTO sca_accounts (account_id, primary_name, discord_id, discord_tag, java_uuids, "
                + "bedrock_uuids, status_message, play_points, last_ip, last_login, mfa_enabled, banned, custom_data) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, account.getAccountId().toString());
            ps.setString(2, account.getPrimaryName());
            ps.setString(3, account.getDiscordId());
            ps.setString(4, account.getDiscordTag());
            ps.setString(5, joinUuids(account.getJavaUuids()));
            ps.setString(6, joinUuids(account.getBedrockUuids()));
            ps.setString(7, account.getStatusMessage());
            ps.setLong(8, account.getPlayPoints());
            ps.setString(9, account.getLastIp());
            ps.setLong(10, account.getLastLogin());
            ps.setInt(11, account.isMfaEnabled() ? 1 : 0);
            ps.setInt(12, account.isBanned() ? 1 : 0);
            ps.setString(13, joinCustomData(account.getCustomData()));
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save account " + account.getPrimaryName() + ": " + e.getMessage());
        }
    }

    public void deleteAccount(UUID accountId) {
        if (!online) return;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("DELETE FROM sca_accounts WHERE account_id = ?")) {
            ps.setString(1, accountId.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to delete account: " + e.getMessage());
        }
    }

    public List<SuperAccount> loadAll() {
        List<SuperAccount> result = new ArrayList<>();
        if (!online) return result;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM sca_accounts")) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load accounts: " + e.getMessage());
        }
        return result;
    }

    /** Single-row lookup used to reload cache-expired accounts on join. */
    public SuperAccount loadByPlayerUuid(UUID playerUuid) {
        if (!online) return null;
        String needle = "%" + playerUuid + "%";
        String sql = "SELECT * FROM sca_accounts WHERE java_uuids LIKE ? OR bedrock_uuids LIKE ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, needle);
            ps.setString(2, needle);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load account for " + playerUuid + ": " + e.getMessage());
        }
        return null;
    }

    private SuperAccount mapRow(ResultSet rs) throws Exception {
        SuperAccount account = new SuperAccount();
        account.setAccountId(UUID.fromString(rs.getString("account_id")));
        account.setPrimaryName(rs.getString("primary_name"));
        account.setDiscordId(rs.getString("discord_id"));
        account.setDiscordTag(rs.getString("discord_tag") == null ? "" : rs.getString("discord_tag"));
        splitUuids(rs.getString("java_uuids"), account.getJavaUuids());
        splitUuids(rs.getString("bedrock_uuids"), account.getBedrockUuids());
        account.setStatusMessage(rs.getString("status_message") == null ? "" : rs.getString("status_message"));
        account.setPlayPoints(rs.getLong("play_points"));
        account.setLastIp(rs.getString("last_ip") == null ? "" : rs.getString("last_ip"));
        account.setLastLogin(rs.getLong("last_login"));
        account.setMfaEnabled(rs.getInt("mfa_enabled") == 1);
        account.setBanned(rs.getInt("banned") == 1);
        splitCustomData(rs.getString("custom_data"), account.getCustomData());
        return account;
    }

    public void shutdown() {
        if (dataSource != null) dataSource.close();
        online = false;
    }

    /** Pooled connection for auxiliary repositories (polls, drafts, …). */
    public Connection getConnection() throws Exception {
        return dataSource.getConnection();
    }

    private String joinUuids(Collection<UUID> uuids) {
        return uuids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    private void splitUuids(String raw, Set<UUID> target) {
        if (raw == null || raw.isEmpty()) return;
        for (String part : raw.split(",")) {
            try {
                target.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private String joinCustomData(Map<String, String> data) {
        return data.entrySet().stream()
                .map(e -> e.getKey() + "\u0001" + e.getValue())
                .collect(Collectors.joining("\u0002"));
    }

    private void splitCustomData(String raw, Map<String, String> target) {
        if (raw == null || raw.isEmpty()) return;
        for (String entry : raw.split("\u0002")) {
            String[] kv = entry.split("\u0001", 2);
            if (kv.length == 2) target.put(kv[0], kv[1]);
        }
    }
}