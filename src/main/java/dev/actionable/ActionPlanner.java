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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ActionPlanner {
    private static final int MAX_COMMAND_LENGTH = 256;
    private static final Set<String> ALLOWED_COMMANDS =
            Set.of("mine", "goto", "build", "explore", "find", "pickup", "farm", "cancel", "come", "follow");
    private static final Set<String> COMMANDS_REQUIRING_ARGUMENTS =
            Set.of("mine", "goto", "build", "find", "pickup", "farm", "follow");
    private static final String DEFAULT_ENDPOINT = "http://localhost:11434/api/chat";
    private static final String SYSTEM_PROMPT = """
            You are an execution planner for Minecraft. The world synopsis gives player position, facing,
            aimed-at block, inventory, nearby blocks and candidate surfaces. Coordinates are x,y,z in blocks.
            Nearby biome vectors are relative to the player; angle_from_facing is signed degrees from current
            facing (0 is straight ahead). Only listed nearby_loaded_biomes_sampled are known; do not infer
            unobserved terrain or resources.
            Before acting, compare the goal's required items/materials to inventory. If missing, plan to gather
            the required source blocks first with Baritone mine, using nearby sampled blocks and loaded biomes
            as evidence. For wood recipes, gather logs before asking for planks or a crafting table. Do not
            attempt final placement while the required block is missing. This mod cannot craft items or make
            schematics yet; state the blocker instead of claiming the craft succeeded.
            Choose one achievable next step, report it as goal, and issue exactly one action.
            Baritone goto requires three numeric coordinates: "goto x y z". Never output bare "goto".
            Baritone build accepts an existing schematic filename only; it does not place individual
            blocks. Use only filenames ending in .schematic, .schem, or .litematic; never use build with a
            block/item name. To place one block, use a place_block action with its minecraft: block id and
            exact x,y,z target. Only request blocks shown in the hotbar or inventory. Choose a target from
            nearby_placement_candidates with a replaceable target, clear overhead space and a sturdy ground block.
            Never place inside the player's occupied cell. For a replaceable flower/plant at the target, Actionable
            will clear it and ask for another plan before placing. If the target is not safely reachable,
            navigate close first. Do not repeat an action after its reported failure.
            Prioritize immediate danger: nearby hostile mobs marked as targeting the player or low health
            should take precedence; combat commands are not available.
            Return only JSON: {"summary":"short status","goal":"current subgoal","complete":false,
            "action":{"type":"baritone","command":"goto -11 120 15"}}
            or {"summary":"short status","goal":"place crafting table","complete":false,
            "action":{"type":"place_block","block":"minecraft:crafting_table","x":-11,"y":120,"z":15}}.
            Baritone command allowlist: mine, goto, build, explore, find, pickup, farm, cancel, come, follow.
            Return complete=true with action omitted only when the user's entire task is truly done.
            Do not invent inventory, location, block, or command result facts. If blocked, report why in summary
            and choose no action rather than repeating a failing action.
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

    CompletableFuture<ActionPlan> plan(String task, String synopsis, String lastAction, String lastResult) {
        return CompletableFuture.supplyAsync(() -> requestPlan(task, synopsis, lastAction, lastResult), executor);
    }

    private ActionPlan requestPlan(String task, String synopsis, String lastAction, String lastResult) {
        String endpoint = System.getProperty("actionable.ollama.url", DEFAULT_ENDPOINT);
        String model = System.getProperty("actionable.ollama.model", "gemma3:4b");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("format", "json");

        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", "Task: " + task + "\nPrevious action: "
                + (lastAction.isEmpty() ? "none" : lastAction) + "\nPrevious action result: "
                + (lastResult.isEmpty() ? "none" : lastResult) + "\nLatest world state: " + synopsis));
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
        String summary = cleanText(root, "summary", "No summary provided", 200);
        String goal = cleanText(root, "goal", "Choosing next step", 120);
        boolean complete = root.has("complete") && root.get("complete").getAsBoolean();
        Optional<PlannedAction> action = Optional.empty();
        JsonElement actionElement = root.get("action");
        if (actionElement != null && actionElement.isJsonObject()) {
            action = parseAction(actionElement.getAsJsonObject());
        } else if (root.has("actions") && root.get("actions").isJsonArray()
                && !root.getAsJsonArray("actions").isEmpty()) {
            JsonElement legacyAction = root.getAsJsonArray("actions").get(0);
            if (legacyAction.isJsonObject()) {
                JsonObject legacyObject = legacyAction.getAsJsonObject();
                if (legacyObject.has("command")) {
                    action = validateCommand(legacyObject.get("command").getAsString())
                            .map(command -> new PlannedAction("baritone", command, "", 0, 0, 0));
                }
            }
        }
        return new ActionPlan(summary, goal, complete, action);
    }

    private static Optional<PlannedAction> parseAction(JsonObject action) {
        String type = action.has("type") ? action.get("type").getAsString() : "baritone";
        if (type.equals("baritone") && action.has("command")) {
            return validateCommand(action.get("command").getAsString())
                    .map(command -> new PlannedAction("baritone", command, "", 0, 0, 0));
        }
        if (type.equals("place_block") && action.has("block")
                && action.has("x") && action.has("y") && action.has("z")) {
            String block = action.get("block").getAsString();
            if (block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                return Optional.of(new PlannedAction("place_block", "", block,
                        action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt()));
            }
        }
        return Optional.empty();
    }

    private static java.util.Optional<String> validateCommand(String command) {
        String normalized = command.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_COMMAND_LENGTH
                || normalized.startsWith("#") || normalized.startsWith("/")
                || normalized.chars().anyMatch(Character::isISOControl)) {
            return java.util.Optional.empty();
        }
        String[] parts = normalized.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED_COMMANDS.contains(verb)
                || (COMMANDS_REQUIRING_ARGUMENTS.contains(verb)
                && (parts.length < 2 || parts[1].isBlank()))) {
            return java.util.Optional.empty();
        }
        if (verb.equals("goto") && !parts[1].matches("-?\\d+\\s+-?\\d+\\s+-?\\d+")) {
            return java.util.Optional.empty();
        }
        if (verb.equals("build")
                && !parts[1].matches("[A-Za-z0-9_.-]+\\.(?i:schematic|schem|litematic)")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(normalized);
    }

    private static String cleanText(JsonObject root, String key, String fallback, int maxLength) {
        String value = root.has(key) ? root.get(key).getAsString() : fallback;
        return value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(maxLength)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
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

    record PlannedAction(String type, String command, String block, int x, int y, int z) {
    }

    record ActionPlan(String summary, String goal, boolean complete, Optional<PlannedAction> action) {
    }
}
