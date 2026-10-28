package dev.actionable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the local Ollama server (Gemma) for a tactical decision without ever blocking the caller (FR-1).
 * Every failure mode - timeout over 100 ms, dropped connection, bad HTTP status, corrupt JSON - resolves
 * the future normally with a failed {@link Outcome} so the agent can fall back to a defensive hold (FR-10).
 */
final class GemmaClient {
    static final long TIMEOUT_MS = 100;
    private static final Gson GSON = new Gson();
    private static final Pattern LINE = Pattern.compile("line (\\d+)");
    private static final String SYSTEM = "You are the tactical brain of a Minecraft survival agent. Reply with JSON "
            + "only: {\"tactic\":\"<ONE_OF_ALLOWED>\",\"reason\":\"<short>\"}. Choose only from allowed_tactics.";

    record Decision(Tactic tactic, String reason) { }

    /** Exactly one of decision/failure is set. */
    record Outcome(Decision decision, String failure) {
        boolean ok() {
            return decision != null;
        }
    }

    private final HttpClient http;
    private final String model;

    GemmaClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build(),
                System.getProperty("actionable.gemma.model", System.getProperty("actionable.ollama.model", "gemma3:4b")));
    }

    GemmaClient(HttpClient http, String model) {
        this.http = http;
        this.model = model;
    }

    String requestBody(TacticalState state) {
        JsonObject user = new JsonObject();
        user.add("state", GSON.toJsonTree(state));
        user.add("allowed_tactics", GSON.toJsonTree(TacticBook.allowed(state.mob()).stream().map(Enum::name).toList()));
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", SYSTEM);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", user.toString());
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("format", "json");
        body.addProperty("keep_alive", System.getProperty("actionable.ollama.keepAlive", "30m"));
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(system);
        messages.add(message);
        body.add("messages", messages);
        return body.toString();
    }

    CompletableFuture<Outcome> decide(TacticalState state) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(GemmaConfig.chatUri())
                    .timeout(Duration.ofMillis(TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(state)))
                    .build();
        } catch (RuntimeException e) {
            GemmaLog.warn("request_build_failed " + ActionableLog.text(e.toString()));
            return CompletableFuture.completedFuture(new Outcome(null, "bad endpoint"));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    if (error != null) {
                        GemmaLog.warn("ollama_unavailable timeout_ms=" + TIMEOUT_MS + " cause="
                                + ActionableLog.text(String.valueOf(rootCause(error))));
                        return new Outcome(null, "ollama unavailable");
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        GemmaLog.warn("ollama_http_error status=" + response.statusCode());
                        return new Outcome(null, "http " + response.statusCode());
                    }
                    return parseResponse(response.body(), state.mob());
                });
    }

    /** Unwraps Ollama's chat envelope, then the decision inside it; logs and fails on anything malformed. */
    static Outcome parseResponse(String body, String mob) {
        String content;
        try {
            JsonObject envelope = strictObject(body);
            content = envelope.getAsJsonObject("message").get("content").getAsString();
        } catch (IOException | RuntimeException e) {
            GemmaLog.invalidPayload(body, errorLine(e), String.valueOf(e.getMessage()));
            return new Outcome(null, "malformed envelope");
        }
        return parseDecision(content, mob);
    }

    static Outcome parseDecision(String content, String mob) {
        try {
            JsonObject object = strictObject(content);
            Optional<Tactic> tactic = Tactic.parse(object.get("tactic").getAsString());
            if (tactic.isEmpty()) {
                GemmaLog.invalidPayload(content, 1, "unknown tactic " + object.get("tactic"));
                return new Outcome(null, "unknown tactic");
            }
            JsonElement reason = object.get("reason");
            return new Outcome(new Decision(tactic.get(), reason == null || reason.isJsonNull() ? ""
                    : reason.getAsString()), null);
        } catch (IOException | RuntimeException e) {
            GemmaLog.invalidPayload(content, errorLine(e), String.valueOf(e.getMessage()));
            return new Outcome(null, "malformed decision");
        }
    }

    private static JsonObject strictObject(String text) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(text == null ? "" : text));
        reader.setStrictness(Strictness.STRICT);
        JsonElement element = GSON.getAdapter(JsonElement.class).read(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IOException("trailing data at line " + 1);
        }
        if (element == null || !element.isJsonObject()) {
            throw new IOException("expected a JSON object at line 1");
        }
        return element.getAsJsonObject();
    }

    /** Gson reports "at line N column M"; the numbers are already 1-based. */
    static int errorLine(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            Matcher matcher = LINE.matcher(String.valueOf(cause.getMessage()));
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return 1;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
