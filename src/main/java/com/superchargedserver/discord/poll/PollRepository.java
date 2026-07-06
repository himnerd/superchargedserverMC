package com.superchargedserver.discord.poll;

import com.superchargedserver.SuperChargedServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite/MySQL persistence for polls and votes. All methods are safe to call
 * from any thread — Hikari pools the connections and every statement is
 * self-contained, so poll closures survive server restarts.
 */
public class PollRepository {

    private static final String SEPARATOR = "\u0001";

    private final SuperChargedServer plugin;

    public PollRepository(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void init() {
        if (!plugin.getDatabaseManager().isOnline()) return;
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sca_polls ("
                    + "poll_id VARCHAR(16) PRIMARY KEY,"
                    + "channel_id VARCHAR(32),"
                    + "message_id VARCHAR(32),"
                    + "question TEXT,"
                    + "description TEXT,"
                    + "options TEXT,"
                    + "end_time BIGINT,"
                    + "closed INT,"
                    + "allow_switch INT,"
                    + "allowed_roles TEXT)");
            try {
                statement.executeUpdate("ALTER TABLE sca_polls ADD COLUMN description TEXT");
            } catch (Exception ignored) {
            }
            try {
                statement.executeUpdate("ALTER TABLE sca_polls ADD COLUMN allowed_roles TEXT");
            } catch (Exception ignored) {
            }
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sca_poll_votes ("
                    + "poll_id VARCHAR(16),"
                    + "user_id VARCHAR(32),"
                    + "option_index INT,"
                    + "PRIMARY KEY (poll_id, user_id))");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create poll tables: " + e.getMessage());
        }
    }

    public void save(Poll poll) {
        if (!plugin.getDatabaseManager().isOnline()) return;
        String sql = "REPLACE INTO sca_polls (poll_id, channel_id, message_id, question, description, options, "
                + "end_time, closed, allow_switch, allowed_roles) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, poll.getId());
            ps.setString(2, poll.getChannelId());
            ps.setString(3, poll.getMessageId());
            ps.setString(4, poll.getQuestion());
            ps.setString(5, poll.getDescription() == null ? "" : poll.getDescription());
            ps.setString(6, String.join(SEPARATOR, poll.getOptions()));
            ps.setLong(7, poll.getEndTime());
            ps.setInt(8, poll.isClosed() ? 1 : 0);
            ps.setInt(9, poll.isAllowVoteSwitch() ? 1 : 0);
            ps.setString(10, String.join(SEPARATOR, poll.getAllowedRoleIds()));
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save poll " + poll.getId() + ": " + e.getMessage());
        }
    }

    public Poll getPoll(String pollId) {
        if (!plugin.getDatabaseManager().isOnline()) return null;
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT * FROM sca_polls WHERE poll_id = ?")) {
            ps.setString(1, pollId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load poll " + pollId + ": " + e.getMessage());
        }
        return null;
    }

    /** Every non-closed poll — loaded on startup so closure timers survive restarts. */
    public List<Poll> loadOpenPolls() {
        List<Poll> result = new ArrayList<>();
        if (!plugin.getDatabaseManager().isOnline()) return result;
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM sca_polls WHERE closed = 0")) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load open polls: " + e.getMessage());
        }
        return result;
    }

    public void markClosed(String pollId) {
        if (!plugin.getDatabaseManager().isOnline()) return;
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = connection.prepareStatement("UPDATE sca_polls SET closed = 1 WHERE poll_id = ?")) {
            ps.setString(1, pollId);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to close poll " + pollId + ": " + e.getMessage());
        }
    }

    /** Returns the option index the user voted for, or null if they haven't voted. */
    public Integer getUserVote(String pollId, String userId) {
        if (!plugin.getDatabaseManager().isOnline()) return null;
        String sql = "SELECT option_index FROM sca_poll_votes WHERE poll_id = ? AND user_id = ?";
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pollId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read vote: " + e.getMessage());
        }
        return null;
    }

    /** Registers or seamlessly switches the user's vote. */
    public void setVote(String pollId, String userId, int optionIndex) {
        if (!plugin.getDatabaseManager().isOnline()) return;
        String sql = "REPLACE INTO sca_poll_votes (poll_id, user_id, option_index) VALUES (?,?,?)";
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pollId);
            ps.setString(2, userId);
            ps.setInt(3, optionIndex);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to record vote: " + e.getMessage());
        }
    }

    /** option index → vote count. */
    public Map<Integer, Integer> countVotes(String pollId) {
        Map<Integer, Integer> tallies = new HashMap<>();
        if (!plugin.getDatabaseManager().isOnline()) return tallies;
        String sql = "SELECT option_index, COUNT(*) FROM sca_poll_votes WHERE poll_id = ? GROUP BY option_index";
        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pollId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tallies.put(rs.getInt(1), rs.getInt(2));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to count votes: " + e.getMessage());
        }
        return tallies;
    }

    private Poll mapRow(ResultSet rs) throws Exception {
        Poll poll = new Poll();
        poll.setId(rs.getString("poll_id"));
        poll.setChannelId(rs.getString("channel_id"));
        poll.setMessageId(rs.getString("message_id"));
        poll.setQuestion(rs.getString("question"));
        String raw = rs.getString("options");
        poll.setOptions(raw == null || raw.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(raw.split(SEPARATOR))));
        poll.setEndTime(rs.getLong("end_time"));
        poll.setClosed(rs.getInt("closed") == 1);
        poll.setAllowVoteSwitch(rs.getInt("allow_switch") == 1);
        String description = rs.getString("description");
        poll.setDescription(description == null ? "" : description);
        String roles = rs.getString("allowed_roles");
        poll.setAllowedRoleIds(roles == null || roles.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(roles.split(SEPARATOR))));
        return poll;
    }
}