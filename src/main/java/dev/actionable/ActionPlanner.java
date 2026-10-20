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
    static final Set<String> MENU_OPS = Set.of("read", "quick_move", "move", "drop", "close");
    private static final Set<String> ALLOWED_COMMANDS =
            Set.of("mine", "goto", "build", "explore", "find", "pickup", "farm", "cancel", "come", "follow");
    private static final String GOAL_HEADER = "GET TO THE NETHER!!!\n\n";
    private static final String SYSTEM_PROMPT = GOAL_HEADER + """
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
            - explore: no arguments. It explores new terrain.
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
            It can place one held/inventory block; use "place_block" for a nearby valid surface and "jump_place"
            only when placing into the player's current feet cell. jump_place jumps one block and places at the
            original feet cell after clearing replaceable flowers/plants. It cannot create schematic files.

            Furnace: a furnace turns raw items into useful ones: raw iron/gold/copper into ingots, raw
            beef/porkchop/mutton/chicken/rabbit/fish into cooked food (much more filling), sand into glass.
            It needs an input in the top slot and fuel in the bottom slot (coal or charcoal smelt 8 items,
            logs and planks 1.5 each), then about 10 seconds per item, then the results are collected.
            Use the goal "smelt in house furnace": code walks to the house furnace, loads every valid input
            with enough fuel (keeping 10 planks for a bed and door), waits, collects, and repeats until
            nothing is left. Do not try to work the furnace slot by slot yourself.

            Food: health only regenerates when the hunger bar is high. Code eats automatically when
            hungry or hurt. Pigs, cows, chickens and sheep are food; while hunting sheep, food animals met
            on the way are killed too when food is low.

            Doors: a door needs a 1-wide, 2-tall opening with solid blocks on both sides at both heights
            and solid ground below. Never place a door in open space or inside a room. For the house use
            the step "place door in house": it takes back misplaced doors, breaks the two blocks filling
            the doorway if needed, and hangs the door. One door is enough; do not craft more.

            Crafting: {"type":"craft","item":"minecraft:stick","count":4} crafts the items in
            craftable_catalog. count is the total to HOLD afterwards, like mine's quantity. 2x2 recipes
            (planks, sticks, crafting table, torches) work anywhere; 3x3 recipes (pickaxes, axes, swords,
            shovels, furnace, chest) need a placed crafting table within 4 blocks: place one, or goto the
            nearest_crafting_table first. A crafting table is reusable: mining a placed table (by hand
            is fine) puts it back in your inventory. Leave placed tables standing after crafting. If nearest_crafting_table shows one,
            walk to it and reuse it; never craft a second table. Reclaim one only for an explicit
            relocation into an already built house. Plan steps map directly to actions: "craft N X" ->
            {"type":"craft","item":X,"count":N}; "mine N X" -> Baritone "mine N X"; "place
            minecraft:crafting_table nearby" -> place_block at a nearby_placement_candidates position.

            Tools and safety are enforced. A mine command is rejected when the block drops nothing without a
            pickaxe you lack (stone, coal ore and every ore need one; cobblestone comes from mining
            minecraft:stone), or when every nearby deposit is in a dark cave or deep underground and you
            lack cave gear. Mining has no fixed travel-distance cutoff. Read a
            rejection and change plan (craft the tool, explore the surface, or pick another resource);
            never resend the rejected command. The synopsis safety={...} field rates light, depth and
            gear: when level=danger, get to light or the surface before anything else, and never go
            underground while cave_ready=false.
            Use "lookup" with a short search query to find registered block/item IDs or player-known recipes.
            Nether entry is the objective. Iron/diamond tools and armor, a shield, bucket, flint and steel,
            and golden boots are craftable. Smelt raw iron/gold before crafting ingot items.
            Use {"type":"use_item","item":"minecraft:bucket","x":0,"y":64,"z":0} on an observed
            water/lava SOURCE to fill a bucket. With a full bucket, specify the solid clicked block and
            its face (up/down/north/south/east/west); fluid goes into the adjacent cell. Move within
            reach with clear sight first; keep lava away from yourself and never place water in the Nether.
            Water placed beside lava sources makes obsidian; flowing lava may make cobblestone instead.
            Harvest ordinary obsidian with a diamond pickaxe. Do not use crying obsidian for a portal.
            The reliable supported portal route is 10 obsidian plus flint and steel. Choose dry clear
            ground, clear a 4-wide by 5-high plane and keep access on both sides. For a frame in the X/Y
            plane with origin (X,Y,Z), place the bottom at (X+1,Y,Z),(X+2,Y,Z), the sides at
            (X,Y+1..Y+3,Z),(X+3,Y+1..Y+3,Z), and the top at (X+1,Y+4,Z),(X+2,Y+4,Z).
            The four corners can be spare solid blocks; keep the 2x3 interior empty. Place blocks one at
            a time using supports or temporary scaffolding, verifying each placement. Ignite by using
            {"type":"use_item","item":"minecraft:flint_and_steel","x":X+1,"y":Y,"z":Z,"face":"up"}
            with actual integer coordinates. Observe minecraft:nether_portal blocks, walk into the
            interior and stay there for the transition. Do not claim Nether entry until dimension is
            minecraft:the_nether. Equip armor before entering. A shield can be equipped but active
            shield blocking and ranged attacks are not automated. Beds explode in the Nether: do not sleep there.
            Lookup results are local game data and will be supplied in the next turn. Do not invent global
            biome/dimension/ore-generation ranges; only report nearby loaded observations in the synopsis.
            Basic melee combat is available: {"type":"attack","entity_id":123} sends one hit to a
            live hostile mob identified in nearby_hostile_mobs. Only attack targets marked melee_reachable=true.
            Attacks respect weapon cooldown and line of sight. No ranged attacks or combat pathfinding.
            Never target players or neutral mobs. Escape creepers and flee when health is 6 or lower.
            Observe target health/death before claiming damage or kills; repeat attack if still needed.
            The synopsis lists everything carried: occupied inventory slots, totals, held_item, offhand, armor worn
            (head/chest/legs/feet) and free_inventory_slots. Use {"type":"equip","item":"minecraft:iron_sword"}
            to hold a tool or weapon, or to wear armor or a shield, that is already in the inventory.
            Equip only items the inventory lists; equipping cannot create items. When an item is already
            held or worn the result is EQUIP_ALREADY: do not repeat it.
            A result starting REPEAT_BLOCKED means the action was refused for looping; choose something else.

            Counting: every quantity is a TOTAL to hold, never an amount to add. "mine 18 minecraft:birch_log"
            mines only until the inventory holds 18 birch logs in total. Read the inventory/totals fields
            before choosing a quantity, and say the remaining need in your summary. Different wood types
            are different items: spruce logs never count toward birch.

            Opening and using things:
            - {"type":"break_block","x":..,"y":..,"z":..} breaks one block (vines, leaves, dirt, a plank in
              the way). It walks to a dry spot within reach first and picks the best hotbar tool, so you
              never need to be close already. Baritone itself digs through leaves, vines and soft blocks
              in its path, so a blocked route is not a reason to stop; use break_block only for one
              specific block.
            - {"type":"use_block","x":..,"y":..,"z":..} right-clicks a block within 4 blocks (opens a crafting
              table, furnace, chest, barrel; also doors, beds, buttons).
            - {"type":"menu","op":"read"} lists the open menu's slots (or your inventory if nothing is open)
              with slot numbers and the item in each; read before moving anything.
            - {"type":"menu","op":"quick_move","slot":N} shift-clicks slot N (moves the stack between the
              container and your inventory; in a furnace it loads input/fuel or takes the result).
            - {"type":"menu","op":"move","slot":A,"to_slot":B} moves the stack in slot A into slot B.
            - {"type":"menu","op":"drop","slot":N} throws the stack out; {"type":"menu","op":"close"} closes it.
            - {"type":"screenshot"} captures what the player sees; the image arrives with your next turn.
              Use it only when the text state is not enough (what a structure looks like, where you are).

            Night and darkness: dark is a risk, never a reason to freeze. Night makes hostile mobs spawn
            on the surface in the dark, so before dusk gather a sword, torches/lantern/campfire and a bed
            (3 wool + 3 planks) when affordable. At night you may sleep in a bed, stay near light, keep
            working at a lit site, or mine underground once you hold torches. Do not abandon a task only
            because light is low; act on actual hostile mobs and low health. Once a house or bed exists,
            code takes you home at night and sleeps; you are not asked while that runs.

            Travel has no fixed home radius: goto and mining may go beyond the start area.
            Never plan while sinking in water: get onto land first; code swims you out.

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
            or {"summary":"hold sword","goal":"prepare to fight","complete":false,
                "action":{"type":"equip","item":"minecraft:stone_sword"}}
            or {"summary":"make handles","goal":"craft sticks","complete":false,
                "action":{"type":"craft","item":"minecraft:stick","count":4}}
            or {"summary":"open the table","goal":"use crafting table","complete":false,
                "action":{"type":"use_block","x":4,"y":64,"z":-2}}
            or {"summary":"load furnace","goal":"smelt","complete":false,
                "action":{"type":"menu","op":"quick_move","slot":30}}
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

    private static final String DECOMPOSE_PROMPT = GOAL_HEADER + """
            You break a Minecraft task into an ordered plan of concrete, verifiable steps.
            Each step must be achievable with these abilities only: Baritone navigation and mining
            (mine, goto, explore, find, pickup, farm, follow), crafting items in craftable_catalog,
            placing a single block from inventory, using buckets/flint and steel on observed coordinates,
            close-range melee against hostile mobs, equipping items
            already carried, and looking up block/item IDs. Building from schematics is NOT available.
            Avoid adjacent duplicate steps and cap each gathering step at 64 items.
            A later gathering step may repeat an earlier one if crafting consumed the first batch.
            Every material you gather must be used by a later step or by the task itself; know why you
            collect it. Use the progression briefing: tools come before the blocks that need them (stone,
            coal and ores drop nothing without the right pickaxe), and cobblestone is obtained by mining
            minecraft:stone. Do not plan to go underground when cave_ready=false.
            Write steps in these forms so they can be checked: "mine 3 minecraft:oak_log",
            "craft 12 minecraft:oak_planks", "place minecraft:crafting_table nearby", "build house",
            "hunt 3 minecraft:white_wool" (kill white sheep), "place minecraft:white_bed in house",
            "place minecraft:crafting_table in house", "place door in house", "go to house" (to craft at
            the house table). Build the house before any step that places furniture inside it. The house crafting table
            is never picked up. Quantities are totals
            to hold when the step finishes. 3x3 recipes need a placed crafting table. Reuse the crafting
            table: if one is in inventory, place it; if one is placed elsewhere, "go to crafting table"
            walks there to use it. Never plan a second table or a pickup after crafting. Only reclaim
            a table when explicitly relocating it into an already built house.
            Use the fewest steps that actually accomplish the task, at most 8.
            Combine gathering requirements when possible: "mine 18 minecraft:birch_log" collects a total
            of 18 logs. Repeat gathering only when an intervening craft consumes the earlier batch.
            Use the exact wood/stone type the task
            names, and check the inventory first; hold-totals already met need no step.
            Return only JSON: {"steps":["mine 3 minecraft:oak_log","craft 12 minecraft:oak_planks"]}
            """;
    private static final String JUDGE_PROMPT = GOAL_HEADER + """
            You judge whether one Minecraft objective is already satisfied.
            You are given the objective, the player's inventory, and what was recently done.
            Answer only from that evidence. If the objective names a quantity of an item, it is
            satisfied only when the inventory holds at least that quantity.
            Return only JSON: {"done":true,"why":"short reason"} or {"done":false,"why":"short reason"}
            """;
    private static final String DIRECTOR_PROMPT = GOAL_HEADER + """
            You decide what a Minecraft player should do next, with no human giving instructions.
            You are given a progression briefing, the world state (including a safety assessment), goals
            already achieved, and goals recently attempted.
            Abilities: Baritone navigation and mining (mine, goto, explore, find, pickup, farm, follow),
            crafting the items in craftable_catalog (2x2 anywhere, 3x3 at a placed crafting table), placing
            a block from inventory, close-range melee against hostile mobs, equipping items already
            carried, and looking up IDs.
            Schematic building is NOT available.
            Decide WHY before WHAT. Every material must be for something: name what the goal unlocks or
            lets you craft, using inventory_uses and next_milestone. Never gather a material that nothing
            you plan to make uses, and never gather more of something you already hold enough of.
            Tools gate mining: stone, coal ore and every ore drop nothing without a pickaxe, and iron ore
            needs a stone pickaxe. Never choose to mine a block in cannot_mine_yet; craft the tool instead.
            Cobblestone comes from mining minecraft:stone; never go looking for placed cobblestone.
            A crafting table is reusable: walk to it and leave it standing after crafting. Never choose a second one while
            crafting_table shows one in inventory or placed nearby.
            Safety comes first. Darkness alone is only caution: at night keep working, or prepare (weapon,
            torches, lantern/campfire, bed) and sleep. If safety level=danger because of low health or
            hostile mobs, choose to reach light or the surface. Do not choose
            goals that take you underground or into dark caves while cave_ready=false; prefer exposed
            blocks on the surface, cliffs and hillsides near you. Escape at health 6 or lower and avoid
            creepers. Fight only mobs marked melee_reachable=true.
            Early game is a fixed checklist run by code: 20 logs; build the house; put a crafting table
            inside the house (it stays there for good); craft and hang a door; craft a wooden sword; craft
            a wooden pickaxe (gather more wood first if short); kill sheep for 3 white wool; craft the bed
            and place it inside the house; sleep at night. Then: stone pickaxe, stone sword, food (24 hunger
            points carried), a furnace in the house, and only then coal for torches. Coal waits until there is
            a furnace and food, unless you hold raw ore that needs smelting; a coal goal before that is
            rejected. Only after that come the pickaxe,
            stone tools, food, torches and iron; never race to cobblestone, coal or iron before the house
            and bed exist. Travel has no fixed home radius.
            The briefing ends with a Situation line: what you have, what you still need and why, and the
            best job you can do right now. Use it. With a bed in the house you sleep at night. Without one,
            code works from home at night: it finishes the house, crafts tools, smelts in the house furnace,
            and digs the house staircase mine for cobblestone and then iron, down to y=16, stopping at caves.
            After that, follow the game progression list in the briefing: pick one concrete goal from the
            earliest stage that is not finished (judge by inventory and goals already achieved). Commit to
            it: do not switch to a different stage or invent a new project halfway.
            next_milestone is normally the right goal; choose something else only when safety or the
            world state gives a clear reason, and say what it is.
            Never repeat a goal listed as already achieved, and do not repeat a recently attempted goal
            unless the state shows it clearly failed and is still worth doing.
            Goal forms: "craft 1 minecraft:wooden_pickaxe", "mine 8 minecraft:oak_log", "build house",
            "hunt 3 minecraft:white_wool", "place minecraft:crafting_table in house", "place door in house",
            "place minecraft:white_bed in house", "explore".
            Return only JSON: {"goal":"craft 1 minecraft:wooden_pickaxe",
             "why":"stone and coal drop nothing without a pickaxe; it unlocks cobblestone for stone tools"}
            """;
    static String systemPrompt(String stage) {
        return switch (stage) {
            case "director" -> DIRECTOR_PROMPT;
            case "decomposition" -> DECOMPOSE_PROMPT;
            case "action" -> SYSTEM_PROMPT;
            case "judge" -> JUDGE_PROMPT;
            default -> throw new IllegalArgumentException("Unknown planner stage: " + stage);
        };
    }

    private static final String DEFAULT_ENDPOINT = "http://localhost:11434/api/chat";
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_IDS = new java.util.concurrent.atomic.AtomicLong();

    private record RequestContext(long id, String stage, String provider) { }
    private final ThreadLocal<RequestContext> requestContext = new ThreadLocal<>();

    private void logResponseBody(String body) {
        RequestContext context = requestContext.get();
        ActionableLog.LOGGER.info("{}", ActionableLog.modelResponseBody(
                context.id(), context.stage(), context.provider(), body));
    }

    private <T> T loggedRequest(String stage, java.util.function.Supplier<T> request) {
        long id = REQUEST_IDS.incrementAndGet();
        long started = System.nanoTime();
        String provider = System.getProperty("actionable.model.provider", "ollama");
        String model = provider.equalsIgnoreCase("claude")
                ? System.getProperty("actionable.claude.model", "claude-sonnet-5-5")
                : System.getProperty("actionable.ollama.model", "gemma3:4b");
        ActionableLog.LOGGER.info("[Actionable] model_request id={} stage={} provider={} model={}",
                id, stage, ActionableLog.text(provider), ActionableLog.text(model));
        requestContext.set(new RequestContext(id, stage, provider));
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
        } finally {
            requestContext.remove();
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

    private volatile String pendingImage;

    /** Attaches a PNG (base64) to the next action request; only Ollama vision models can use it. */
    void attachImage(String base64Png) {
        pendingImage = base64Png;
    }

    CompletableFuture<ActionPlan> plan(String task, String synopsis, String lastAction, String lastResult,
            String planContext, String history) {
        return CompletableFuture.supplyAsync(
                () -> loggedRequest("action", () -> requestPlan(task, synopsis, lastAction, lastResult, planContext, history)), executor);
    }

    CompletableFuture<java.util.List<String>> decompose(String task, String synopsis, String briefing) {
        return CompletableFuture.supplyAsync(
                () -> loggedRequest("decomposition", () -> requestSteps(task, synopsis, briefing)), executor);
    }

    CompletableFuture<GoalChoice> chooseGoal(String synopsis, String briefing, String achieved, String attempted) {
        return CompletableFuture.supplyAsync(
                () -> loggedRequest("director", () -> requestGoal(synopsis, briefing, achieved, attempted)), executor);
    }

    private GoalChoice requestGoal(String synopsis, String briefing, String achieved, String attempted) {
        String endpoint = System.getProperty("actionable.ollama.url", DEFAULT_ENDPOINT);
        String model = System.getProperty("actionable.ollama.model", "gemma3:4b");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("format", "json");

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt("director")));
        messages.add(message("user", briefing
                + "\nGoals already achieved: " + (achieved.isBlank() ? "none" : achieved)
                + "\nGoals recently attempted: " + (attempted.isBlank() ? "none" : attempted)
                + "\nCurrent world state: " + synopsis));
        body.add("messages", messages);

        return parseGoal(send(endpoint, body));
    }

    static GoalChoice parseGoal(String json) {
        JsonObject chosen = JsonParser.parseString(json).getAsJsonObject();
        String goal = cleanText(chosen, "goal", "", 120).strip();
        if (goal.isBlank()) {
            throw new IllegalStateException("Director returned no goal");
        }
        return new GoalChoice(goal, cleanText(chosen, "why", "", 200).strip());
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
        messages.add(message("system", systemPrompt("judge")));
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
        messages.add(message("system", systemPrompt("action")));
        messages.add(message("user", "Task: " + task + "\n" + planContext
                + "\nRecent action history (oldest first): " + (history.isEmpty() ? "none" : history)
                + "\nPrevious action: "
                + (lastAction.isEmpty() ? "none" : lastAction) + "\nPrevious action result: "
                + (lastResult.isEmpty() ? "none" : lastResult) + "\nLatest world state: " + synopsis));
        String image = pendingImage;
        if (image != null) {
            pendingImage = null;
            JsonArray images = new JsonArray();
            images.add(image);
            messages.get(1).getAsJsonObject().add("images", images);
        }
        body.add("messages", messages);

        return parsePlan(send(endpoint, body));
    }

    private java.util.List<String> requestSteps(String task, String synopsis, String briefing) {
        String endpoint = System.getProperty("actionable.ollama.url", DEFAULT_ENDPOINT);
        String model = System.getProperty("actionable.ollama.model", "gemma3:4b");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("format", "json");

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt("decomposition")));
        messages.add(message("user", "Task: " + task + "\n" + briefing + "\nCurrent world state: " + synopsis));
        body.add("messages", messages);

        return parseSteps(send(endpoint, body));
    }

    private String send(String endpoint, JsonObject body) {
        String stage = requestContext.get().stage();
        String provider = System.getProperty("actionable.model.provider", "ollama");
        if (provider.equalsIgnoreCase("claude")) {
            JsonArray messages = body.getAsJsonArray("messages");
            String system = messages.get(0).getAsJsonObject().get("content").getAsString();
            return ClaudeCli.request(system,
                    messages.get(1).getAsJsonObject().get("content").getAsString(), PlannerSchema.forStage(stage), this::logResponseBody);
        }
        if (!provider.equalsIgnoreCase("ollama")) {
            throw new IllegalStateException("Unknown actionable.model.provider: " + provider);
        }
        OllamaProtocol.configure(body, stage);
        // Keep the model loaded between calls; reloading it cost about 10 seconds after any pause.
        body.addProperty("keep_alive", System.getProperty("actionable.ollama.keepAlive", "30m"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            logResponseBody(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
            }
            return OllamaProtocol.content(response.body());
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

    static Optional<PlannedAction> parseAction(JsonObject action) {
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
        if (type.equals("equip")) {
            String item = action.has("item") ? action.get("item").getAsString()
                    : action.has("block") ? action.get("block").getAsString() : "";
            if (item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                return Optional.of(new PlannedAction("equip", item, "", 0, 0, 0, ""));
            }
            return Optional.empty();
        }
        if (type.equals("craft") && action.has("item")) {
            String item = cleanText(action, "item", "", 80).strip().toLowerCase(Locale.ROOT);
            int count = action.has("count") && action.get("count").isJsonPrimitive()
                    && action.get("count").getAsJsonPrimitive().isNumber() ? action.get("count").getAsInt() : 1;
            if (!item.contains(":")) {
                item = "minecraft:" + item;
            }
            // The item travels in "block" and the total to hold in "command".
            return item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && count >= 1 && count <= 256
                    ? Optional.of(new PlannedAction("craft", Integer.toString(count), item, 0, 0, 0, ""))
                    : Optional.empty();
        }
        if (type.equals("lookup") && action.has("query")) {
            String query = cleanText(action, "query", "", 80);
            return query.isBlank() ? Optional.empty()
                    : Optional.of(new PlannedAction("lookup", "", "", 0, 0, 0, query));
        }
        if (type.equals("use_item") && action.has("item") && action.has("x") && action.has("y") && action.has("z")) {
            String item = cleanText(action, "item", "", 80).strip().toLowerCase(Locale.ROOT);
            String face = cleanText(action, "face", "up", 10).strip().toLowerCase(Locale.ROOT);
            try {
                long x = Long.parseLong(action.get("x").toString());
                long y = Long.parseLong(action.get("y").toString());
                long z = Long.parseLong(action.get("z").toString());
                return ItemUseRules.valid(item, face, x, y, z)
                        ? Optional.of(new PlannedAction("use_item", face, item, (int) x, (int) y, (int) z, ""))
                        : Optional.empty();
            } catch (NumberFormatException malformed) {
                return Optional.empty();
            }
        }
        if (type.equals("screenshot")) {
            return Optional.of(new PlannedAction("screenshot", "", "", 0, 0, 0, ""));
        }
        if (type.equals("break_block") && action.has("x") && action.has("y") && action.has("z")) {
            return Optional.of(new PlannedAction("break_block", "", "",
                    action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt(), ""));
        }
        if (type.equals("use_block") && action.has("x") && action.has("y") && action.has("z")) {
            return Optional.of(new PlannedAction("use_block", "", "",
                    action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt(), ""));
        }
        if (type.equals("menu")) {
            String op = cleanText(action, "op", "read", 20).strip().toLowerCase(Locale.ROOT);
            if (!MENU_OPS.contains(op)) {
                return Optional.empty();
            }
            int slot = action.has("slot") && action.get("slot").isJsonPrimitive() ? action.get("slot").getAsInt() : -1;
            int toSlot = action.has("to_slot") && action.get("to_slot").isJsonPrimitive()
                    ? action.get("to_slot").getAsInt() : -1;
            boolean needsSlot = !op.equals("read") && !op.equals("close");
            if (needsSlot && (slot < 0 || slot > 200 || (op.equals("move") && (toSlot < 0 || toSlot > 200)))) {
                return Optional.empty();
            }
            return Optional.of(new PlannedAction("menu", op, "", slot, toSlot, 0, ""));
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

    record GoalChoice(String goal, String why) {
    }

    record ActionPlan(String summary, String goal, boolean complete, boolean stepComplete,
            Optional<PlannedAction> action) {
    }
}
