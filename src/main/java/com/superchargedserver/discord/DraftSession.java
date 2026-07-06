package com.superchargedserver.discord;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe per-user draft state for the /embed live builder and the
 * /announce preview loop. All mutable fields are volatile; the field list
 * is copy-on-write so preview renders never see a torn state.
 */
@Getter
@Setter
public class DraftSession {

    public record Field(String title, String value, boolean inline) {
    }

    public record PollOption(String title, String description) {
    }

    private final String userId;
    private final List<Field> fields = new CopyOnWriteArrayList<>();

    private volatile long lastTouched = System.currentTimeMillis();

    private volatile String channelId = "";
    private volatile String title = "";
    private volatile String description = "";
    private volatile String colorHex = "";
    private volatile String thumbnail = "";
    private volatile String image = "";

    private volatile String announcementText = "";

    private volatile String pollChannelId = "";
    private volatile String pollQuestion = "";
    private volatile String pollDescription = "";
    private volatile long pollDurationMinutes = 1440;
    private final List<String> pollAllowedRoleIds = new CopyOnWriteArrayList<>();
    private final List<PollOption> pollOptions = new CopyOnWriteArrayList<>();
    private final List<String> pendingChunks = new CopyOnWriteArrayList<>();

    public DraftSession(String userId) {
        this.userId = userId;
    }

    public void touch() {
        lastTouched = System.currentTimeMillis();
    }

    public boolean isExpired(long ttlMillis) {
        return System.currentTimeMillis() - lastTouched > ttlMillis;
    }
}