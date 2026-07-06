package com.superchargedserver.tools;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * ASL-style presentation profile for join/quit messages — mirrors
 * MotdProfile semantics: priority ordering plus a schedule condition,
 * extended with permission, first-join and world conditions.
 */
@Data
public class ConnectionMessageProfile {

    private String name;
    private int priority;
    private String schedule = "always";
    private String permission = "";
    private boolean firstJoinOnly;
    private List<String> worlds = new ArrayList<>();
    private List<String> joinMessages = new ArrayList<>();
    private List<String> quitMessages = new ArrayList<>();
    private int joinIndex = -1;
    private int quitIndex = -1;
}