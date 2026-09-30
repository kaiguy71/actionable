package dev.actionable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ActionPlanner {
    private static final int MAX_ACTIONS = 1;
    private static final int MAX_COMMAND_LENGTH = 256;
    private static final Set<String> ALLOWED_COMMANDS =
            Set.of("mine", "goto", "build", "explore", "find", "pickup", "farm", "cancel", "come", "follow");
    private static final String DEFAULT_ENDPOINT = "http://localhost:11434/api/chat";
    private static final String SYSTEM_PROMPT = """
            You plan Minecraft tasks for Baritone. Use the compact world synopsis and the user's goal.
            Choose one next action based on the latest world state; the plan will be requested again as the
            player moves or the surroundings change. Use mine to gather resources. Baritone build requires
            an existing schematic; do not claim to craft tools or create schematics.
            Prioritize immediate danger: nearby hostile mobs marked as targeting the player or low health
            should take precedence over the task. Use only navigation or cancellation to respond; combat
            commands are not available.
            Return only JSON: {"summary":"brief next-step explanation","complete":false,"actions":[{"command":"mine stone"}]}.
            Commands are sent to Baritone and must begin with one of: mine, goto, build, explore, find, pickup, farm, cancel, come, follow.
            Return at most one short action. Do not invent inventory or world facts. If uncertain, return no actions.
            Set complete to true only when the requested task is finished.
            """;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "actionable-planner");
        thread.setDaemon(true);
        return thread;
    });

    CompletableFuture<ActionPlan> plan(String task, String synopsis, String lastAction) {
        return CompletableFuture.supplyAsync(() -> requestPlan(task, synopsis, lastAction), executor);
    }

    private ActionPlan requestPlan(String task, String synopsis, String lastAction) {
        String endpoint = System.getProperty("actionable.ollama.url", DEFAULT_ENDPOINT);
        String model = System.getProperty("actionable.ollama.model", "gemma3:4b");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("format", "json");

        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", "Task: " + task + "\nPrevious action: "
                + (lastAction.isEmpty() ? "none" : lastAction) + "\nWorld: " + synopsis));
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
            }
            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = responseJson.getAsJsonObject("message").get("content").getAsString();
            return parsePlan(content);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not get a plan from Ollama: " + safeMessage(exception), exception);
        }
    }

    static ActionPlan parsePlan(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String summary = root.has("summary") ? root.get("summary").getAsString() : "No summary provided";
        summary = summary.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(200)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        JsonArray actions = root.has("actions") && root.get("actions").isJsonArray()
                ? root.getAsJsonArray("actions") : new JsonArray();
        List<String> safeActions = new ArrayList<>();
        for (JsonElement element : actions) {
            if (safeActions.size() == MAX_ACTIONS) {
                break;
            }
            if (element.isJsonObject() && element.getAsJsonObject().has("command")) {
                validateCommand(element.getAsJsonObject().get("command").getAsString())
                        .ifPresent(safeActions::add);
            }
        }
        boolean complete = root.has("complete") && root.get("complete").getAsBoolean();
        return new ActionPlan(summary, complete, List.copyOf(safeActions));
    }

    private static java.util.Optional<String> validateCommand(String command) {
        String normalized = command.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_COMMAND_LENGTH
                || normalized.startsWith("#") || normalized.startsWith("/")
                || normalized.chars().anyMatch(Character::isISOControl)) {
            return java.util.Optional.empty();
        }
        String verb = normalized.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return ALLOWED_COMMANDS.contains(verb) ? java.util.Optional.of(normalized) : java.util.Optional.empty();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    static String safeMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    record ActionPlan(String summary, boolean complete, List<String> actions) {
    }
}
