package com.superchargedserver.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Asynchronous external document ingestion for the embed builder dashboard.
 * Resolves direct .txt/.pdf links plus Google Drive, Dropbox and iCloud
 * sharing links into raw text, entirely off the Minecraft main thread on a
 * dedicated daemon worker pool. PDF text is extracted with a lightweight
 * pure-Java FlateDecode stream reader — no external dependencies.
 */
public final class ExternalFileIngestor {

    public record Result(boolean success, String text, String error) {
        static Result ok(String text) {
            return new Result(true, text, null);
        }

        static Result fail(String error) {
            return new Result(false, null, error);
        }
    }

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Pattern GDRIVE_FILE = Pattern.compile("drive\\.google\\.com/file/d/([A-Za-z0-9_-]+)");
    private static final Pattern GDRIVE_OPEN = Pattern.compile("drive\\.google\\.com/open\\?id=([A-Za-z0-9_-]+)");

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "SuperCharged-FileIngest");
        thread.setDaemon(true);
        return thread;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ExternalFileIngestor() {
    }

    private static final String LINK_COMMAND_PREFIX = "/scslink:";

    /** Strips the required {@code /scslink:} prefix from user input and returns the raw link, or null if the prefix is absent. */
    public static String stripLinkCommand(String input) {
        if (input == null) return null;
        String candidate = input.trim();
        if (!candidate.regionMatches(true, 0, LINK_COMMAND_PREFIX, 0, LINK_COMMAND_PREFIX.length())) return null;
        return candidate.substring(LINK_COMMAND_PREFIX.length()).trim();
    }

    /** True when the input is a {@code /scslink: <url>} command pointing at a .txt/.pdf or a supported cloud share link. */
    public static boolean isIngestableLink(String input) {
        String candidate = stripLinkCommand(input);
        if (candidate == null) return false;
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) return false;
        if (candidate.contains(" ") || candidate.contains("\n")) return false;
        String lower = stripQuery(candidate).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".pdf")) return true;
        return candidate.contains("drive.google.com/")
                || candidate.contains("dropbox.com/")
                || candidate.contains("icloud.com/");
    }

    /** Rewrites cloud sharing links into direct-download endpoints. */
    public static String rewriteUrl(String url) {
        Matcher file = GDRIVE_FILE.matcher(url);
        if (file.find()) {
            return "https://drive.google.com/uc?export=download&id=" + file.group(1);
        }
        Matcher open = GDRIVE_OPEN.matcher(url);
        if (open.find()) {
            return "https://drive.google.com/uc?export=download&id=" + open.group(1);
        }
        if (url.contains("dropbox.com/")) {
            if (url.contains("dl=0")) return url.replace("dl=0", "dl=1");
            if (url.contains("dl=1")) return url;
            return url + (url.contains("?") ? "&dl=1" : "?dl=1");
        }
        // iCloud share nodes resolve to the underlying asset through the
        // redirect chain, which the HTTP client follows automatically.
        return url;
    }

    /** Downloads and extracts off-thread; the callback fires on the ingest worker pool. */
    public static void ingestAsync(String url, Consumer<Result> callback) {
        EXECUTOR.execute(() -> callback.accept(ingest(url)));
    }

    private static Result ingest(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(rewriteUrl(url)))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "SuperChargedServer-Ingestor/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Result.fail("HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length == 0) return Result.fail("the file is empty");
            if (body.length > MAX_BYTES) return Result.fail("file exceeds the 5 MB ingestion cap");

            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            boolean pdf = isPdf(body) || contentType.contains("application/pdf")
                    || stripQuery(url).toLowerCase(Locale.ROOT).endsWith(".pdf");
            String text = pdf ? extractPdfText(body) : new String(body, StandardCharsets.UTF_8);
            text = normalize(text);
            if (text.isBlank()) {
                return Result.fail(pdf
                        ? "no extractable text found (image-only or unsupported PDF encoding)"
                        : "the document contains no readable text");
            }
            return Result.ok(text);
        } catch (Exception ex) {
            return Result.fail(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    /**
     * Splits text into a first chunk of {@code firstLimit} characters and
     * follow-up chunks of {@code restLimit}, breaking on word boundaries.
     */
    public static List<String> chunk(String text, int firstLimit, int restLimit) {
        List<String> chunks = new ArrayList<>();
        String remaining = text;
        int limit = firstLimit;
        while (!remaining.isEmpty()) {
            if (remaining.length() <= limit) {
                chunks.add(remaining);
                break;
            }
            int cut = remaining.lastIndexOf('\n', limit);
            if (cut < limit / 2) cut = remaining.lastIndexOf(' ', limit);
            if (cut < limit / 2) cut = limit;
            chunks.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
            limit = restLimit;
        }
        chunks.removeIf(String::isBlank);
        return chunks;
    }

    // ── PDF extraction ───────────────────────────────────────────────────

    private static boolean isPdf(byte[] body) {
        return body.length > 4 && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F';
    }

    /**
     * Lightweight pure-Java PDF text extraction: inflates FlateDecode content
     * streams and reads literal strings from BT..ET text blocks. Handles
     * standard-encoded PDFs without external dependencies; CID/Identity-H
     * encoded documents may yield no text.
     */
    private static String extractPdfText(byte[] data) {
        String raw = new String(data, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (true) {
            int streamAt = raw.indexOf("stream", cursor);
            if (streamAt < 0) break;
            int start = streamAt + 6;
            if (start < raw.length() && raw.charAt(start) == '\r') start++;
            if (start < raw.length() && raw.charAt(start) == '\n') start++;
            int end = raw.indexOf("endstream", start);
            if (end < 0) break;
            byte[] segment = Arrays.copyOfRange(data, start, end);
            String content = inflate(segment);
            if (content == null) content = new String(segment, StandardCharsets.ISO_8859_1);
            appendTextOps(content, out);
            cursor = end + 9;
        }
        return out.toString();
    }

    private static String inflate(byte[] segment) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(segment);
            StringBuilder sb = new StringBuilder();
            byte[] buffer = new byte[8192];
            int total = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) break;
                total += n;
                if (total > MAX_BYTES * 4) return null;
                sb.append(new String(buffer, 0, n, StandardCharsets.ISO_8859_1));
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception ex) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /** Pulls literal strings shown inside BT..ET text blocks, tracking line operators. */
    private static void appendTextOps(String content, StringBuilder out) {
        int cursor = 0;
        while (true) {
            int bt = content.indexOf("BT", cursor);
            if (bt < 0) return;
            int et = content.indexOf("ET", bt + 2);
            if (et < 0) return;
            String block = content.substring(bt + 2, et);
            for (int i = 0; i < block.length(); i++) {
                char c = block.charAt(i);
                if (c == '(') {
                    i = readLiteral(block, i, out);
                } else if (c == 'T' && i + 1 < block.length()) {
                    char op = block.charAt(i + 1);
                    if ((op == '*' || op == 'd' || op == 'D')
                            && out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                        out.append('\n');
                    }
                }
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            cursor = et + 2;
        }
    }

    private static int readLiteral(String block, int open, StringBuilder out) {
        int depth = 1;
        int i = open + 1;
        while (i < block.length() && depth > 0) {
            char c = block.charAt(i);
            if (c == '\\') {
                if (i + 1 < block.length()) {
                    char next = block.charAt(i + 1);
                    switch (next) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r', 'f', 'b' -> out.append(' ');
                        case '(', ')', '\\' -> out.append(next);
                        default -> {
                            if (Character.isDigit(next)) {
                                int len = 1;
                                while (len < 3 && i + 1 + len < block.length()
                                        && Character.isDigit(block.charAt(i + 1 + len))) len++;
                                try {
                                    out.append((char) Integer.parseInt(block.substring(i + 1, i + 1 + len), 8));
                                } catch (NumberFormatException ignored) {
                                }
                                i += len - 1;
                            }
                        }
                    }
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) break;
            }
            out.append(c);
            i++;
        }
        return i;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int f = url.indexOf('#');
        if (f >= 0) url = url.substring(0, f);
        return url;
    }
}