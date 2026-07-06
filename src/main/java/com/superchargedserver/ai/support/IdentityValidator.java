package com.superchargedserver.ai.support;

import com.superchargedserver.SuperChargedServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Non-blocking startup validation of configured asset links. Fires an HTTP
 * HEAD request against the avatar URL and only approves it when the remote
 * host answers 200 OK with an image/* content type — preventing webhook
 * execution crashes from dead links.
 */
public final class IdentityValidator {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private IdentityValidator() {
    }

    public static void validateAsync(SuperChargedServer plugin, String url, Consumer<Boolean> callback) {
        if (url == null || url.isBlank()) {
            callback.accept(false);
            return;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(8))
                    .build();
        } catch (Exception ex) {
            warn(plugin);
            callback.accept(false);
            return;
        }
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            boolean usable = error == null
                    && response.statusCode() == 200
                    && response.headers().firstValue("Content-Type")
                            .map(type -> type.toLowerCase(Locale.ROOT).startsWith("image/"))
                            .orElse(false);
            if (!usable) warn(plugin);
            callback.accept(usable);
        });
    }

    private static void warn(SuperChargedServer plugin) {
        plugin.getLogger().warning("[SuperCharged Configuration Warning] The configured AI bot avatar URL "
                + "is unreachable. Defaulting to fallback Discord assets.");
    }
}