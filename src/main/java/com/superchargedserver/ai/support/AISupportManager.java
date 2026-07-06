package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Coordinator for the generative AI support engine. Runs the full pipeline —
 * FAQ cache → local RAG retrieval → optional web enrichment → LLM call →
 * confidence validation — entirely on a dedicated daemon executor so the
 * main server thread is never touched by network or database IO.
 */
public class AISupportManager {

    private final SuperChargedServer plugin;
    @Getter
    private final KnowledgeFeedIngestor ingestor;
    @Getter
    private final LLMClientService llmClient;
    @Getter
    private final FAQCacheRepository faqCache;
    private final CognitivePromptBuilder promptBuilder;
    private final ConfidenceValidationEngine confidenceEngine;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "SuperCharged-AI-Support");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean avatarUsable;

    public AISupportManager(SuperChargedServer plugin) {
        this.plugin = plugin;
        this.ingestor = new KnowledgeFeedIngestor(plugin);
        this.llmClient = new LLMClientService(plugin);
        this.faqCache = new FAQCacheRepository(plugin);
        this.promptBuilder = new CognitivePromptBuilder(plugin);
        this.confidenceEngine = new ConfidenceValidationEngine(plugin);
    }

    private FileConfiguration cfg() {
        return plugin.getConfigManager().aiEngine();
    }

    public boolean isEnabled() {
        return cfg().getBoolean("ai-support.enabled", false);
    }

    public String displayName() {
        return cfg().getString("ai-support.ai-bot.display-name", "SuperCharged Assistant");
    }

    /** Avatar URL for webhook identity — empty when validation failed. */
    public String avatarUrl() {
        return avatarUsable ? cfg().getString("ai-support.ai-bot.avatar-url", "") : "";
    }

    public String fallbackMessage() {
        return cfg().getString("ai-support.fallback-message",
                "I could not find that in our knowledge base. Please contact a staff member for help!");
    }

    public void start() {
        if (!isEnabled()) {
            plugin.getLogger().info("AI support engine disabled (ai-support.enabled: false).");
            return;
        }
        faqCache.init();
        IdentityValidator.validateAsync(plugin,
                cfg().getString("ai-support.ai-bot.avatar-url", ""), ok -> avatarUsable = ok);
        ingestor.reloadAsync(executor, count -> plugin.getLogger().info(
                "AI support engine online — " + count + " knowledge chunks indexed from AI/feeds."));
    }

    public void reload() {
        if (!isEnabled()) return;
        avatarUsable = false;
        IdentityValidator.validateAsync(plugin,
                cfg().getString("ai-support.ai-bot.avatar-url", ""), ok -> avatarUsable = ok);
        ingestor.reloadAsync(executor, count -> plugin.getLogger().info(
                "AI knowledge feeds reloaded — " + count + " context chunks indexed."));
        executor.execute(faqCache::purgeExpired);
    }

    /**
     * Answers a support question asynchronously. The callback fires on the
     * AI executor thread — callers needing the main thread must hop back
     * via the Bukkit scheduler themselves.
     */
    public void answer(String question, Consumer<AIAnswer> callback) {
        if (!isEnabled()) {
            callback.accept(new AIAnswer(fallbackMessage(), 0, true, false));
            return;
        }
        executor.execute(() -> {
            try {
                FileConfiguration cfg = cfg();
                boolean cacheEnabled = cfg.getBoolean("ai-support.faq-cache.enabled", true);
                if (cacheEnabled) {
                    String cached = faqCache.lookup(question);
                    if (cached != null) {
                        callback.accept(new AIAnswer(cached, 100, false, true));
                        return;
                    }
                }

                int maxChunks = cfg.getInt("ai-support.feeds.max-context-chunks", 6);
                List<KnowledgeFeedIngestor.ScoredChunk> chunks = ingestor.retrieve(question, maxChunks);
                boolean allowInternet = cfg.getBoolean("ai-support.allow-internet-fetch", false);

                if (chunks.isEmpty() && !allowInternet) {
                    callback.accept(new AIAnswer(fallbackMessage(), 0, true, false));
                    return;
                }

                String webContext = null;
                double bestScore = chunks.isEmpty() ? 0.0 : chunks.get(0).score();
                if (allowInternet && bestScore < 0.35) {
                    try {
                        webContext = llmClient.webSearch(question).get(8, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }
                }

                String systemPrompt = promptBuilder.build(chunks, webContext, allowInternet);
                String raw;
                try {
                    raw = llmClient.chat(systemPrompt, question)
                            .get(cfg.getInt("ai-support.llm.timeout-seconds", 30) + 15L, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    plugin.getLogger().warning("AI support LLM call failed: " + ex.getMessage());
                    callback.accept(new AIAnswer(fallbackMessage(), 0, true, false));
                    return;
                }

                ConfidenceValidationEngine.ValidatedResponse validated = confidenceEngine.validate(raw);
                if (validated.escalate()) {
                    callback.accept(new AIAnswer("", validated.confidence(), true, false));
                    return;
                }
                if (cacheEnabled && validated.confidence() >= cfg.getInt("ai-support.confidence.high-threshold", 85)) {
                    faqCache.store(question, validated.text(), validated.confidence());
                }
                callback.accept(new AIAnswer(validated.text(), validated.confidence(), false, false));
            } catch (Exception ex) {
                plugin.getLogger().warning("AI support pipeline error: " + ex.getMessage());
                callback.accept(new AIAnswer(fallbackMessage(), 0, true, false));
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}