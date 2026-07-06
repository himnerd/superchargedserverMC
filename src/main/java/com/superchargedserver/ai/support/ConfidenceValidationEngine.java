package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the raw LLM payload, extracts the mandatory confidence indicator,
 * strips it from the user-facing text, and applies the three-tier policy:
 * high = full answer, mid = answer + partial-data warning footer,
 * low = skip generation entirely and escalate to human staff.
 */
public class ConfidenceValidationEngine {

    private static final Pattern CONFIDENCE_LINE =
            Pattern.compile("(?im)^\\s*\\[?confidence:\\s*(\\d{1,3})\\s*%?]?\\s*$");

    public record ValidatedResponse(String text, int confidence, boolean escalate) {
    }

    private final SuperChargedServer plugin;

    public ConfidenceValidationEngine(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public ValidatedResponse validate(String raw) {
        FileConfiguration cfg = plugin.getConfigManager().aiEngine();
        int high = cfg.getInt("ai-support.confidence.high-threshold", 85);
        int low = cfg.getInt("ai-support.confidence.low-threshold", 50);
        String footer = cfg.getString("ai-support.confidence.warning-footer",
                "⚠️ This solution is generated based on partial data matches. If this does not resolve "
                        + "your issue, please click the human support button below.");

        String text = raw == null ? "" : raw.trim();
        int confidence = 60;

        Matcher matcher = CONFIDENCE_LINE.matcher(text);
        int lastStart = -1;
        int lastEnd = -1;
        int lastValue = -1;
        while (matcher.find()) {
            lastValue = Integer.parseInt(matcher.group(1));
            lastStart = matcher.start();
            lastEnd = matcher.end();
        }
        if (lastValue >= 0) {
            confidence = Math.min(100, lastValue);
            text = (text.substring(0, lastStart) + text.substring(lastEnd)).trim();
        }

        if (confidence < low || text.isEmpty()) {
            return new ValidatedResponse("", confidence, true);
        }
        if (confidence <= high) {
            return new ValidatedResponse(text + "\n\n" + footer, confidence, false);
        }
        return new ValidatedResponse(text, confidence, false);
    }
}