package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Fully asynchronous HTTP client for the configured LLM backend. Supports
 * OpenAI, Anthropic, and OpenAI-compatible local hosts (Ollama / LM Studio),
 * with graceful 429 rate-limit retries and hard request timeouts. Zero calls
 * ever touch the main server thread.
 */
public class LLMClientService {

    private final SuperChargedServer plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LLMClientService(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfigManager().aiEngine();
    }

    public CompletableFuture<String> chat(String systemPrompt, String userMessage) {
        FileConfiguration cfg = cfg();
        String provider = cfg.getString("ai-support.llm.provider", "openai").toLowerCase(Locale.ROOT);
        String apiKey = cfg.getString("ai-support.llm.api-key", "");
        String model = cfg.getString("ai-support.llm.model", "gpt-4o");
        String endpoint = cfg.getString("ai-support.llm.endpoint", "");
        int maxTokens = cfg.getInt("ai-support.llm.max-tokens", 900);
        double temperature = cfg.getDouble("ai-support.llm.temperature", 0.4);
        int timeoutSeconds = cfg.getInt("ai-support.llm.timeout-seconds", 30);

        String url;
        String body;
        HttpRequest.Builder builder;
        if ("anthropic".equals(provider)) {
            url = endpoint.isBlank() ? "https://api.anthropic.com/v1/messages" : endpoint;
            body = "{\"model\":\"" + esc(model) + "\",\"max_tokens\":" + maxTokens
                    + ",\"temperature\":" + temperature
                    + ",\"system\":\"" + esc(systemPrompt) + "\""
                    + ",\"messages\":[{\"role\":\"user\",\"content\":\"" + esc(userMessage) + "\"}]}";
            builder = HttpRequest.newBuilder(URI.create(url))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01");
        } else {
            url = !endpoint.isBlank() ? endpoint
                    : "ollama".equals(provider) ? "http://localhost:11434/v1/chat/completions"
                    : "https://api.openai.com/v1/chat/completions";
            body = "{\"model\":\"" + esc(model) + "\",\"max_tokens\":" + maxTokens
                    + ",\"temperature\":" + temperature
                    + ",\"messages\":[{\"role\":\"system\",\"content\":\"" + esc(systemPrompt)
                    + "\"},{\"role\":\"user\",\"content\":\"" + esc(userMessage) + "\"}]}";
            builder = HttpRequest.newBuilder(URI.create(url));
            if (!apiKey.isBlank() && !apiKey.startsWith("PASTE-")) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
        }

        HttpRequest request = builder
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return sendWithRetry(request, 0).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new CompletionException(new IOException("LLM API returned HTTP " + response.statusCode()));
            }
            String content = "anthropic".equals(provider)
                    ? extractStringField(response.body(), "\"content\"", "text")
                    : extractStringField(response.body(), "\"message\"", "content");
            if (content == null || content.isBlank()) {
                throw new CompletionException(new IOException("LLM API returned an empty completion"));
            }
            return content;
        });
    }

    private CompletableFuture<HttpResponse<String>> sendWithRetry(HttpRequest request, int attempt) {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if (response.statusCode() == 429 && attempt < 2) {
                long delayMs = response.headers().firstValueAsLong("Retry-After").orElse(2) * 1000L;
                plugin.getLogger().warning("LLM API rate limited — retrying in " + delayMs + "ms.");
                return CompletableFuture.supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
                        .thenCompose(x -> sendWithRetry(request, attempt + 1));
            }
            return CompletableFuture.completedFuture(response);
        });
    }

    /**
     * Hybrid web search enrichment via the DuckDuckGo instant answer API.
     * Only invoked when allow-internet-fetch is true and local RAG retrieval
     * produced a weak match. Completes with null on any failure.
     */
    public CompletableFuture<String> webSearch(String query) {
        try {
            String url = "https://api.duckduckgo.com/?format=json&no_html=1&q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                if (response.statusCode() != 200) return null;
                String abstractText = extractStringField(response.body(), null, "AbstractText");
                if (abstractText != null && !abstractText.isBlank()) return abstractText;
                String answer = extractStringField(response.body(), null, "Answer");
                return answer != null && !answer.isBlank() ? answer : null;
            }).exceptionally(ex -> null);
        } catch (Exception ex) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private String esc(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Extracts and decodes the first JSON string field after an optional anchor. */
    private String extractStringField(String json, String anchor, String field) {
        int start = 0;
        if (anchor != null) {
            start = json.indexOf(anchor);
            if (start < 0) start = 0;
        }
        int idx = json.indexOf("\"" + field + "\"", start);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + field.length() + 2);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                char escape = json.charAt(i);
                switch (escape) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(escape);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }
}