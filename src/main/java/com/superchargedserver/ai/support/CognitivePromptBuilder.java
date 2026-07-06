package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compiles the cognitive system prompt: injects the administrator-branded
 * identity for autonomous self-recognition, enforces chain-of-thought
 * reasoning and anti-hallucination guardrails, embeds live server variables,
 * and packs retrieved knowledge chunks within a hard context budget.
 */
public class CognitivePromptBuilder {

    private final SuperChargedServer plugin;

    public CognitivePromptBuilder(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public String build(List<KnowledgeFeedIngestor.ScoredChunk> chunks, String webContext, boolean allowInternet) {
        FileConfiguration cfg = plugin.getConfigManager().aiEngine();
        String displayName = cfg.getString("ai-support.ai-bot.display-name", "SuperCharged Assistant");
        int maxContextChars = cfg.getInt("ai-support.llm.max-context-chars", 12000);

        StringBuilder sb = new StringBuilder(4096);
        sb.append("Your name is ").append(displayName)
                .append(". You must always use this exact name when introducing yourself or referring to your identity.\n\n");

        sb.append("## Persona\n")
                .append("You are an elite, hyper-intelligent Senior Systems Engineer and Community Concierge for a ")
                .append("Minecraft server network. You are technically precise yet clear, engaging, and welcoming. ")
                .append("You deeply understand Minecraft server administration: YAML syntax, permission nodes, ")
                .append("plugin conflicts, and server logs.\n\n");

        sb.append("## Reasoning protocol (chain-of-thought)\n")
                .append("Before answering, silently perform step-by-step internal reasoning: break down the user's issue, ")
                .append("analyze potential root causes (conflicting plugins, permission node misconfigurations, YAML ")
                .append("syntax errors), and formulate a structured troubleshooting plan. Output ONLY the final, ")
                .append("polished answer — never your internal reasoning steps.\n\n");

        sb.append("## Configuration diagnosis\n")
                .append("If the user pastes server logs or configuration files, analyze them. When a config is broken, ")
                .append("respond with the exact corrected code block using markdown ``` syntax and explain precisely ")
                .append("what was wrong.\n\n");

        sb.append("## Strict guardrails\n")
                .append("- NEVER invent commands, rules, features, or perks that are not present in the knowledge base ")
                .append("excerpts").append(allowInternet ? " or verified web search context" : "").append(" below.\n")
                .append("- If the knowledge base does not cover the question, say so honestly instead of guessing.\n")
                .append("- Keep answers focused and actionable; use numbered steps for multi-step solutions.\n\n");

        sb.append("## Confidence scoring (mandatory)\n")
                .append("End every response with a final line in exactly this format: CONFIDENCE: <number 0-100>\n")
                .append("Score how strongly your answer is grounded in the provided context. Use below 50 when the ")
                .append("context does not actually cover the question.\n\n");

        sb.append("## Live server snapshot\n")
                .append("- Online players: ").append(Bukkit.getOnlinePlayers().size())
                .append('/').append(Bukkit.getMaxPlayers()).append('\n')
                .append("- Server uptime: ").append(formatUptime()).append('\n')
                .append("- Active plugins: ").append(Arrays.stream(Bukkit.getPluginManager().getPlugins())
                        .map(Plugin::getName).collect(Collectors.joining(", "))).append("\n\n");

        sb.append("## Knowledge base excerpts\n");
        if (chunks.isEmpty()) {
            sb.append("(no matching local documents)\n");
        } else {
            int used = 0;
            for (KnowledgeFeedIngestor.ScoredChunk scored : chunks) {
                String block = "[Source: " + scored.chunk().source() + "]\n" + scored.chunk().text() + "\n\n";
                if (used + block.length() > maxContextChars) break;
                sb.append(block);
                used += block.length();
            }
        }

        if (webContext != null && !webContext.isBlank()) {
            sb.append("\n## Verified web search context\n").append(webContext).append('\n');
        }

        return sb.toString();
    }

    private String formatUptime() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        long hours = uptimeMs / 3_600_000L;
        long minutes = (uptimeMs % 3_600_000L) / 60_000L;
        return hours + "h " + minutes + "m";
    }
}