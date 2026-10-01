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
    private static final String SYSTEM_PROMPT = """
            You plan Minecraft tasks and can issue one next action per turn.
            Coordinates are integer block positions x,y,z. Minecraft convention: +X is east, +Z is south,
            +Y is up; -X west and -Z north. Yaw 0 faces +Z (south), yaw 90 faces -X (west),
            and yaw -90 faces +X (east).
            The world synopsis includes exact position, yaw/pitch, target looked at, biome, loaded nearby
            biome samples, sampled blocks, nearby resources, valid placement candidates and inventory.
            Biome sample vectors are world dx,0,dz; angle_from_facing is the signed angle from current facing.
            Never convert directions to coordinates from memory: calculate from current x,z and use a three-
            integer coordinate only for goto.

            Baritone commands have these exact forms:
            - mine <block_id> [quantity]: searches loaded chunks, navigates to matching blocks, and keeps
              mining until the quantity is reached or the command is replaced. No coordinates or extra args.
            - goto <x> <y> <z>: exactly three integer coordinates; goto does not accept block IDs.
            - explore: no arguments; continues toward the nearest unexplored/unloaded region.
            - build <schematic_filename>: existing schematic only; never a block/item ID.
            Never emit coordinates after mine and never emit a block ID to goto.
            Other approved commands use their normal Baritone syntax, but do not invent arguments.

            The latest observation includes Baritone's currently executing command, elapsed time, player movement,
            distance to a goto target where applicable, and recent Baritone feedback. If it is making
            useful progress, return action type "wait" to leave the current command running. If progress stalls,
            the environment changes, or the current command is not appropriate, issue a different valid
            Baritone command to replace it. Do not resend an already-running command. Explore may be left
            running while checking newly loaded biome/world information.

            Before building or placing, compare required items to inventory. Gather missing source materials
            first with mine and use the inventory/world facts; do not try to place an item that is unavailable.
            This version cannot craft items or create schematic files. It can place one held/inventory block;
            use "place_block" for a nearby valid surface and "jump_place" only when placing into the player's
            current feet cell. jump_place jumps one block and places at the original feet cell after clearing
            replaceable flowers/plants. If a crafting recipe is needed, use lookup to inspect known recipe
            information, but state when crafting itself is unavailable.
            Use "lookup" with a short search query to find registered block/item IDs or player-known recipes.
            Lookup results are local game data and will be supplied in the next turn. Do not invent global
            biome/dimension/ore-generation ranges; only report nearby loaded observations in the synopsis.
            Prioritize immediate hostile threats and low health; there is no combat action.

            Return only JSON:
            {"summary":"short status","goal":"current subgoal","complete":false,
             "action":{"type":"baritone","command":"mine minecraft:oak_log 4"}}
            or {"summary":"still moving","goal":"reach destination","complete":false,
                "action":{"type":"wait"}}
            or {"summary":"place block","goal":"place table","complete":false,
                "action":{"type":"place_block","block":"minecraft:crafting_table","x":-11,"y":120,"z":15}}
            or {"summary":"jump and place","goal":"place below player","complete":false,
                "action":{"type":"jump_place","block":"minecraft:crafting_table","x":-11,"y":120,"z":15}}
            or {"summary":"identify item","goal":"look up crafting table","complete":false,
                "action":{"type":"lookup","query":"crafting table"}}
            Return complete=true only when the entire user task is actually done. For a blocker, return action
            omitted and explain the reason. Never claim an action succeeded before its result is observed.
            """;
    private static final String DEFAULT_ENDPOINT = "http://localhost:11434/api/chat";

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
                            .map(command -> new PlannedAction("baritone", command, "", 0, 0, 0, ""));
                }
            }
        }
        return new ActionPlan(summary, goal, complete, action);
    }

    private static Optional<PlannedAction> parseAction(JsonObject action) {
        String type = action.has("type") ? action.get("type").getAsString() : "baritone";
        if (type.equals("wait")) {
            return Optional.of(new PlannedAction("wait", "", "", 0, 0, 0, ""));
        }
        if (type.equals("lookup") && action.has("query")) {
            String query = cleanText(action, "query", "", 80);
            return query.isBlank() ? Optional.empty()
                    : Optional.of(new PlannedAction("lookup", "", "", 0, 0, 0, query));
        }
        if ((type.equals("place_block") || type.equals("jump_place")) && action.has("block")
                && action.has("x") && action.has("y") && action.has("z")) {
            String block = action.get("block").getAsString();
            if (block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                return Optional.of(new PlannedAction(type, "", block,
                        action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt(), ""));
            }
        }
        if (type.equals("baritone") && action.has("command")) {
            return validateCommand(action.get("command").getAsString())
                    .map(command -> new PlannedAction("baritone", command, "", 0, 0, 0, ""));
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
        String[] parts = normalized.split("\\s+");
        String verb = parts[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED_COMMANDS.contains(verb)) {
            return java.util.Optional.empty();
        }
        switch (verb) {
            case "mine" -> {
                if ((parts.length < 2 || parts.length > 3)
                        || !parts[1].matches("[a-zA-Z0-9_.-]+(?::[a-zA-Z0-9_./-]+)?")
                        || (parts.length == 3 && (!parts[2].matches("\\d{1,4}")
                        || Integer.parseInt(parts[2]) <= 0 || Integer.parseInt(parts[2]) > 4096))) {
                    return Optional.empty();
                }
            }
            case "goto" -> {
                if (parts.length != 4 || !parts[1].matches("-?\\d+")
                        || !parts[2].matches("-?\\d+") || !parts[3].matches("-?\\d+")) {
                    return Optional.empty();
                }
            }
            case "explore", "come", "cancel" -> {
                if (parts.length != 1) {
                    return Optional.empty();
                }
            }
            case "build" -> {
                if (parts.length != 2
                        || !parts[1].matches("[A-Za-z0-9_.-]+\\.(?i:schematic|schem|litematic)")) {
                    return Optional.empty();
                }
            }
            case "find", "pickup", "farm", "follow" -> {
                if (parts.length < 2) {
                    return Optional.empty();
                }
            }
            default -> {
                return Optional.empty();
            }
        }
        return Optional.of(normalized);
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

    record PlannedAction(String type, String command, String block, int x, int y, int z, String query) {
    }

    record ActionPlan(String summary, String goal, boolean complete, Optional<PlannedAction> action) {
    }
}
