package com.superchargedserver.motd;

import lombok.Data;

import java.util.List;

@Data
public class MotdProfile {
    private String name;
    private int priority;
    private String schedule = "always";
    private List<String> motd = List.of();
    private String icon = "";
    private boolean animated;
    private int updateIntervalTicks = 20;
    private boolean hidePlayers;
    private String text = "";
    private int extraPlayers;
    private int maxPlayers = -1;
    private List<String> hover = List.of();
    private int currentIndex;
}