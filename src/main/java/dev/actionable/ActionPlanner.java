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
    static final int MAX_STEPS = 8;
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
            - mine <quantity> <block_id>: searches loaded chunks, navigates to matching blocks, and keeps
              mining until the quantity is reached or the command is replaced. Quantity comes first; no
              coordinates or extra args.
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
            Basic melee combat is available: {"type":"attack","entity_id":123} sends one hit to a
            live hostile mob identified in nearby_hostile_mobs. Only attack targets marked melee_reachable=true.
            Attacks respect weapon cooldown and line of sight. No ranged attacks or combat pathfinding.
            Never target players or neutral mobs. Escape creepers and flee when health is 6 or lower.
            Observe target health/death before claiming damage or kills; repeat attack if still needed.

            Return only JSON:
            {"summary":"short status","goal":"current subgoal","complete":false,
             "action":{"type":"baritone","command":"mine 4 minecraft:oak_log"}}
            or {"summary":"still moving","goal":"reach destination","complete":false,
                "action":{"type":"wait"}}
            or {"summary":"place block","goal":"place table","complete":false,
                "action":{"type":"place_block","block":"minecraft:crafting_table","x":-11,"y":120,"z":15}}
            or {"summary":"jump and place","goal":"place below player","complete":false,
                "action":{"type":"jump_place","block":"minecraft:crafting_table","x":-11,"y":120,"z":15}}
            or {"summary":"identify item","goal":"look up crafting table","complete":false,
                "action":{"type":"lookup","query":"crafting table"}}
            or {"summary":"step objective met","goal":"move to the next step","complete":false,
                "step_complete":true,"action":{"type":"wait"}}
            Return complete=true only when the entire user task is actually done. For a blocker, return action
            omitted and explain the reason. Never claim an action succeeded before its result is observed.

            You are given a numbered plan for the overall task and the one step you are working on now.
            Work only on the current step; do not skip ahead to later steps. Recent action history is
            supplied so you can see what has already been tried - never repeat an action that history
            shows already succeeded, and do not retry an identical failing action more than twice.
            Before choosing an action, check whether the CURRENT STEP is already satisfied by the
            inventory and the action history. If it is already satisfied, you MUST return
            "step_complete":true so the next step begins - do not return "wait" and do not re-run an
            action whose objective is already met. For example, if the current step is
            "mine 4 minecraft:oak_log" and the inventory already holds 4 oak logs, return
            "step_complete":true. Otherwise omit step_complete or set it to false.
            Use complete=true only when the final step is finished.
            """;

    private static final String DECOMPOSE_PROMPT = """
            You break a Minecraft task into an ordered plan of concrete, verifiable steps.
            Each step must be achievable with these abilities only: Baritone navigation and mining
            (mine, goto, explore, find, pickup, farm, follow), placing a single block from inventory,
            close-range melee against hostile mobs, and looking up block/item IDs and known recipes. Crafting and schematic creation are NOT
            available, so never emit a step that requires crafting or building from a schematic.
            Gather prerequisites before they are needed: to place blocks you must first mine them.
            State quantities and concrete block IDs where known, for example "mine 4 minecraft:oak_log".
            Use the fewest steps that actually accomplish the task, at most 8.
            Return only JSON: {"steps":["mine 4 minecraft:oak_log","goto the clearing at 10 70 -5"]}
            """;
    private static final String JUDGE_PROMPT = """
            You judge whether one Minecraft objective is already satisfied.
            You are given the objective, the player's inventory, and what was recently done.
            Answer only from that evidence. If the objective names a quantity of an item, it is
            satisfied only when the inventory holds at least that quantity.
            Return only JSON: {"done":true,"why":"short reason"} or {"done":false,"why":"short reason"}
            """;
    private static final String DIRECTOR_PROMPT = """
            You decide what a Minecraft player should do next, with no human giving instructions.
            You are given the world state, goals already achieved, and goals recently attempted.
            Choose ONE short objective that makes concrete progress and is achievable right now with
            these abilities only: Baritone navigation and mining (mine, goto, explore, find, pickup,
            farm, follow), placing a single block from inventory, close-range melee against hostile mobs,
            and looking up IDs and known recipes.
            Crafting and schematic building are NOT available, so never choose a goal that needs them.
            Prefer the natural progression: gather wood, then stone, then coal, then iron ore.
            Survival comes first: escape at health 6 or lower and avoid creepers. Healthy players can
            fight a nearby hostile mob marked melee_reachable=true; do not plan combat against distant mobs.
            Never repeat a goal listed as already achieved, and do not repeat a recently attempted goal
            unless the state shows it clearly failed and is still worth doing.
            Name concrete quantities and block IDs, for example "mine 16 minecraft:oak_log".
            Return only JSON: {"goal":"mine 16 minecraft:oak_log","why":"short reason"}
            """;
    private static final String DEFAULT_ENDPOINT = "http://localhost:11434/api/chat";
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_IDS = new java.util.concurrent.atomic.AtomicLong();

    private <T> T loggedRequest(String stage, java.util.function.Supplier<T> request) {
        long id = REQUEST_IDS.incrementAndGet();
        long started = System.nanoTime();
        String provider = System.getProperty("actionable.model.provider", "ollama");
        String model = provider.equalsIgnoreCase("claude")
                ? System.getProperty("actionable.claude.model", "claude-sonnet-5-5")
                : System.getProperty("actionable.ollama.model", "gemma3:4b");
        ActionableLog.LOGGER.info("[Actionable] model_request id={} stage={} provider={} model={}",
                id, stage, ActionableLog.text(provider), ActionableLog.text(model));
        try {
            T result = request.get();
            ActionableLog.LOGGER.info("[Actionable] model_response id={} stage={} elapsed_ms={}",
                    id, stage, (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (RuntimeException exception) {
            ActionableLog.LOGGER.warn("[Actionable] model_failure id={} stage={} elapsed_ms={} error={}",
                    id, stage, (System.nanoTime() - started) / 1_000_000,
                    ActionableLog.text(safeMessage(exception)));
            throw exception;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "actionable-planner");
        thread.setDaemon(true);
        return thread;
    });

    CompletableFuture<ActionPlan> plan(String task, String synopsis, String lastAction, String lastResult,
            String planContext, String history) {
        return CompletableFuture.supplyAsync(
                () -> loggedRequest("action", () -> requestPlan(task, synopsis, lastAction, lastResult, planContext, history)), executor);
    }

    CompletableFuture<java.util.List<String>> decompose(String task, String synopsis) {
        return CompletableFuture.supplyAsync(() -> loggedRequest("decomposition", () -> requestSteps(task, synopsis)), executor);
    }

    CompletableFuture<String> chooseGoal(String synopsis, String achieved, String attempted) {
        return CompletableFuture.supplyAsync(() -> loggedRequest("director", () -> requestGoal(synopsis, achieved, attempted)), executor);
    }

    private String requestGoal(String synopsis, String achieved, String attempted) {
        String endpoint = System.getProperty("actionable.ollama.url", DEFAULT_ENDPOINT);
        String model = System.getProperty("actionable.ollama.model", "gemma3:4b");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("format", "json");

        JsonArray messages = new JsonArray();
        messages.add(message("system", DIRECTOR_PROMPT));
        messages.add(message("user", "Goals already achieved: " + (achieved.isBlank() ? "none" : achieved)
                + "\nGoals recently attempted: " + (attempted.isBlank() ? "none" : attempted)
                + "\nCurrent world state: " + synopsis));
        body.add("messages", messages);

        JsonObject chosen = JsonParser.parseString(send(endpoint, body)).getAsJsonObject();
        String goal = cleanText(chosen, "goal", "", 120).strip();
        if (goal.isBlank()) {
            throw new IllegalStateException("Director returned no goal");
        }
        return goal;
    }

    CompletableFuture<Boolean> checkStep(String objective, String inventory, String recent) {
        return CompletableFuture.supplyAsync(() -> loggedRequest("judge", () -> requestStepVerdict(objective, inventory, recent)), executor);
    }

    private boolean requestStepVerdict(String objective, String inventory, String recent) {
        String endpoint = System.getProperty("actionable.ollama.url", DEFAULT_ENDPOINT);
        String model = System.getProperty("actionable.ollama.model", "gemma3:4b");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("format", "json");

        JsonArray messages = new JsonArray();
        messages.add(message("system", JUDGE_PROMPT));
        messages.add(message("user", "Objective: " + objective + "\nInventory: "
                + (inventory.isBlank() ? "unknown" : inventory)
                + "\nRecently done: " + (recent.isBlank() ? "none" : recent)));
        body.add("messages", messages);

        JsonObject verdict = JsonParser.parseString(send(endpoint, body)).getAsJsonObject();
        return verdict.has("done") && verdict.get("done").getAsBoolean();
    }

    private ActionPlan requestPlan(String task, String synopsis, String lastAction, String lastResult,
            String planContext, String history) {
        String endpoint = System.getProperty("actionable.ollama.url", DEFAULT_ENDPOINT);
        String model = System.getProperty("actionable.ollama.model", "gemma3:4b");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("format", "json");

        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", "Task: " + task + "\n" + planContext
                + "\nRecent action history (oldest first): " + (history.isEmpty() ? "none" : history)
                + "\nPrevious action: "
                + (lastAction.isEmpty() ? "none" : lastAction) + "\nPrevious action result: "
                + (lastResult.isEmpty() ? "none" : lastResult) + "\nLatest world state: " + synopsis));
        body.add("messages", messages);

        return parsePlan(send(endpoint, body));
    }

    private java.util.List<String> requestSteps(String task, String synopsis) {
        String endpoint = System.getProperty("actionable.ollama.url", DEFAULT_ENDPOINT);
        String model = System.getProperty("actionable.ollama.model", "gemma3:4b");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("format", "json");

        JsonArray messages = new JsonArray();
        messages.add(message("system", DECOMPOSE_PROMPT));
        messages.add(message("user", "Task: " + task + "\nCurrent world state: " + synopsis));
        body.add("messages", messages);

        return parseSteps(send(endpoint, body));
    }

    private String send(String endpoint, JsonObject body) {
        String provider = System.getProperty("actionable.model.provider", "ollama");
        if (provider.equalsIgnoreCase("claude")) {
            JsonArray messages = body.getAsJsonArray("messages");
            String system = messages.get(0).getAsJsonObject().get("content").getAsString();
            String stage = system.equals(DIRECTOR_PROMPT) ? "director" : system.equals(DECOMPOSE_PROMPT)
                    ? "decomposition" : system.equals(JUDGE_PROMPT) ? "judge" : "action";
            return ClaudeCli.request(system,
                    messages.get(1).getAsJsonObject().get("content").getAsString(), PlannerSchema.forStage(stage));
        }
        if (!provider.equalsIgnoreCase("ollama")) {
            throw new IllegalStateException("Unknown actionable.model.provider: " + provider);
        }
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
            return responseJson.getAsJsonObject("message").get("content").getAsString();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not get a plan from Ollama: " + safeMessage(exception), exception);
        }
    }

    static java.util.List<String> parseSteps(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        java.util.List<String> steps = new java.util.ArrayList<>();
        JsonElement stepsElement = root.get("steps");
        if (stepsElement == null || !stepsElement.isJsonArray()) {
            return steps;
        }
        for (JsonElement element : stepsElement.getAsJsonArray()) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            String step = element.getAsString().codePoints()
                    .filter(codePoint -> !Character.isISOControl(codePoint))
                    .limit(120)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString()
                    .strip();
            if (!step.isBlank()) {
                steps.add(step);
            }
            if (steps.size() == MAX_STEPS) {
                break;
            }
        }
        return steps;
    }

    static ActionPlan parsePlan(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String summary = cleanText(root, "summary", "No summary provided", 200);
        String goal = cleanText(root, "goal", "Choosing next step", 120);
        boolean complete = root.has("complete") && root.get("complete").getAsBoolean();
        boolean stepComplete = root.has("step_complete") && root.get("step_complete").getAsBoolean();
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
        return new ActionPlan(summary, goal, complete, stepComplete, action);
    }

    private static Optional<PlannedAction> parseAction(JsonObject action) {
        String type = action.has("type") ? action.get("type").getAsString() : "baritone";
        if (type.equals("wait")) {
            return Optional.of(new PlannedAction("wait", "", "", 0, 0, 0, ""));
        }
        if (type.equals("attack") && action.has("entity_id")) {
            String id = action.get("entity_id").toString();
            if (id.matches("[0-9]{1,10}")) {
                try {
                    int entityId = Integer.parseInt(id);
                    return Optional.of(new PlannedAction("attack", Integer.toString(entityId), "", 0, 0, 0, ""));
                } catch (NumberFormatException ignored) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
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
        // Small models often name the Baritone verb as the action type and split its arguments into
        // fields instead of returning {"type":"baritone","command":"..."}. Rebuild the command.
        if (ALLOWED_COMMANDS.contains(type)) {
            return validateCommand(rebuildCommand(type, action))
                    .map(command -> new PlannedAction("baritone", command, "", 0, 0, 0, ""));
        }
        return Optional.empty();
    }

    private static String rebuildCommand(String verb, JsonObject action) {
        if (action.has("command")) {
            String command = action.get("command").getAsString().strip();
            // Either the full command or just its arguments may appear here.
            return command.startsWith(verb) ? command : verb + " " + command;
        }
        return switch (verb) {
            case "mine" -> verb + " " + firstOf(action, "1", "quantity", "count", "amount")
                    + " " + firstOf(action, "", "block", "block_id", "blockID", "item", "target");
            case "goto" -> verb + " " + firstOf(action, "", "x") + " " + firstOf(action, "", "y")
                    + " " + firstOf(action, "", "z");
            case "explore", "cancel", "come" -> verb;
            default -> verb + " " + firstOf(action, "", "target", "block", "block_id", "item", "name");
        };
    }

    private static String firstOf(JsonObject action, String fallback, String... keys) {
        for (String key : keys) {
            JsonElement element = action.get(key);
            if (element != null && element.isJsonPrimitive()) {
                return element.getAsString().strip();
            }
        }
        return fallback;
    }

    static java.util.Optional<String> validateCommand(String command) {
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
                if (parts.length != 3 || !parts[1].matches("\\d{1,4}")
                        || Integer.parseInt(parts[1]) <= 0 || Integer.parseInt(parts[1]) > 4096
                        || !parts[2].matches("[a-zA-Z0-9_.-]+(?::[a-zA-Z0-9_./-]+)?")) {
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

    record ActionPlan(String summary, String goal, boolean complete, boolean stepComplete,
            Optional<PlannedAction> action) {
    }
}
