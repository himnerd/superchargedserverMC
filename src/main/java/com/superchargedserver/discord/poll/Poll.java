package com.superchargedserver.discord.poll;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Poll {

    private String id;
    private String channelId;
    private String messageId;
    private String question;
    private String description = "";
    private List<String> options = new ArrayList<>();
    private List<String> allowedRoleIds = new ArrayList<>();
    /** Epoch millis when the poll auto-closes. */
    private long endTime;
    private boolean closed;
    private boolean allowVoteSwitch = true;

    public boolean isExpired() {
        return endTime <= System.currentTimeMillis();
    }
}