package com.superchargedserver.discord;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-mixup guard for asynchronous drafting workers. Every async operation
 * is anchored to a compound runtime identity — the initiating user ID, the
 * channel the dashboard lives in, and a single-use cryptographic UUID token.
 * A worker result is accepted exactly once and only against the identical
 * compound anchor, so concurrent staff drafts can never cross-contaminate.
 */
public class SessionStateSafetyFilter {

    private record Anchor(String userId, String channelId, long issuedAt) {
        boolean expired(long ttlMillis) {
            return System.currentTimeMillis() - issuedAt > ttlMillis;
        }
    }

    private static final long TTL_MILLIS = 2L * 60L * 1000L;

    private final Map<String, Anchor> anchors = new ConcurrentHashMap<>();

    /** Issues a single-use token bound to the user + channel pair. */
    public String issue(String userId, String channelId) {
        anchors.values().removeIf(anchor -> anchor.expired(TTL_MILLIS));
        String token = UUID.randomUUID().toString();
        anchors.put(token, new Anchor(userId, channelId, System.currentTimeMillis()));
        return token;
    }

    /** Atomically consumes the token; true only for the exact original anchor within its TTL. */
    public boolean consume(String token, String userId, String channelId) {
        Anchor anchor = anchors.remove(token);
        return anchor != null
                && !anchor.expired(TTL_MILLIS)
                && anchor.userId().equals(userId)
                && anchor.channelId().equals(channelId);
    }
}