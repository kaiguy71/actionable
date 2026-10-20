package dev.actionable;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class ActionableClient implements ClientModInitializer {
    private final ActionPlanner planner = new ActionPlanner();
    private static final int DEBUG_PANEL_WIDTH = 228;
    /** The panel is drawn at this fraction of normal GUI size so the game stays visible behind it. */
    private static final float DEBUG_PANEL_SCALE = 0.6f;
    private KeyMapping toggleKey;
    private boolean enabled = true;
    private boolean debugEnabled;
    private boolean debugDetailed;
    private boolean planning;
    private boolean baritoneCommandRunning;
    private String currentBaritoneCommand = "";
    private long generation;
    private long clientTick;
    private long baritoneCommandStartedAt;
    private long lastBaritoneMovementAt;
    private BlockPos baritoneStartPosition;
    private BlockPos lastBaritonePosition;
    private JumpPlace pendingJumpPlace;
    private int ticksUntilPlan;
    private String activeTask;
    private String lastAction = "";
    private String currentGoal = "Idle";
    private String lastResult = "No action issued";
    private String baritoneStatus = "No response captured";
    private String errorCode = "none";
    private String lastError = "none";
    private BlockPos taskOrigin;
    private static final int HISTORY_LIMIT = 5;
    private java.util.List<String> taskSteps = java.util.List.of();
    private int currentStep;
    private static final int DANGER_HEALTH = 6;
    private static final int MAX_WHITE_FLAG_RESTARTS = 5;
    /** Block/sky light at or below which a held torch is placed automatically. */
    private static final int TORCH_LIGHT = 4;
    /** Blocks walked per travel leg before the model is asked whether to continue. */
    private static final int TRAVEL_LEG = 500;
    private static final int GOAL_MEMORY = 20;
    private boolean autonomous;
    private boolean choosingGoal;
    private boolean escaping;
    private boolean defending;
    private int ticksUntilGoal;
    private long taskStartedAtTick;
    private final java.util.ArrayDeque<String> achievedGoals = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> attemptedGoals = new java.util.ArrayDeque<>();
    private boolean decomposing;
    private boolean judging;
    private boolean stepJudged;
    private final java.util.ArrayDeque<String> history = new java.util.ArrayDeque<>();
    private static final int AVOID_MEMORY = 4;
    private static final int SITE_SCAN_RADIUS = 24;
    private static final int SURFACE_SCAN_RADIUS = 48;
    private static final int SURFACE_DROP_LIMIT = 4;
    private static final java.util.List<String> BARITONE_SETTINGS = java.util.List.of(
            "blacklistClosestOnFailure false", "useSwordToMine false", "allowParkour true",
            "allowParkourAscend true", "allowParkourPlace true", "allowVines true", "legitMine false",
            "smoothLook true");
    private Crafter pendingCraft;
    private boolean retreating;
    private boolean stepActionTaken;
    private int replans;
    /** House corner, saved per world; before a house exists, {@link #startArea} stands in as home. */
    private int[] home;
    private int[] startArea;
    private String homeWorld = "";
    private boolean houseBuiltSeen;
    private Hunter pendingHunt;
    private BlockBreaker pendingBreak;
    private Smelter pendingSmelt;
    private StairMiner pendingStairs;
    /** True while the active task is a job the night routine chose (no bed, so work at home). */
    private boolean nightJob;
    /** Morning came while in the house mine; the task ends once the player has climbed out. */
    private boolean morningPending;
    private long furnaceReadyAt;
    private long smeltCooldownUntil;
    private long mineRestUntil;
    /** Baritone command to resume after a snag is cleared; null when the break was the model's. */
    private String unstickResume;
    private int lastX;
    private int lastZ;
    private long lastSidewaysMoveAt;
    private BlockPos lastSnag;
    private int snagAttempts;
    private boolean nightRoutine;
    private boolean returningHome;
    private boolean holdingJump;
    private boolean baritoneConfigured;
    private int waterTicks;
    private long lastSleepAttempt;
    private int wanderAttempts;
    private int whiteFlagRestarts;
    private long nextTorchTick;
    private boolean finalCheck;
    /** Set when code (inventory/world facts) already confirmed the last step, so no model re-check is needed. */
    private boolean codeVerified;
    /** Compass direction of the running travel leg, or empty/null when the running command is not one. */
    private String travelDirection;
    private boolean finalChecked;
    /** 0 = not eating, 1 = waiting for Baritone to let go of the keys, 2 = eating. */
    private int eatPhase;
    private long eatStartedAt;
    private long eatBackoffUntil;
    private int eatFoodBefore;
    private String eatItem;
    private String eatResume;
    /** The step code started itself; while it makes progress the model is not asked. */
    private String directStep;
    private boolean houseTableSeen;
    private int directBest;
    private long directProgressAt;
    private int directResends;
    private int[] lastSafePosition;
    private int[] mineStart;
    private String pendingMine;
    private long pendingMineSince;
    private String goalReason = "";
    private String safetyStatus = "unknown";
    private final java.util.ArrayDeque<int[]> avoidAreas = new java.util.ArrayDeque<>();

    @Override
    @SuppressWarnings("null")
    public void onInitializeClient() {
        ActionableLog.LOGGER.info("[Actionable] initialized provider={} autonomous={} log=logs/latest.log",
                ActionableLog.text(System.getProperty("actionable.model.provider", "ollama")), autonomous);
        toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.actionable.toggle",
                        InputConstants.Type.KEYBOARD,
                        InputConstants.KEY_GRAVE,
                        KeyMapping.Category.MISC
                )
        );
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("action")
                        .then(ClientCommands.argument("prompt", StringArgumentType.greedyString())
                                .executes(context -> {
                                    whiteFlagRestarts = 0;
                                    submit(context.getArgument("prompt", String.class));
                                    return 1;
                                }))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("actiondebug")
                        .executes(context -> {
                            debugEnabled = !debugEnabled;
                            tell(Minecraft.getInstance(), "Debug sidebar " + (debugEnabled ? "enabled." : "disabled.")
                                    + " (use /actiondebug to toggle, /actiondebug full for all details).");
                            return 1;
                        })
                        .then(ClientCommands.literal("capture")
                                .executes(context -> {
                                    captureContext(Minecraft.getInstance());
                                    return 1;
                                }))
                        .then(ClientCommands.literal("full")
                                .executes(context -> {
                                    debugEnabled = true;
                                    debugDetailed = !debugDetailed;
                                    tell(Minecraft.getInstance(), "Debug sidebar shows "
                                            + (debugDetailed ? "all details." : "the summary."));
                                    return 1;
                                }))));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("actionauto")
                        .executes(context -> {
                            autonomous = !autonomous;
                            Minecraft client = Minecraft.getInstance();
                            if (!autonomous && activeTask != null) {
                                abandonTask(client, "autonomous mode turned off");
                            }
                            ticksUntilGoal = 0;
                            tell(client, "Autonomous mode " + (autonomous ? "on." : "off."));
                            return 1;
                        })));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            resetToDefaults(client);
            Screenshots.archiveSession(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            resetToDefaults(client);
            Screenshots.archiveSession(client);
        });

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("actionable", "debug_sidebar"),
                (graphics, deltaTracker) -> renderDebugSidebar(Minecraft.getInstance(), graphics)
        );

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            if (text.contains("[Baritone]")) {
                ActionableLog.LOGGER.info("[Actionable] baritone_feedback {}", ActionableLog.text(text));
                baritoneStatus = compact(text, 140);
                lastResult = "Baritone feedback: " + baritoneStatus;
                if (text.contains("Error") || text.contains("error")
                        || text.contains("Unknown command") || text.contains("insufficient permissions")) {
                    errorCode = "BARITONE_COMMAND_REJECTED";
                    lastError = baritoneStatus;
                    baritoneCommandRunning = false;
                    lastResult = "Baritone rejected the last command: " + lastError;
                    if (activeTask != null && !planning) {
                        ticksUntilPlan = 0;
                    }
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            clientTick++;
            updateBaritoneProgress(client);
            tickUnstick(client);
            tickJumpPlace(client);
            tickCraft(client);
            tickMining(client);
            tickHunt(client);
            tickBreak(client);
            tickSmelt(client);
            tickStairs(client);
            tickEat(client);
            if (working() && clientTick % 100 == 0 && eatPhase == 0 && pendingCraft == null && pendingSmelt == null
                    && pendingBreak == null && !defending) {
                arrangeHotbar(client);
            }
            tickWater(client);
            tickTorch(client);
            tickStepCheck(client);
            while (toggleKey.consumeClick()) {
                boolean controlDown = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
                        || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
                if (controlDown) {
                    toggle(client);
                }
            }
            if (tickDefense(client)) {
                return;
            }
            tickSafety(client);
            tickNight(client);
            tickHomeRange(client);
            if (enabled && autonomous && escaping && activeTask != null && clientTick % 20 == 0
                    && dangerEscape(client) == null) {
                abandonTask(client, "threat cleared");
            }
            if (enabled && autonomous && !escaping && clientTick % 20 == 0) {
                String escape = dangerEscape(client);
                if (escape != null) {
                    stopBaritone(client);
                    escaping = true;
                    retreating = false;
                    startGoal(client, "escape immediate danger", java.util.List.of(escape));
                }
            }
            if (enabled && autonomous && activeTask == null && !choosingGoal && !decomposing && !nightRoutine
                    && client.player != null && client.level != null && ticksUntilGoal-- <= 0) {
                requestGoalSelection(client);
            }
            // Night, return-home and hunting are run by code; the model is not consulted while they run.
            if (enabled && activeTask != null && !planning && !decomposing && !judging && (!nightRoutine || nightJob)
                    && !returningHome && pendingJumpPlace == null && pendingCraft == null && pendingHunt == null
                    && pendingBreak == null && pendingSmelt == null && pendingStairs == null && eatPhase == 0
                    && ticksUntilPlan-- <= 0) {
                requestNextPlan(client);
            }
        });
    }

    private void submit(String prompt) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled) {
            tell(client, "Actionable is disabled. Press Ctrl+` to enable it.");
            return;
        }
        if (prompt.isBlank()) {
            tell(client, "Usage: /action <task>");
            return;
        }
        if (prompt.length() > 1000) {
            tell(client, "Keep the task under 1,000 characters.");
            return;
        }
        if (client.player == null || client.level == null) {
            tell(client, "Join a world before starting an action.");
            return;
        }
        if (client.getConnection() == null) {
            tell(client, "No active game connection is available.");
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("baritone")) {
            tell(client, "Baritone is required (Fabric mod ID 'baritone', version 1.20.0 or newer, for Minecraft 26.3).");
            return;
        }

        // A new /action is a clean slate: nothing from earlier tasks may leak into this one.
        client.getConnection().sendChat("#stop");
        baritoneCommandRunning = false;
        currentBaritoneCommand = "";
        pendingMine = null;
        mineStart = null;
        pendingCraft = null;
        achievedGoals.clear();
        attemptedGoals.clear();
        avoidAreas.clear();
        choosingGoal = false;
        decomposing = false;
        judging = false;
        escaping = false;
        retreating = false;
        planner.attachImage(null);
        planner.cancelPending();
        finalCheck = false;
        codeVerified = false;
        finalChecked = false;
        generation++;
        activeTask = prompt;
        taskOrigin = java.util.Objects.requireNonNull(client.player).blockPosition();
        lastAction = "";
        currentGoal = "Planning first step";
        lastResult = "Waiting for planner";
        baritoneStatus = "No response captured";
        baritoneCommandRunning = false;
        pendingJumpPlace = null;
        errorCode = "none";
        lastError = "none";
        planning = false;
        ticksUntilPlan = 0;
        taskSteps = java.util.List.of();
        currentStep = 0;
        stepJudged = false;
        stepActionTaken = false;
        directStep = null;
        replans = 0;
        goalReason = "requested with /action";
        taskStartedAtTick = clientTick;
        history.clear();
        noteAttempt(prompt);
        tell(client, "Planning steps for: " + prompt);
        requestDecomposition(client);
    }

    private void requestGoalSelection(Minecraft client) {
        loadHome(client);
        if (leaveWater(client)) {
            ticksUntilGoal = 60;
            return;
        }
        if (startArea == null) {
            BlockPos here = java.util.Objects.requireNonNull(client.player).blockPosition();
            startArea = new int[] {here.getX(), here.getY(), here.getZ()};
        }
        var inventoryNow = WorldProbe.inventory(java.util.Objects.requireNonNull(client.player));
        if (facts(client).furnaceInHouse() && clientTick >= smeltCooldownUntil && dangerEscape(client) == null
                && FurnacePlan.choose(inventoryNow).isPresent()) {
            // Raw meat or ore plus fuel and a furnace: cook and smelt before anything else.
            String chosen = NeedsPlanner.SMELT;
            activeTask = chosen;
            goalReason = "raw items to cook or smelt, fuel to do it, and a furnace in the house";
            remember("chose goal", chosen);
            noteAttempt(chosen);
            ActionableLog.LOGGER.info("[Actionable] goal={} why={} source=furnace", ActionableLog.text(chosen),
                    ActionableLog.text(goalReason));
            tell(client, "Goal: " + chosen + " (why: " + goalReason + ")");
            beginTask(client, chosen);
            return;
        }
        java.util.Optional<ProgressPlanner.Milestone> foundation = milestone(client)
                .filter(ProgressPlanner::isFoundation);
        if (foundation.isPresent() && dangerEscape(client) == null) {
            // Wood, house, wool and bed come first, in order, without a director call.
            String chosen = foundation.get().goal();
            activeTask = chosen;
            goalReason = foundation.get().why();
            remember("chose goal", chosen);
            noteAttempt(chosen);
            ActionableLog.LOGGER.info("[Actionable] goal={} why={} source=foundation",
                    ActionableLog.text(chosen), ActionableLog.text(goalReason));
            tell(client, "Goal: " + chosen + " (why: " + goalReason + ")");
            beginTask(client, chosen);
            return;
        }
        String synopsis = WorldSynopsis.capture(client);
        String briefing = briefing(client, synopsis);

        // Survival is decided in code: the model was tested and did not reliably prioritise it.
        String escape = dangerEscape(client);
        if (escape != null) {
            startGoal(client, "escape immediate danger", java.util.List.of(escape));
            return;
        }

        long requestGeneration = ++generation;
        choosingGoal = true;
        currentGoal = "Choosing a goal";
        planner.chooseGoal(synopsis, briefing, joined(achievedGoals), joined(attemptedGoals))
                .whenComplete((choice, error) -> client.execute(() -> {
                    if (requestGeneration != generation) {
                        return;
                    }
                    choosingGoal = false;
                    if (!enabled || !autonomous || client.player == null || client.level == null) {
                        return;
                    }
                    String chosen = error == null && choice != null ? choice.goal().strip() : "";
                    String why = error == null && choice != null ? choice.why() : "";
                    String lowered = chosen.toLowerCase(java.util.Locale.ROOT);
                    if ((lowered.contains("smelt") || lowered.contains("cook") || lowered.contains("furnace"))
                            && facts(client).furnaceInHouse()) {
                        // The furnace is run by code: map any smelting or cooking goal to it.
                        chosen = NeedsPlanner.SMELT;
                    }
                    // Goals that need a missing tool are replaced in code: the model chose to punch stone.
                    java.util.Optional<String> problem = chosen.isEmpty() ? java.util.Optional.empty()
                            : ProgressPlanner.goalProblem(chosen, WorldProbe.inventory(client.player));
                    if (problem.isEmpty() && ProgressPlanner.duplicateTable(chosen,
                            WorldProbe.inventory(client.player), tableAccess(client))) {
                        problem = java.util.Optional.of("a crafting table already exists; reuse it instead");
                    }
                    if (problem.isEmpty() && !chosen.isEmpty()) {
                        problem = ProgressPlanner.coalTooEarly(chosen, WorldProbe.inventory(client.player), facts(client));
                    }
                    // Repeat avoidance is also in code: the model re-picked goals it had just achieved.
                    if (chosen.isEmpty() || alreadyAchieved(chosen) || problem.isPresent()) {
                        if (error != null) {
                            errorCode = plannerErrorCode(error);
                            lastError = compact(ActionPlanner.safeMessage(error), 140);
                        }
                        if (problem.isPresent()) {
                            ActionableLog.LOGGER.info("[Actionable] goal_rejected goal={} problem={}",
                                    ActionableLog.text(chosen), ActionableLog.text(problem.get()));
                            tell(client, "Rejected goal \"" + chosen + "\": " + problem.get());
                        }
                        java.util.Optional<ProgressPlanner.Milestone> milestone = milestone(client);
                        if (milestone.isEmpty()) {
                            // Nothing useful to do right now; look around and reconsider shortly.
                            tell(client, "No new goal available; exploring.");
                            chosen = "travel to find new resources";
                            why = "no milestone is reachable right now; new terrain may expose resources";
                        } else {
                            chosen = milestone.get().goal();
                            why = milestone.get().why();
                            tell(client, "Using milestone goal: " + chosen);
                        }
                    }
                    activeTask = chosen;
                    goalReason = why.isBlank() ? "no reason given" : why;
                    remember("chose goal", chosen);
                    noteAttempt(chosen);
                    ActionableLog.LOGGER.info("[Actionable] goal={} why={}",
                            ActionableLog.text(chosen), ActionableLog.text(goalReason));
                    tell(client, "Goal: " + chosen + " (why: " + goalReason + ")");
                    beginTask(client, chosen);
                }));
    }

    /**
     * The next tech-tree milestone, used when the director fails, repeats itself, or picks something
     * impossible. Inventory decides what is done; a milestone attempted twice recently is skipped.
     */
    private java.util.Optional<ProgressPlanner.Milestone> milestone(Minecraft client) {
        if (client.player == null) {
            return java.util.Optional.empty();
        }
        // Exact goal text: goalKey would merge "build house" with "place ... bed in house".
        return ProgressPlanner.nextMilestone(WorldProbe.inventory(client.player), facts(client))
                .filter(next -> attemptedGoals.stream()
                        .filter(goal -> goal.toLowerCase(java.util.Locale.ROOT)
                                .startsWith(next.goal().toLowerCase(java.util.Locale.ROOT))).count() < 3);
    }

    /** World progress: whether the house stands and a bed is placed in it. */
    private ProgressPlanner.Facts facts(Minecraft client) {
        if (client.player == null || client.level == null) {
            return ProgressPlanner.Facts.NONE;
        }
        loadHome(client);
        if (home != null && !houseBuiltSeen
                && WorldProbe.houseCompletion(client.level, home) >= HouseDesign.COMPLETE) {
            houseBuiltSeen = true;
            ActionableLog.LOGGER.info("[Actionable] house_built at={},{},{}", home[0], home[1], home[2]);
        }
        BlockPos center = home != null ? new BlockPos(home[0] + 2, home[1], home[2] + 2) : client.player.blockPosition();
        boolean bed = WorldProbe.nearestBed(client.level, center, home != null ? 6 : 24) != null;
        boolean door = false;
        boolean atHome = false;
        boolean floor = houseBuiltSeen;
        boolean furnace = false;
        boolean mine = false;
        if (home != null && client.level.hasChunk(home[0] >> 4, home[2] >> 4)) {
            // Exact cells, so a table from the old layout (now the stairs) is moved to its new spot.
            houseTableSeen = client.level.getBlockState(homeCell(HouseDesign.TABLE_CELL))
                    .is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
            furnace = client.level.getBlockState(homeCell(HouseDesign.FURNACE_CELL))
                    .is(net.minecraft.world.level.block.Blocks.FURNACE);
            StairMine.Probe probe = StairMiner.probe(client.level, home);
            int maxStep = StairMine.maxStep(home[1]);
            int depth = StairMine.depth(probe, maxStep);
            mine = depth >= maxStep || (depth > 0 && StairMine.check(probe, depth) != StairMine.Check.OK);
            door = client.level.getBlockState(homeCell(HouseDesign.DOOR_CELL)).getBlock()
                    instanceof net.minecraft.world.level.block.DoorBlock
                    && WorldProbe.strayDoors(client.level, home).isEmpty();
            BlockPos feet = client.player.blockPosition();
            atHome = HouseDesign.inside(home, feet.getX(), feet.getY(), feet.getZ());
            floor = houseBuiltSeen && WorldProbe.houseFloorProblem(client.level, home) == null;
        }
        return new ProgressPlanner.Facts(houseBuiltSeen, bed, houseTableSeen, door, atHome, floor, furnace, mine);
    }

    /** Loads this world's saved home once per world. */
    private void loadHome(Minecraft client) {
        String key = HomeStore.worldKey(client);
        if (!key.equals(homeWorld)) {
            homeWorld = key;
            home = HomeStore.load(client);
            WorldProbe.home = home;
            houseBuiltSeen = false;
            startArea = null;
            if (home != null) {
                ActionableLog.LOGGER.info("[Actionable] home_loaded at={},{},{}", home[0], home[1], home[2]);
            }
        }
    }

    /** Home, or where autonomy started before any house exists. */
    private int[] anchor() {
        return home != null ? home : startArea;
    }

    private boolean geared(Minecraft client) {
        return client.player != null && HomeRange.geared(WorldProbe.inventory(client.player));
    }

    /**
     * The home range only applies once a house exists and the player is still ungeared. Before that the
     * house goes wherever the wood is, so searching for trees (or travelling to a forest) is never blocked.
     */
    private boolean rangeExempt(Minecraft client) {
        loadHome(client);
        return home == null || geared(client);
    }

    private boolean tableNearby(Minecraft client) {
        if (client.player == null || client.level == null) {
            return false;
        }
        return tableAccess(client) == ProgressPlanner.TableAccess.NEARBY;
    }

    /** Whether a placed crafting table is within crafting reach, elsewhere in the scan, or absent. */
    private ProgressPlanner.TableAccess tableAccess(Minecraft client) {
        if (client.player == null || client.level == null) {
            return ProgressPlanner.TableAccess.NONE;
        }
        BlockPos table = WorldProbe.nearestCraftingTable(client.level, client.player.blockPosition());
        // Same reach the crafter needs, so a plan never assumes a table it then cannot use.
        if (table != null && WorldProbe.tableInReach(client.player, table)) {
            return isHouseCell(table) ? ProgressPlanner.TableAccess.HOME_NEARBY : ProgressPlanner.TableAccess.NEARBY;
        }
        if (home != null && houseTableSeen) {
            // The house table stays in the house; plans walk home to craft instead of making another.
            return ProgressPlanner.TableAccess.HOME_FAR;
        }
        return table == null ? ProgressPlanner.TableAccess.NONE : ProgressPlanner.TableAccess.ELSEWHERE;
    }

    private boolean isHouseCell(BlockPos pos) {
        return home != null && pos.equals(homeCell(HouseDesign.TABLE_CELL));
    }

    private String briefing(Minecraft client, String synopsis) {
        var inventory = WorldProbe.inventory(java.util.Objects.requireNonNull(client.player));
        ProgressPlanner.Facts facts = facts(client);
        return ProgressPlanner.briefing(inventory, tableAccess(client), WorldProbe.preferredLog(inventory, synopsis),
                facts, HomeRange.describe(anchor(), HomeRange.geared(inventory), facts.houseBuilt()))
                + "\n" + woodLine(synopsis)
                + "\n" + NeedsPlanner.report(inventory, situation(client, facts));
    }

    private static String woodLine(String synopsis) {
        java.util.regex.Matcher biome = java.util.regex.Pattern.compile("current_biome=([a-z0-9_:.]+)").matcher(synopsis);
        java.util.regex.Matcher nearby = java.util.regex.Pattern
                .compile("nearby_loaded_biomes_sampled=(\\[.*?\\])(?:;|$)").matcher(synopsis);
        return "Wood: " + TreeBiomes.advice(biome.find() ? biome.group(1) : "", nearby.find() ? nearby.group(1) : "");
    }

    /**
     * Cheap threat check that avoids capturing a full world synopsis, so it can run every second.
     * Returns a goto step away from the nearest threat, or null when the player is safe.
     */
    private String dangerEscape(Minecraft client) {
        var player = client.player;
        var level = client.level;
        if (player == null || level == null) {
            return null;
        }
        boolean hurt = player.getHealth() <= DANGER_HEALTH;
        net.minecraft.world.entity.monster.Monster nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.monster.Monster monster && monster.isAlive()
                    && (monster.getTarget() == player
                        || BuiltInRegistries.ENTITY_TYPE.getKey(monster.getType()).toString().equals("minecraft:creeper"))) {
                double distance = monster.distanceToSqr(player);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = monster;
                }
            }
        }
        BlockPos here = player.blockPosition();
        if (nearest == null) {
            return hurt ? fleeTo(here.getX(), here.getY(), here.getZ(), here.getX() + 32, here.getZ() + 32) : null;
        }
        if (!hurt && nearestDistance > 64) {
            return null;
        }
        if (!hurt && MeleeCombat.reachable(client, nearest)) {
            return null;
        }
        BlockPos threat = nearest.blockPosition();
        return fleeTo(here.getX(), here.getY(), here.getZ(), threat.getX(), threat.getZ());
    }

    /** Immediate defense does not wait for a model round trip or abandon the interrupted task. */
    private boolean tickDefense(Minecraft client) {
        net.minecraft.world.entity.Entity target = enabled && autonomous && !escaping
                && client.player != null && client.player.isAlive() && client.player.getHealth() > DANGER_HEALTH
                && client.mouseHandler.isMouseGrabbed() && client.isWindowActive()
                && dangerEscape(client) == null ? MeleeCombat.nearest(client) : null;
        if (target == null) {
            if (defending) {
                defending = false;
                ActionableLog.LOGGER.info("[Actionable] defense_ended task={}", ActionableLog.text(activeTask));
                stepJudged = false;
                ticksUntilPlan = 0;
                remember(lastAction, "Close-range defense ended; observe world before resuming task");
            }
            return false;
        }
        if (!defending) {
            defending = true;
            ActionableLog.LOGGER.info("[Actionable] defense_started entity_id={} task={}",
                    target.getId(), ActionableLog.text(activeTask));
            generation++;
            planning = false;
            judging = false;
            choosingGoal = false;
            errorCode = "none";
            lastError = "none";
            if (decomposing) {
                decomposing = false;
                if (taskSteps.isEmpty() && activeTask != null) {
                    taskSteps = java.util.List.of(activeTask);
                    currentStep = 0;
                }
            }
            pendingJumpPlace = null;
            cancelCraft(client);
            cancelBreak(client);
            stopBaritone(client);
        }
        lastAction = "attack entity_id=" + target.getId();
        lastResult = MeleeCombat.attack(client, target.getId());
        if (lastResult.startsWith("ATTACK_SENT")) {
            ActionableLog.LOGGER.info("[Actionable] combat {}", ActionableLog.text(lastResult));
        }
        currentGoal = "Defending against " + BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        return true;
    }

    static String fleeTo(int fromX, int fromY, int fromZ, int awayX, int awayZ) {
        int dx = fromX - awayX;
        int dz = fromZ - awayZ;
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        double length = Math.sqrt((double) dx * dx + (double) dz * dz);
        return "goto " + (fromX + (int) Math.round(dx / length * 40)) + " " + fromY
                + " " + (fromZ + (int) Math.round(dz / length * 40));
    }

    private void startGoal(Minecraft client, String goal, java.util.List<String> steps) {
        generation++;
        finalCheck = false;
        codeVerified = false;
        finalChecked = false;
        activeTask = goal;
        taskSteps = java.util.List.copyOf(steps);
        currentStep = 0;
        stepJudged = false;
        stepActionTaken = false;
        directStep = null;
        replans = 0;
        history.clear();
        taskStartedAtTick = clientTick;
        taskOrigin = java.util.Objects.requireNonNull(client.player).blockPosition();
        lastAction = "";
        lastResult = "Starting: " + goal;
        currentGoal = steps.isEmpty() ? goal : steps.get(0);
        errorCode = "none";
        lastError = "none";
        planning = false;
        ticksUntilPlan = 0;
        tell(client, "Goal: " + goal);
        var connection = client.getConnection();
        java.util.Optional<String> direct = steps.size() == 1
                ? ActionPlanner.validateCommand(steps.get(0))
                : java.util.Optional.empty();
        if (direct.isPresent() && connection != null) {
            // Escaping must not wait on a planning round trip.
            lastAction = direct.get();
            executeBaritone(client, connection, direct.get());
            remember(lastAction, lastResult);
            ticksUntilPlan = 200;
            return;
        }
        requestNextPlan(client);
    }

    private void beginTask(Minecraft client, String prompt) {
        if (client.player != null) {
            var inventory = WorldProbe.inventory(client.player);
            ActionableLog.LOGGER.info("[Actionable] situation task={} inventory={} report={}", ActionableLog.text(prompt),
                    ActionableLog.text(inventory.toString()),
                    ActionableLog.text(NeedsPlanner.report(inventory, situation(client, facts(client)))));
        }
        taskSteps = java.util.List.of();
        currentStep = 0;
        finalCheck = false;
        codeVerified = false;
        finalChecked = false;
        stepJudged = false;
        stepActionTaken = false;
        directStep = null;
        replans = 0;

        history.clear();
        taskStartedAtTick = clientTick;
        taskOrigin = java.util.Objects.requireNonNull(client.player).blockPosition();
        lastAction = "";
        currentGoal = "Planning steps";
        lastResult = "Waiting for planner";
        baritoneStatus = "No response captured";
        baritoneCommandRunning = false;
        pendingJumpPlace = null;
        errorCode = "none";
        lastError = "none";
        planning = false;
        ticksUntilPlan = 0;
        requestDecomposition(client);
    }

    private void abandonTask(Minecraft client, String reason) {
        tell(client, "Giving up on \"" + activeTask + "\" (" + reason + ").");
        lastResult = "Abandoned: " + reason;
        currentGoal = "Idle";
        stopBaritone(client);
        cancelCraft(client);
        pendingHunt = null;
        cancelBreak(client);
        cancelHomeWork(client);
        nightJob = false;
        escaping = false;
        retreating = false;
        returningHome = false;
        finalCheck = false;
        codeVerified = false;
        finalChecked = false;
        activeTask = null;
        taskSteps = java.util.List.of();
        ticksUntilGoal = 40;
    }

    /**
     * The model's white flag: it cannot make progress. A user task restarts from scratch with a fresh
     * model session and the original prompt; an autonomous goal is dropped and a new one is chosen.
     */
    private void whiteFlag(Minecraft client, String reason) {
        String prompt = activeTask;
        boolean userTask = "requested with /action".equals(goalReason);
        ActionableLog.LOGGER.info("[Actionable] white_flag task={} restarts={} reason={}", ActionableLog.text(prompt),
                whiteFlagRestarts, ActionableLog.text(reason));
        if (prompt != null) {
            noteAttempt(prompt + " (white flag: " + compact(reason, 80) + ")");
        }
        if (!userTask || prompt == null) {
            abandonTask(client, "white flag: " + compact(reason, 80));
            return;
        }
        if (++whiteFlagRestarts > MAX_WHITE_FLAG_RESTARTS) {
            abandonTask(client, "white flag thrown " + MAX_WHITE_FLAG_RESTARTS + " times in a row: " + compact(reason, 80));
            whiteFlagRestarts = 0;
            return;
        }
        tell(client, "White flag (" + compact(reason, 80) + "); restarting with a fresh session ("
                + whiteFlagRestarts + "/" + MAX_WHITE_FLAG_RESTARTS + ").");
        submit(prompt);
    }

    /**
     * Returns the mod and the model session to a clean default state. Runs on disconnect and on game
     * exit, so nothing waits on an in-flight model request and nothing carries into the next world.
     */
    /** Writes the whole model/mod interaction context to logs/actionable without touching the debug sidebar. */
    private void captureContext(Minecraft client) {
        StringBuilder state = new StringBuilder();
        state.append("enabled=").append(enabled).append(", autonomous=").append(autonomous)
                .append("\ntask=").append(activeTask).append("\ngoal=").append(currentGoal)
                .append("\nplan_steps=").append(taskSteps).append(", current_step=").append(currentStep)
                .append("\nlast_action=").append(lastAction).append("\nlast_result=").append(lastResult)
                .append("\nerror_code=").append(errorCode).append(", last_error=").append(lastError)
                .append("\nbaritone=").append(baritoneStatus).append(", running=").append(baritoneCommandRunning)
                .append(", command=").append(currentBaritoneCommand)
                .append("\nachieved_goals=").append(achievedGoals).append("\nhistory=").append(history);
        try {
            if (client.player != null && client.level != null) {
                state.append("\nsynopsis=").append(WorldSynopsis.capture(client)).append("; ")
                        .append(baritoneProgress(client));
            }
            java.nio.file.Path file = ContextCapture.write(client.gameDirectory.toPath(), state.toString());
            tell(client, "Context saved to logs/actionable/" + file.getFileName());
        } catch (java.io.IOException | RuntimeException e) {
            tell(client, "Context capture failed: " + ActionPlanner.safeMessage(e));
        }
    }

    private void resetToDefaults(Minecraft client) {
        ContextCapture.clear();
        planner.cancelPending();
        generation++;
        try {
            cancelCraft(client);
            cancelBreak(client);
            cancelHomeWork(client);
        } catch (RuntimeException e) {
            ActionableLog.LOGGER.warn("[Actionable] reset_cancel_failed {}", ActionableLog.text(e.toString()));
        }
        if (eatPhase != 0) {
            endEat(client, false);
        }
        pendingCraft = null;
        pendingBreak = null;
        pendingSmelt = null;
        pendingStairs = null;
        pendingHunt = null;
        pendingMine = null;
        pendingJumpPlace = null;
        mineStart = null;
        baritoneCommandRunning = false;
        currentBaritoneCommand = "";
        activeTask = null;
        taskSteps = java.util.List.of();
        currentStep = 0;
        planning = false;
        decomposing = false;
        judging = false;
        choosingGoal = false;
        escaping = false;
        retreating = false;
        defending = false;
        returningHome = false;
        nightRoutine = false;
        nightJob = false;
        morningPending = false;
        autonomous = false;
        finalCheck = false;
        codeVerified = false;
        finalChecked = false;
        home = null;
        homeWorld = null;
        WorldProbe.home = null;
        houseBuiltSeen = false;
        startArea = null;
        history.clear();
        achievedGoals.clear();
        attemptedGoals.clear();
        avoidAreas.clear();
        lastAction = "";
        lastResult = "none";
        currentGoal = "Idle";
        errorCode = "none";
        lastError = "none";
        whiteFlagRestarts = 0;
        ticksUntilPlan = 0;
        ticksUntilGoal = 0;
    }

    private void noteAttempt(String goal) {
        attemptedGoals.addLast(goal);
        while (attemptedGoals.size() > GOAL_MEMORY) {
            attemptedGoals.removeFirst();
        }
    }

    private void noteAchieved(String goal) {
        if (goal == null || goal.isBlank() || achievedGoals.contains(goal)) {
            return;
        }
        achievedGoals.addLast(goal);
        while (achievedGoals.size() > GOAL_MEMORY) {
            achievedGoals.removeFirst();
        }
    }

    private boolean alreadyAchieved(String goal) {
        String key = goalKey(goal);
        if (key.isEmpty()) {
            return false;
        }
        for (String done : achievedGoals) {
            if (goalKey(done).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Collapses a goal to the resource it concerns, so "mine 8 stone" and "mine 20 stone" match. */
    static String goalKey(String goal) {
        java.util.regex.Matcher id = java.util.regex.Pattern
                .compile("([a-z0-9_]+:)?([a-z0-9_]+)").matcher(goal.toLowerCase(java.util.Locale.ROOT));
        String last = "";
        while (id.find()) {
            String word = id.group(2);
            if (!word.isBlank() && !word.matches("mine|get|goto|explore|find|pickup|the|a|an|to|minecraft")) {
                last = word;
            }
        }
        return last.replaceAll("s$", "").replace("cobblestone", "stone");
    }

    private static String joined(java.util.ArrayDeque<String> goals) {
        StringBuilder text = new StringBuilder();
        for (String goal : goals) {
            if (!text.isEmpty()) {
                text.append(" | ");
            }
            text.append(goal);
        }
        return text.toString();
    }

    private void requestDecomposition(Minecraft client) {
        String synopsis = WorldSynopsis.capture(client);
        var inventory = WorldProbe.inventory(java.util.Objects.requireNonNull(client.player));
        // Tech-tree goals get an exact plan from code; the model only decomposes free-form tasks.
        java.util.List<String> known = planFor(client, activeTask, inventory,
                WorldProbe.preferredLog(inventory, synopsis));
        if (!known.isEmpty()) {
            taskSteps = java.util.List.copyOf(known);
            announcePlan(client);
            currentStep = 0;
            ticksUntilPlan = 0;
            requestNextPlan(client);
            return;
        }
        long requestGeneration = generation;
        decomposing = true;
        currentGoal = "Breaking the task into steps";
        planner.decompose(activeTask, synopsis, briefing(client, synopsis)).whenComplete((steps, error) -> client.execute(() -> {
            if (requestGeneration != generation) {
                return;
            }
            decomposing = false;
            if (!enabled || activeTask == null || client.player == null) {
                return;
            }
            if (error != null || steps == null || steps.isEmpty()) {
                // Decomposition is an optimisation: fall back to treating the prompt as one step.
                taskSteps = java.util.List.of(activeTask);
                if (error != null) {
                    errorCode = plannerErrorCode(error);
                    lastError = compact(ActionPlanner.safeMessage(error), 140);
                }
                tell(client, "Could not split the task; working on it directly.");
            } else {
                taskSteps = ProgressPlanner.normalizeSteps(steps);
                announcePlan(client);
            }
            currentStep = 0;
            ticksUntilPlan = 0;
            requestNextPlan(client);
        }));
    }

    private void announcePlan(Minecraft client) {
        tell(client, "Plan (" + taskSteps.size() + " steps). Step 1: " + taskSteps.get(0));
        ActionableLog.LOGGER.info("[Actionable] plan task={} steps={}",
                ActionableLog.text(activeTask), ActionableLog.text(taskSteps.toString()));
        for (int index = 0; index < taskSteps.size(); index++) {
            java.util.Objects.requireNonNull(client.player).sendSystemMessage(
                    Component.literal("[Actionable] " + (index + 1) + ". " + taskSteps.get(index)));
        }
    }

    private void requestStepCheck(Minecraft client) {
        long requestGeneration = generation;
        judging = true;
        String objective = taskSteps.get(currentStep);
        String inventory = inventoryOf(WorldSynopsis.capture(client));
        planner.checkStep(objective, inventory, historyText()).whenComplete((done, error) -> client.execute(() -> {
            if (requestGeneration != generation) {
                return;
            }
            judging = false;
            if (!enabled || activeTask == null || client.player == null) {
                return;
            }
            // A failed verdict must not stall the task; fall through to normal planning.
            stepJudged = true;
            if (error == null && Boolean.TRUE.equals(done)) {
                advanceStep(client, objective);
                return;
            }
            ticksUntilPlan = 0;
            requestNextPlan(client);
        }));
    }

    private void advanceStep(Minecraft client, String finished) {
        remember("finished step " + (currentStep + 1), finished);
        if (currentStep >= taskSteps.size() - 1) {
            var connection = client.getConnection();
            if (connection != null) {
                finishTask(client, connection, "Final plan step finished: " + finished);
            }
            return;
        }
        codeVerified = false;
        stopBaritone(client);
        currentStep++;
        stepJudged = false;
        stepActionTaken = false;
        directStep = null;

        currentGoal = taskSteps.get(currentStep);
        lastAction = "";
        lastResult = "Step " + currentStep + " done (" + finished + "); starting next step";
        tell(client, "Step " + (currentStep + 1) + "/" + taskSteps.size() + ": " + taskSteps.get(currentStep));
        ticksUntilPlan = 1;
    }

    private static String inventoryOf(String synopsis) {
        StringBuilder items = new StringBuilder();
        for (String field : synopsis.split("; ")) {
            if (field.startsWith("totals=") || field.startsWith("inventory=")) {
                if (!items.isEmpty()) {
                    items.append("; ");
                }
                items.append(field);
            }
        }
        return items.toString();
    }

    private String planContext() {
        if (taskSteps.isEmpty()) {
            return "Plan: none available; work directly on the task.";
        }
        StringBuilder context = new StringBuilder("Plan steps:");
        for (int index = 0; index < taskSteps.size(); index++) {
            context.append("\n").append(index + 1).append(". ").append(taskSteps.get(index));
            if (index < currentStep) {
                context.append(" [done]");
            } else if (index == currentStep) {
                context.append("   <-- CURRENT STEP");
            }
        }
        return context.append("\nWork only on step ").append(currentStep + 1)
                .append(" of ").append(taskSteps.size()).append('.').toString();
    }

    private void remember(String action, String result) {
        stepJudged = false;
        if (action == null || action.isBlank()) {
            return;
        }
        String entry = compact(action, 60) + " -> " + compact(result, 80);
        if (entry.equals(history.peekLast())) {
            // A "wait" action repeats the previous action/result; don't crowd out real history.
            return;
        }
        history.addLast(entry);
        ContextCapture.event("action result", action + " -> " + result);
        ActionableLog.LOGGER.info("[Actionable] action={} result={}",
                ActionableLog.text(action), ActionableLog.text(result));
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    private String historyText() {
        StringBuilder text = new StringBuilder();
        for (String entry : history) {
            if (!text.isEmpty()) {
                text.append(" | ");
            }
            text.append(entry);
        }
        return text.toString();
    }

    private void finishTask(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection,
            String reason) {
        boolean codeRun = nightJob || escaping || nightRoutine || returningHome || pendingStairs != null
                || client.player == null;
        if (!finalCheck && !finalChecked && !codeVerified && !codeRun && activeTask != null) {
            beginFinalCheck(client, reason);
            return;
        }
        finalCheck = false;
        finalChecked = false;
        codeVerified = false;
        tell(client, "Task complete.");
        connection.sendChat("#cancel");
        baritoneCommandRunning = false;
        currentBaritoneCommand = "";
        lastResult = reason;
        currentGoal = "Complete";
        if (!nightJob) {
            noteAchieved(activeTask);
        }
        nightJob = false;
        escaping = false;
        retreating = false;
        mineStart = null;
        pendingMine = null;
        activeTask = null;
        taskSteps = java.util.List.of();
        ticksUntilGoal = 60;
    }

    /** Before a task counts as done, the model must look at the world once more and confirm the result. */
    private void beginFinalCheck(Minecraft client, String reason) {
        finalCheck = true;
        finalChecked = true;
        stopBaritone(client);
        lastAction = "final_check";
        lastResult = "FINAL_CHECK: (" + compact(reason, 80) + ") Before finishing, verify the ORIGINAL task \""
                + compact(activeTask, 200) + "\" against the inventory and world synopsis now (and the screenshot"
                + " attached if present). Return complete=true only if its end state really exists; otherwise"
                + " continue with the missing work.";
        tell(client, "Final check before finishing: " + compact(activeTask, 60));
        long shotGeneration = generation;
        Screenshots.capture(client, debugEnabled, image -> {
            if (shotGeneration == generation && image != null) {
                planner.attachImage(image);
            }
        });
        ticksUntilPlan = 15;
    }

    private void requestNextPlan(Minecraft client) {
        if (client.player == null || client.level == null || client.getConnection() == null || activeTask == null) {
            ticksUntilPlan = 20;
            return;
        }
        boolean useSteps = !taskSteps.isEmpty() && !finalCheck;
        if (leaveWater(client)) {
            ticksUntilPlan = 60;
            return;
        }
        if (useSteps) {
            // Quantity, house and bed steps are judged from the inventory and world, without a model call.
            String step = taskSteps.get(currentStep);
            java.util.Optional<Boolean> done = ProgressPlanner.stepSatisfied(step,
                    WorldProbe.inventory(client.player), tableNearby(client), facts(client));
            if (done.orElse(false)) {
                codeVerified = true;
                advanceStep(client, step);
                return;
            }
            if (done.isPresent()) {
                stepJudged = true;
            }
        }
        if (useSteps && !history.isEmpty() && !stepJudged) {
            requestStepCheck(client);
            return;
        }
        if (useSteps && Recovery.replannable(lastResult)
                && replanFromInventory(client, lastResult)) {
            return;
        }
        if (useSteps && !stepActionTaken && executeStepDirectly(client)) {
            return;
        }
        if (useSteps && directStep != null && continueDirectStep(client)) {
            return;
        }
        long requestGeneration = generation;
        planning = true;
        String synopsis = WorldSynopsis.capture(client) + "; " + baritoneProgress(client);
        String taskWithOrigin = activeTask + "\nTask prompt origin xyz: "
                + (taskOrigin == null ? "unknown" : taskOrigin.getX() + "," + taskOrigin.getY() + "," + taskOrigin.getZ());
        planner.plan(taskWithOrigin, synopsis, lastAction, lastResult, planContext(), historyText())
                .whenComplete((plan, error) -> client.execute(() -> {
            if (requestGeneration != generation) {
                return;
            }
            planning = false;
            if (!enabled || client.player == null) {
                return;
            }
            var connection = client.getConnection();
            if (client.level == null || connection == null) {
                activeTask = null;
                errorCode = "GAME_SESSION_ENDED";
                lastError = "World or server connection ended while waiting for a plan";
                lastResult = lastError;
                tell(client, "Action stopped [" + errorCode + "]: " + lastError);
                return;
            }
            if (error != null) {
                errorCode = plannerErrorCode(error);
                lastError = compact(ActionPlanner.safeMessage(error), 140);
                lastResult = errorCode + ": " + lastError;
                tell(client, "Planning failed [" + errorCode + "]: " + lastError);
                ticksUntilPlan = 200;
                return;
            }
            errorCode = "none";
            lastError = "none";
            currentGoal = plan.goal();
            tell(client, "Plan: " + plan.summary());
            if (plan.complete()) {
                finishTask(client, connection, "Planner marked the task complete");
                return;
            }
            if (finalCheck) {
                // The model found work left to do: continue freely on the original task.
                finalCheck = false;
                taskSteps = java.util.List.of();
                currentStep = 0;
            } else if (plan.stepComplete() && !taskSteps.isEmpty()) {
                codeVerified = false;
                advanceStep(client, taskSteps.get(currentStep));
                return;
            }
            if (plan.action().isEmpty()) {
                lastResult = "No valid action returned. " + plan.summary();
                errorCode = "PLAN_NO_VALID_ACTION";
                lastError = compact(plan.summary(), 140);
                tell(client, "No valid action [" + errorCode + "]: " + lastError);
                ticksUntilPlan = Recovery.delayAfter("", "PLAN_NO_VALID_ACTION");
                return;
            }

            ActionPlanner.PlannedAction action = plan.action().orElseThrow();
            switch (action.type()) {
                case "attack" -> {
                    stopBaritone(client);
                    lastAction = "attack entity_id=" + action.command();
                    lastResult = MeleeCombat.attack(client, Integer.parseInt(action.command()));
                    if (lastResult.startsWith("ATTACK_FAILED")) {
                        errorCode = lastResult;
                        lastError = lastResult;
                    } else {
                        errorCode = "none";
                        lastError = "none";
                    }
                    tell(client, lastResult);
                }
                case "wait" -> {
                    lastResult = baritoneCommandRunning
                            ? "Planner chose to wait; " + baritoneProgress(client)
                            : "Planner chose no input; reevaluate after next observation";
                    tell(client, baritoneCommandRunning ? "Waiting on current Baritone task." : "Waiting for next observation.");
                }
                case "white_flag" -> {
                    lastAction = "white_flag";
                    whiteFlag(client, action.query());
                    return;
                }
                case "craft" -> startCraft(client, action.block(), Integer.parseInt(action.command()));
                case "lookup" -> {
                    lastAction = "lookup " + action.query();
                    lastResult = GameKnowledge.lookup(client, action.query());
                    tell(client, lastResult);
                    ticksUntilPlan = 1;
                }
                case "break_block" -> {
                    stopBaritone(client);
                    lastAction = "break block at " + action.x() + "," + action.y() + "," + action.z();
                    BlockBreaker.Start start = BlockBreaker.start(client, action.x(), action.y(), action.z(), clientTick);
                    if (start.breaker() == null) {
                        lastResult = start.result();
                        reportBlockResult(client);
                    } else {
                        pendingBreak = start.breaker();
                        lastResult = "Breaking " + pendingBreak.describe();
                        tell(client, lastResult);
                    }
                }
                case "use_block" -> {
                    stopBaritone(client);
                    lastAction = "use_block " + action.x() + "," + action.y() + "," + action.z();
                    lastResult = MenuActions.useBlock(client, action.x(), action.y(), action.z());
                    reportBlockResult(client);
                    ticksUntilPlan = 10;
                }
                case "menu" -> {
                    lastAction = "menu " + action.command() + (action.x() >= 0 ? " " + action.x() : "")
                            + (action.y() >= 0 ? "->" + action.y() : "");
                    lastResult = MenuActions.menu(client, action.command(), action.x(), action.y());
                    reportBlockResult(client);
                    ticksUntilPlan = 10;
                }
                case "screenshot" -> {
                    lastAction = "screenshot";
                    lastResult = "capturing screenshot; the image is attached to your next turn";
                    long shotGeneration = generation;
                    Screenshots.capture(client, debugEnabled, image -> {
                        if (shotGeneration == generation && image != null) {
                            planner.attachImage(image);
                        }
                    });
                    tell(client, "Taking a screenshot for the model.");
                    ticksUntilPlan = 15;
                }
                case "place_block" -> {
                    stopBaritone(client);
                    lastAction = "place " + action.block() + " near " + action.x() + "," + action.y() + "," + action.z();
                    lastResult = placeNearby(client, action);
                    reportBlockResult(client);
                }
                case "travel" -> travel(client, connection, action.command());
                case "baritone" -> {
                    String command = homeRangeCommand(client, taskSteps.isEmpty() ? action.command()
                            : ProgressPlanner.alignMineQuantity(action.command(), taskSteps.get(currentStep)));
                    if (command == null) {
                        reportBlockResult(client);
                    } else if (command.startsWith("mine ")) {
                        startMine(client, connection, command);
                    } else {
                        executeBaritone(client, connection, command);
                    }
                }
                default -> {
                    lastResult = "Unsupported action type: " + action.type();
                    errorCode = "ACTION_TYPE_UNSUPPORTED";
                    lastError = lastResult;
                    tell(client, lastResult);
                }
            }
            remember(lastAction, lastResult);
            // Actions that set their own delay above (menus, screenshots) keep it.
            if (!java.util.Set.of("use_block", "menu", "screenshot").contains(action.type())) {
                ticksUntilPlan = Recovery.delayAfter(action.type(), lastResult);
            }
        }));
    }

    /**
     * Runs the current step itself when it already names an action ("mine 3 minecraft:oak_log",
     * "craft 4 minecraft:stick", "place minecraft:crafting_table nearby"), saving a model round trip.
     * Only the first attempt is direct; failures and waiting go back to the model.
     */
    private boolean executeStepDirectly(Minecraft client) {
        var connection = client.getConnection();
        var player = client.player;
        if (connection == null || player == null || client.level == null) {
            return false;
        }
        String step = taskSteps.get(currentStep);
        if (!step.equals(directStep)) {
            // First attempt at this step: start measuring its progress.
            directStep = step;
            directBest = directProgress(client, step);
            directProgressAt = clientTick;
            directResends = 0;
        }
        java.util.Optional<ProgressPlanner.QuantifiedStep> parsed = ProgressPlanner.parse(step);
        if (parsed.isPresent() && parsed.get().verb().equals("craft")) {
            startCraft(client, parsed.get().item(), parsed.get().count());
            ticksUntilPlan = 20;
        } else if (step.equalsIgnoreCase(ProgressPlanner.GATHER_FOOD)) {
            if (baritoneBusy(client)) {
                ticksUntilPlan = 100;
                return true;
            }
            lastAction = step;
            pendingHunt = Hunter.forFood(FoodPlan.STOCK_WANTED, clientTick);
            lastResult = "Hunting food animals until carrying " + FoodPlan.STOCK_WANTED + " hunger points of food";
            tell(client, lastResult);
        } else if (parsed.isPresent() && parsed.get().verb().equals("hunt")) {
            if (baritoneBusy(client)) {
                // Still walking to a new spot to look for sheep; hunt once there.
                ticksUntilPlan = 100;
                return true;
            }
            lastAction = "hunt " + parsed.get().count() + " " + parsed.get().item();
            pendingHunt = new Hunter(parsed.get().item(), parsed.get().count(), clientTick);
            lastResult = "Hunting sheep until holding " + parsed.get().count() + " " + parsed.get().item();
            tell(client, lastResult);
        } else if (step.equalsIgnoreCase(ProgressPlanner.BUILD_HOUSE)) {
            startHouseBuild(client, connection);
            ticksUntilPlan = lastResult.contains("_FAILED") ? 20 : 200;
        } else if (step.equalsIgnoreCase(ProgressPlanner.GO_HOME)) {
            if (home == null) {
                return false;
            }
            BlockPos stand = homeCell(HouseDesign.DOOR_STAND);
            executeBaritone(client, connection, "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            lastAction = ProgressPlanner.GO_HOME;
            lastResult = "Walking home to use the house crafting table";
            ticksUntilPlan = 200;
        } else if (step.equalsIgnoreCase(ProgressPlanner.LAY_FLOOR)) {
            if (home == null || !fixHouseFloor(client, connection)) {
                return false;
            }
        } else if (step.equalsIgnoreCase(ProgressPlanner.PLACE_BED)) {
            placeInHouse(client, connection, step, TechTree.WHITE_BED, HouseDesign.BED_STAND, HouseDesign.BED_FOOT);
        } else if (step.equalsIgnoreCase(ProgressPlanner.PLACE_TABLE_IN_HOUSE)) {
            placeInHouse(client, connection, step, TechTree.CRAFTING_TABLE, HouseDesign.DOOR_STAND, HouseDesign.TABLE_CELL);
        } else if (step.equalsIgnoreCase(NeedsPlanner.PLACE_FURNACE)) {
            placeInHouse(client, connection, step, "minecraft:furnace", HouseDesign.DOOR_STAND, HouseDesign.FURNACE_CELL);
        } else if (step.equalsIgnoreCase(NeedsPlanner.SMELT)) {
            if (home == null || !startSmelt(client, connection)) {
                return false;
            }
        } else if (step.startsWith("dig house mine for ")) {
            String[] words = step.split(" ");
            if (home == null || words.length != 6) {
                return false;
            }
            pendingStairs = new StairMiner(words[5], Integer.parseInt(words[4]), home, clientTick);
            lastAction = step;
            lastResult = "Digging the " + pendingStairs.describe();
            tell(client, lastResult);
        } else if (step.equalsIgnoreCase(ProgressPlanner.PLACE_DOOR)) {
            if (home == null || !hangHouseDoor(client, connection, step)) {
                return false;
            }
        } else if (parsed.isPresent() && parsed.get().verb().equals("mine")
                && ActionPlanner.validateCommand("mine " + parsed.get().count() + " " + parsed.get().item()).isPresent()) {
            startMine(client, connection, "mine " + parsed.get().count() + " " + parsed.get().item());
            ticksUntilPlan = lastResult.startsWith("MINE_FAILED") ? 20 : 200;
        } else if (step.startsWith("place " + TechTree.CRAFTING_TABLE)) {
            java.util.List<BlockPos> spots = WorldProbe.placementCandidates(client.level, player.blockPosition());
            if (spots.isEmpty()) {
                return false;
            }
            BlockPos spot = spots.get(0);
            stopBaritone(client);
            lastAction = "place " + TechTree.CRAFTING_TABLE + " at " + spot.getX() + "," + spot.getY() + "," + spot.getZ();
            lastResult = placeWithFallback(client, new ActionPlanner.PlannedAction("place_block", "",
                    TechTree.CRAFTING_TABLE, spot.getX(), spot.getY(), spot.getZ(), ""));
            reportBlockResult(client);
            ticksUntilPlan = lastResult.startsWith("BLOCK_PLACE_DEFERRED") ? 1 : 10;
        } else {
            return false;
        }
        stepActionTaken = true;
        currentGoal = step;
        remember(lastAction, lastResult);
        return true;
    }

    /**
     * Fixes one cell of the house floor: a hole or water below floor level gets a plank, and water
     * where the player walks gets a plank that is then broken, leaving dry air. Walks within reach of
     * the cell first. Returns false when there is nothing to fix.
     */
    private boolean fixHouseFloor(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        var player = java.util.Objects.requireNonNull(client.player);
        var level = java.util.Objects.requireNonNull(client.level);
        BlockPos cell = WorldProbe.houseFloorProblem(level, home);
        if (cell == null) {
            return false;
        }
        lastAction = ProgressPlanner.LAY_FLOOR;
        var inventory = WorldProbe.inventory(player);
        String planks = WorldProbe.mostHeldPlanks(inventory, WorldProbe.preferredLog(inventory, ""));
        if (inventory.getOrDefault(planks, 0) == 0) {
            lastResult = "BLOCK_PLACE_FAILED_MATERIAL_MISSING: planks are needed to fill the house floor";
            reportBlockResult(client);
            ticksUntilPlan = 1;
            return true;
        }
        if (!MiningPolicy.inReach(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                cell.getX(), cell.getY(), cell.getZ()) || player.isInWater()) {
            BlockPos stand = WorldProbe.dryStandNear(level, cell);
            if (stand == null) {
                stand = homeCell(HouseDesign.BED_STAND);
            }
            executeBaritone(client, connection, "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            lastAction = ProgressPlanner.LAY_FLOOR;
            lastResult = "Walking within reach of the house floor at " + cell.getX() + "," + cell.getY() + "," + cell.getZ();
            ticksUntilPlan = 200;
            return true;
        }
        stopBaritone(client);
        boolean walkable = cell.getY() >= home[1];
        lastResult = placeBlock(client, new ActionPlanner.PlannedAction("place_block", "", planks,
                cell.getX(), cell.getY(), cell.getZ(), ""));
        if (walkable && lastResult.startsWith("placement request sent")) {
            // The plank displaced the water; break it again so the cell is dry, walkable air.
            BlockBreaker.Start start = BlockBreaker.start(client, cell.getX(), cell.getY(), cell.getZ(), clientTick + 5);
            if (start.breaker() != null) {
                pendingBreak = start.breaker();
            }
            lastResult += "; clearing it again to leave dry air";
        }
        ActionableLog.LOGGER.info("[Actionable] floor_fix at={},{},{} result={}", cell.getX(), cell.getY(), cell.getZ(),
                ActionableLog.text(lastResult));
        reportBlockResult(client);
        ticksUntilPlan = 5;
        return true;
    }

    /**
     * Hangs the house door properly: first takes back doors placed anywhere else around the house, then
     * clears whatever fills the doorway (upper block first), then places the door in the doorway from
     * just inside. Returns false when the doorway already has its door and nothing is out of place.
     */
    private boolean hangHouseDoor(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection,
            String step) {
        var player = java.util.Objects.requireNonNull(client.player);
        var level = java.util.Objects.requireNonNull(client.level);
        lastAction = step;
        java.util.List<BlockPos> strays = WorldProbe.strayDoors(level, home);
        if (!strays.isEmpty()) {
            return breakForDoor(client, strays.get(0), "Taking back a misplaced door");
        }
        BlockPos lower = homeCell(HouseDesign.DOOR_CELL);
        BlockPos upper = lower.above();
        for (BlockPos cell : java.util.List.of(upper, lower)) {
            var state = level.getBlockState(cell);
            if (!state.isAir() && !(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
                return breakForDoor(client, cell, "Clearing the doorway for the door");
            }
        }
        if (level.getBlockState(lower).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            return false;
        }
        String door = WorldProbe.inventory(player).keySet().stream().filter(TechTree::isDoor).findFirst()
                .orElse("minecraft:oak_door");
        placeInHouse(client, connection, step, door, HouseDesign.DOOR_STAND, HouseDesign.DOOR_CELL);
        return true;
    }

    /**
     * The code plan for a goal. Doors placed in the wrong spots are taken back by the door step itself,
     * so with any lying around the plan skips crafting new ones.
     */
    private java.util.List<String> planFor(Minecraft client, String goal, java.util.Map<String, Integer> inventory,
            String preferredLog) {
        if (goal.equalsIgnoreCase(ProgressPlanner.PLACE_DOOR) && home != null && client.level != null
                && !WorldProbe.strayDoors(client.level, home).isEmpty()) {
            return java.util.List.of(ProgressPlanner.PLACE_DOOR);
        }
        return ProgressPlanner.stepsForGoal(goal, inventory, tableAccess(client), preferredLog);
    }

    private boolean breakForDoor(Minecraft client, BlockPos pos, String why) {
        stopBaritone(client);
        BlockBreaker.Start start = BlockBreaker.start(client, pos.getX(), pos.getY(), pos.getZ(), clientTick);
        if (start.breaker() == null) {
            lastResult = start.result();
            reportBlockResult(client);
            ticksUntilPlan = 5;
            return true;
        }
        pendingBreak = start.breaker();
        lastResult = why + ": breaking " + pendingBreak.describe();
        ActionableLog.LOGGER.info("[Actionable] door_fix {}", ActionableLog.text(lastResult));
        tell(client, lastResult);
        return true;
    }

    /** Problems left before the house door is right: stray doors and blocks in the doorway. */
    private int doorProblems(Minecraft client) {
        var level = java.util.Objects.requireNonNull(client.level);
        int problems = WorldProbe.strayDoors(level, home).size();
        BlockPos lower = homeCell(HouseDesign.DOOR_CELL);
        for (BlockPos cell : java.util.List.of(lower, lower.above())) {
            var state = level.getBlockState(cell);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
                problems++;
            }
        }
        return problems;
    }

    /** How far along a code-run step is: items held, house blocks placed, or closeness to its spot. */
    private int directProgress(Minecraft client, String step) {
        var player = java.util.Objects.requireNonNull(client.player);
        java.util.Optional<ProgressPlanner.QuantifiedStep> parsed = ProgressPlanner.parse(step);
        if (parsed.isPresent()) {
            return TechTree.held(WorldProbe.inventory(player), parsed.get().item());
        }
        if (home == null || client.level == null) {
            return 0;
        }
        if (step.equalsIgnoreCase(ProgressPlanner.BUILD_HOUSE)) {
            return (int) Math.round(WorldProbe.houseCompletion(client.level, home) * HouseDesign.planksNeeded());
        }
        if (step.equalsIgnoreCase(ProgressPlanner.LAY_FLOOR)) {
            // Each fixed cell is progress; the count of remaining problem cells only goes down.
            int remaining = 0;
            for (int[] cell : HouseDesign.floorCells()) {
                BlockPos pos = homeCell(cell);
                var state = client.level.getBlockState(pos);
                if (!state.getFluidState().isEmpty() || !state.isFaceSturdy(client.level, pos, Direction.UP)) {
                    remaining++;
                }
            }
            for (int[] cell : HouseDesign.dryCells()) {
                if (!client.level.getBlockState(homeCell(cell)).getFluidState().isEmpty()) {
                    remaining++;
                }
            }
            return -remaining;
        }
        if (step.startsWith("dig house mine for ")) {
            String[] words = step.split(" ");
            return words.length == 6 ? TechTree.held(WorldProbe.inventory(player), words[5]) : 0;
        }
        if (step.equalsIgnoreCase(ProgressPlanner.GATHER_FOOD)) {
            return FoodPlan.stock(WorldProbe.inventory(player));
        }
        int[] stand = step.equalsIgnoreCase(ProgressPlanner.PLACE_BED) ? HouseDesign.BED_STAND : HouseDesign.DOOR_STAND;
        if (step.equalsIgnoreCase(ProgressPlanner.PLACE_DOOR)) {
            // Each stray door taken back or doorway block cleared counts as progress.
            return -doorProblems(client) * 10 - (int) Math.round(Math.sqrt(player.blockPosition().distSqr(homeCell(stand))));
        }
        return -(int) Math.round(Math.sqrt(player.blockPosition().distSqr(homeCell(stand))));
    }

    private BlockPos homeCell(int[] relative) {
        return new BlockPos(home[0] + relative[0], home[1] + relative[1], home[2] + relative[2]);
    }

    private boolean baritoneBusy(Minecraft client) {
        return baritoneCommandRunning || BaritoneProbe.query().map(BaritoneProbe.State::active).orElse(false);
    }

    /**
     * Rebuilds the plan from the current inventory after a failure the planner understands (missing
     * table, tool or ingredient). Re-plans the whole task when it is a tech-tree goal, otherwise only
     * the failed step. Returns false when code cannot plan it, so the model takes over.
     */
    private boolean replanFromInventory(Minecraft client, String failure) {
        var inventory = WorldProbe.inventory(java.util.Objects.requireNonNull(client.player));
        String log = WorldProbe.preferredLog(inventory, "");
        java.util.List<String> steps = planFor(client, activeTask, inventory, log);
        if (steps.isEmpty()) {
            java.util.List<String> fix = planFor(client, taskSteps.get(currentStep), inventory, log);
            if (fix.isEmpty()) {
                return false;
            }
            java.util.List<String> spliced = new java.util.ArrayList<>(fix);
            spliced.addAll(taskSteps.subList(currentStep + 1, taskSteps.size()));
            steps = spliced;
        }
        replans++;
        ActionableLog.LOGGER.info("[Actionable] replan count={} failure={}", replans,
                ActionableLog.text(compact(failure, 120)));
        taskSteps = java.util.List.copyOf(steps);
        currentStep = 0;
        stepJudged = false;
        stepActionTaken = false;
        directStep = null;
        // Replacing the failure text keeps the same failure from triggering another re-plan.
        lastResult = "Re-planned from inventory after: " + compact(failure, 80);
        tell(client, "Re-planned after " + compact(failure, 40));
        announcePlan(client);
        ticksUntilPlan = 1;
        return true;
    }

    /**
     * The model's place_block: the coordinates are only a hint. Tries the hint, then workable cells
     * around the player (outward, up, down), and jumps to place beneath the player when boxed in.
     */
    private String placeNearby(Minecraft client, ActionPlanner.PlannedAction action) {
        if (client.player == null || client.level == null) {
            return "BLOCK_PLACE_FAILED_NO_WORLD: player world is unavailable";
        }
        if (TechTree.isDoor(action.block()) || action.block().endsWith("_bed")) {
            return placeWithFallback(client, action);
        }
        var level = client.level;
        var player = client.player;
        BlockPos hint = new BlockPos(action.x(), action.y(), action.z());
        java.util.List<BlockPos> spots = new java.util.ArrayList<>();
        if (WorldProbe.placeableCell(level, player, hint, player.getEyePosition())) {
            spots.add(hint);
        }
        for (BlockPos spot : WorldProbe.placementSpots(level, player)) {
            if (!spot.equals(hint)) {
                spots.add(spot);
            }
        }
        String last = "";
        int tries = 0;
        for (BlockPos spot : spots) {
            String result = placeBlock(client, new ActionPlanner.PlannedAction("place_block", "", action.block(),
                    spot.getX(), spot.getY(), spot.getZ(), ""));
            if (!Recovery.badPlacementSpot(result)) {
                lastAction = "place " + action.block() + " at " + spot.getX() + "," + spot.getY() + "," + spot.getZ();
                return result;
            }
            last = result;
        }
        // Boxed in (for example a 1x1 hole): jump and place the block beneath the player.
        BlockPos feet = player.blockPosition();
        String jump = startJumpPlace(client, new ActionPlanner.PlannedAction("place_block", "", action.block(),
                feet.getX(), feet.getY(), feet.getZ(), ""));
        return jump.startsWith("Jump started") || last.isBlank() ? jump : jump + " (nearby spots failed: "
                + compact(last, 80) + ")";
    }

    /**
     * Places at the requested spot, or at the nearest workable spot around the player when the
     * requested one is out of reach, occupied, or inside the player.
     */
    private String placeWithFallback(Minecraft client, ActionPlanner.PlannedAction action) {
        if (TechTree.isDoor(action.block()) && client.level != null
                && !DoorRule.fits(WorldProbe.doorProbe(client.level), action.x(), action.y(), action.z())) {
            return "DOOR_FAILED_BAD_SPOT: " + DoorRule.RULE + "."
                    + (home == null ? "" : " Use the step \"place door in house\" for the house doorway at "
                    + homeCell(HouseDesign.DOOR_CELL).toShortString() + ".");
        }
        String result = placeBlock(client, action);
        // Doors and beds go exactly where intended or nowhere; a nearby free cell is never right for them.
        boolean exact = TechTree.isDoor(action.block()) || action.block().endsWith("_bed");
        if (exact || !Recovery.badPlacementSpot(result) || client.player == null || client.level == null) {
            return result;
        }
        BlockPos requested = new BlockPos(action.x(), action.y(), action.z());
        int tries = 0;
        for (BlockPos spot : WorldProbe.placementCandidates(client.level, client.player.blockPosition())) {
            if (spot.equals(requested)) {
                continue;
            }
            String retry = placeBlock(client, new ActionPlanner.PlannedAction("place_block", "", action.block(),
                    spot.getX(), spot.getY(), spot.getZ(), ""));
            if (!Recovery.badPlacementSpot(retry)) {
                lastAction = "place " + action.block() + " at " + spot.getX() + "," + spot.getY() + "," + spot.getZ();
                return retry + " (requested spot unusable: " + compact(result, 80) + ")";
            }
        }
        return result;
    }

    /**
     * Keeps model-chosen movement near home while the player lacks gear: travel becomes a walk to a
     * point near home, and a goto beyond the range is refused. Returns null when refused.
     */
    private String homeRangeCommand(Minecraft client, String command) {
        int[] anchor = anchor();
        if (anchor == null || rangeExempt(client)) {
            return command;
        }
        String[] parts = command.split("\\s+");
        if (parts[0].equals("goto") && parts.length == 4
                && HomeRange.outside(anchor, false, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]), 0)) {
            lastAction = command;
            lastResult = "GOTO_FAILED_OUTSIDE_HOME_RANGE: " + command + " is beyond " + HomeRange.RADIUS
                    + " blocks horizontally or " + HomeRange.VERTICAL + " vertically from home; stay in that range"
                    + " until you have a stone pickaxe and a stone sword";
            return null;
        }
        return command;
    }

    /**
     * Builds the starter house with Baritone from a generated schematic, next to the player. The house
     * corner becomes home and is saved for this world.
     */
    private void startHouseBuild(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        var player = java.util.Objects.requireNonNull(client.player);
        var inventory = WorldProbe.inventory(player);
        String planks = WorldProbe.mostHeldPlanks(inventory, WorldProbe.preferredLog(inventory, ""));
        lastAction = ProgressPlanner.BUILD_HOUSE;
        int held = inventory.getOrDefault(planks, 0);
        if (held < HouseDesign.planksNeeded()) {
            lastResult = "BUILD_FAILED_NOT_ENOUGH_PLANKS: the house needs " + HouseDesign.planksNeeded()
                    + " planks of one wood type; holding " + held + " " + planks;
            reportBlockResult(client);
            return;
        }
        BlockPos feet = player.blockPosition();
        int[] origin = home != null ? home : new int[] {feet.getX() + 1, feet.getY(), feet.getZ() + 1};
        try {
            java.nio.file.Path dir = client.gameDirectory.toPath().resolve("schematics");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(HouseDesign.SCHEMATIC_FILE), HouseDesign.schematic(planks,
                    net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()));
        } catch (java.io.IOException failed) {
            lastResult = "BUILD_FAILED_SCHEMATIC: could not write the house schematic: " + failed.getMessage();
            reportBlockResult(client);
            return;
        }
        if (home == null) {
            home = origin;
            WorldProbe.home = origin;
            HomeStore.save(client, origin);
            ActionableLog.LOGGER.info("[Actionable] home_set at={},{},{}", origin[0], origin[1], origin[2]);
        }
        executeBaritone(client, connection, "build " + HouseDesign.SCHEMATIC_FILE + " " + origin[0] + " "
                + origin[1] + " " + origin[2]);
        lastAction = ProgressPlanner.BUILD_HOUSE;
        lastResult = "Building a " + HouseDesign.WIDTH + "x" + HouseDesign.LENGTH + " " + planks + " house at "
                + origin[0] + "," + origin[1] + "," + origin[2];
        tell(client, lastResult);
    }

    /**
     * Walks to a fixed spot inside the house and places a block at a fixed cell: the bed, the
     * crafting table and the door each have their own place, so they never block one another.
     */
    private void placeInHouse(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection,
            String step, String item, int[] standCell, int[] targetCell) {
        var player = java.util.Objects.requireNonNull(client.player);
        lastAction = step;
        if (home == null) {
            lastResult = "HOUSE_PLACE_FAILED_NO_HOUSE: build the house first";
            reportBlockResult(client);
            ticksUntilPlan = 20;
            return;
        }
        if (WorldProbe.inventory(player).getOrDefault(item, 0) == 0) {
            lastResult = "BLOCK_PLACE_FAILED_MATERIAL_MISSING: " + item + " is not in inventory";
            reportBlockResult(client);
            ticksUntilPlan = 1;
            return;
        }
        BlockPos stand = homeCell(standCell);
        BlockPos target = homeCell(targetCell);
        if (player.blockPosition().distSqr(stand) > 1) {
            executeBaritone(client, connection, "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            lastAction = step;
            lastResult = "Walking into the house to place " + item;
            ticksUntilPlan = 200;
            return;
        }
        stopBaritone(client);
        lastResult = placeBlock(client, new ActionPlanner.PlannedAction("place_block", "", item,
                target.getX(), target.getY(), target.getZ(), ""));
        reportBlockResult(client);
        ticksUntilPlan = lastResult.startsWith("BLOCK_PLACE_DEFERRED") ? 1 : 10;
    }

    private void tickBreak(Minecraft client) {
        if (pendingBreak == null || defending || eatPhase != 0) {
            return;
        }
        var connection = client.getConnection();
        if (!enabled || connection == null) {
            cancelBreak(client);
            return;
        }
        String result = pendingBreak.tick(client, clientTick,
                command -> executeBaritone(client, connection, command), () -> stopBaritone(client));
        if (result == null) {
            return;
        }
        pendingBreak = null;
        if (unstickResume != null) {
            // A snag was cleared mid-walk: carry on with the same command, without a planning round.
            String resume = unstickResume;
            unstickResume = null;
            ActionableLog.LOGGER.info("[Actionable] unstick_done result={} resume={}",
                    ActionableLog.text(result), ActionableLog.text(resume));
            executeBaritone(client, connection, resume);
            return;
        }
        lastAction = "break_block";
        lastResult = result;
        reportBlockResult(client);
        remember(lastAction, lastResult);
        ticksUntilPlan = 1;
    }

    /**
     * Frees the player when Baritone is stuck on a vine or similar. Climbing a vine keeps changing the
     * player's height, so stalls are measured sideways only; after five seconds without sideways
     * movement, a snag next to the player is broken and the same Baritone command resumes.
     */
    private void tickUnstick(Minecraft client) {
        var player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        if (player.getBlockX() != lastX || player.getBlockZ() != lastZ || !baritoneCommandRunning) {
            lastX = player.getBlockX();
            lastZ = player.getBlockZ();
            lastSidewaysMoveAt = clientTick;
            return;
        }
        if (!enabled || pendingBreak != null || clientTick % 10 != 0
                || clientTick - lastSidewaysMoveAt < Recovery.SNAG_STALL_TICKS
                || currentBaritoneCommand.startsWith("build")) {
            return;
        }
        BlockPos snag = WorldProbe.snagNear(client.level, player.blockPosition());
        if (snag == null) {
            return;
        }
        snagAttempts = snag.equals(lastSnag) ? snagAttempts + 1 : 1;
        lastSnag = snag;
        if (snagAttempts > Recovery.SNAG_ATTEMPTS) {
            return;
        }
        BlockBreaker.Start start = BlockBreaker.start(client, snag.getX(), snag.getY(), snag.getZ(), clientTick);
        if (start.breaker() == null) {
            return;
        }
        String id = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(snag).getBlock()).toString();
        ActionableLog.LOGGER.info("[Actionable] unstick block={} at={},{},{} command={}", ActionableLog.text(id),
                snag.getX(), snag.getY(), snag.getZ(), ActionableLog.text(currentBaritoneCommand));
        unstickResume = currentBaritoneCommand;
        // Keep pending mine and step state; only the Baritone command pauses while the snag is broken.
        String pending = pendingMine;
        stopBaritone(client);
        pendingMine = pending;
        pendingBreak = start.breaker();
        lastSidewaysMoveAt = clientTick;
        tell(client, "Stuck on " + id + "; clearing it.");
    }

    private void tickHunt(Minecraft client) {
        if (pendingHunt == null || defending || eatPhase != 0) {
            return;
        }
        var connection = client.getConnection();
        if (!enabled || connection == null) {
            pendingHunt = null;
            return;
        }
        String hunt = pendingHunt.item() == null ? ProgressPlanner.GATHER_FOOD : "hunt " + pendingHunt.item();
        String result = pendingHunt.tick(client, clientTick, anchor(),
                command -> executeBaritone(client, connection, command), () -> stopBaritone(client));
        if (result == null) {
            return;
        }
        pendingHunt = null;
        lastAction = hunt;
        lastResult = result;
        reportBlockResult(client);
        remember(lastAction, lastResult);
        ticksUntilPlan = 1;
        ActionableLog.LOGGER.info("[Actionable] hunt_result {}", ActionableLog.text(result));
    }

    /** Holds jump while idle in water so the player floats instead of sinking. */
    private void tickWater(Minecraft client) {
        var player = client.player;
        // Ask Baritone itself: holding jump while it swims toward something makes the player bob in place.
        boolean baritoneMoving = player != null && player.isInWater()
                && BaritoneProbe.query().map(BaritoneProbe.State::active).orElse(baritoneCommandRunning);
        boolean hold = working() && player != null && player.isInWater() && !baritoneMoving && !player.isSleeping();
        if (hold) {
            client.options.keyJump.setDown(true);
            holdingJump = true;
        } else if (holdingJump) {
            client.options.keyJump.setDown(false);
            holdingJump = false;
        }
    }

    /** Swims to the nearest land when in water with nothing moving the player; true when sent. */
    private boolean leaveWater(Minecraft client) {
        var player = client.player;
        var level = client.level;
        var connection = client.getConnection();
        if (player == null || level == null || connection == null || !player.isInWater() || baritoneCommandRunning) {
            return false;
        }
        BlockPos land = WorldProbe.nearestLand(level, player.blockPosition(), 16);
        if (land == null) {
            return false;
        }
        executeBaritone(client, connection, "goto " + land.getX() + " " + land.getY() + " " + land.getZ());
        lastResult = "In water: swimming to land at " + land.getX() + "," + land.getY() + "," + land.getZ()
                + " before planning";
        ActionableLog.LOGGER.info("[Actionable] leave_water to={},{},{}", land.getX(), land.getY(), land.getZ());
        tell(client, "Swimming to land before planning.");
        return true;
    }

    /**
     * At night, stops work and goes home: sleeps in the bed when there is one, otherwise waits inside
     * the house. Without a house or bed it keeps working, as before.
     */
    private void tickNight(Minecraft client) {
        var player = client.player;
        var level = client.level;
        if (!enabled || !autonomous || player == null || level == null || client.getConnection() == null
                || clientTick % 20 != 0) {
            return;
        }
        if (!level.isDarkOutside()) {
            if (nightRoutine && !morningPending) {
                nightRoutine = false;
                nightJob = false;
                cancelSmelt(client);
                if (pendingStairs != null) {
                    // Climb out of the mine before the day's work starts.
                    pendingStairs.abort();
                    morningPending = true;
                    return;
                }
                activeTask = null;
                taskSteps = java.util.List.of();
                currentGoal = "Idle";
                stopBaritone(client);
                tell(client, "Morning: back to work.");
                ticksUntilGoal = 20;
            }
            return;
        }
        if (escaping || retreating || defending || player.isSleeping() || morningPending) {
            return;
        }
        BlockPos center = home != null ? new BlockPos(home[0] + 2, home[1], home[2] + 2) : player.blockPosition();
        BlockPos bed = WorldProbe.nearestBed(level, center, home != null ? 6 : 24);
        if (bed == null && home == null) {
            return;
        }
        BlockPos stand = home != null ? homeCell(HouseDesign.DOOR_STAND) : bed;
        if (!nightRoutine) {
            nightRoutine = true;
            nightJob = false;
            cancelCraft(client);
            cancelBreak(client);
            pendingHunt = null;
            returningHome = false;
            stopBaritone(client);
            activeTask = null;
            taskSteps = java.util.List.of();
            ActionableLog.LOGGER.info("[Actionable] night_routine bed={}", bed != null);
        }
        if (bed != null) {
            if (nightJob && pendingStairs == null && pendingSmelt == null) {
                // A bed exists now (perhaps just placed): stop working and sleep.
                nightJob = false;
                activeTask = null;
                taskSteps = java.util.List.of();
            }
            if (nightJob) {
                return;
            }
            goalReason = "night: sleep in the bed to skip it safely";
            currentGoal = "Sleeping through the night";
            if (clientTick - lastSleepAttempt < 100) {
                return;
            }
            lastSleepAttempt = clientTick;
            if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(bed)) <= 20.25) {
                stopBaritone(client);
                lastAction = "sleep";
                lastResult = MenuActions.useBlock(client, bed.getX(), bed.getY(), bed.getZ());
                tell(client, "Trying to sleep.");
            } else if (!baritoneBusy(client)) {
                executeBaritone(client, connection(client), "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            }
            return;
        }
        if (activeTask != null) {
            return;
        }
        // No bed, so no sleep: pick the most useful job that can be done at home.
        var inventory = WorldProbe.inventory(player);
        ProgressPlanner.Facts facts = facts(client);
        java.util.Optional<NeedsPlanner.Task> job = NeedsPlanner.homeTask(inventory, situation(client, facts));
        if (job.isPresent()) {
            nightJob = true;
            activeTask = job.get().goal();
            goalReason = "night, no bed: " + job.get().why();
            ActionableLog.LOGGER.info("[Actionable] night_job goal={} why={} situation={}",
                    ActionableLog.text(activeTask), ActionableLog.text(job.get().why()),
                    ActionableLog.text(NeedsPlanner.report(inventory, situation(client, facts))));
            tell(client, "Night job: " + activeTask + " (" + job.get().why() + ")");
            beginTask(client, activeTask);
            return;
        }
        goalReason = "night, no bed, nothing useful to do at home: waiting inside";
        currentGoal = "Waiting inside until morning";
        if (clientTick - lastSleepAttempt >= 100) {
            lastSleepAttempt = clientTick;
            if (!baritoneBusy(client) && player.blockPosition().distSqr(stand) > 2) {
                executeBaritone(client, connection(client), "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            }
        }
    }

    private NeedsPlanner.Situation situation(Minecraft client, ProgressPlanner.Facts facts) {
        boolean night = client.level != null && client.level.isDarkOutside();
        boolean furnaceWaiting = furnaceReadyAt > 0 && clientTick >= furnaceReadyAt;
        boolean strays = home != null && client.level != null && !WorldProbe.strayDoors(client.level, home).isEmpty();
        return new NeedsPlanner.Situation(facts, night, furnaceWaiting, clientTick >= mineRestUntil, strays);
    }

    /** Walks to the furnace if needed, then opens it to collect and load. True when something started. */
    private boolean startSmelt(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        var player = java.util.Objects.requireNonNull(client.player);
        BlockPos stand = homeCell(HouseDesign.DOOR_STAND);
        lastAction = NeedsPlanner.SMELT;
        if (player.blockPosition().distSqr(stand) > 1) {
            executeBaritone(client, connection, "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            lastAction = NeedsPlanner.SMELT;
            lastResult = "Walking to the house furnace";
            ticksUntilPlan = 200;
            return true;
        }
        stopBaritone(client);
        Smelter.Start start = Smelter.start(client, homeCell(HouseDesign.FURNACE_CELL), clientTick);
        if (start.smelter() == null) {
            lastResult = start.result();
            reportBlockResult(client);
            ticksUntilPlan = 20;
            return true;
        }
        pendingSmelt = start.smelter();
        lastResult = "Using the house furnace";
        return true;
    }

    /**
     * Eats when hungry, or when hurt and not full: health only regenerates on a high hunger bar. Stops
     * Baritone, waits a moment for it to release the keys (it held them and cancelled every bite
     * before), then holds use until the hunger bar actually rises. A failed attempt waits ten seconds.
     */
    /**
     * True only while the mod has a reason to act: enabled and either working on a task or in autonomous mode.
     * When disabled, or idle in manual mode waiting for a prompt, nothing may change the game state.
     */
    private boolean working() {
        return enabled && (autonomous || activeTask != null);
    }

    private void tickEat(Minecraft client) {
        var player = client.player;
        if (player == null || client.gameMode == null) {
            return;
        }
        if (eatPhase == 1) {
            if (clientTick - eatStartedAt < 6) {
                return;
            }
            // Food lives in its own hotbar slot; put it there and hold it.
            moveToHotbar(client, eatItem, InventoryLayout.FOOD_SLOT);
            player.getInventory().setSelectedSlot(InventoryLayout.FOOD_SLOT);
            eatPhase = 2;
            eatStartedAt = clientTick;
            return;
        }
        if (eatPhase == 2) {
            String held = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString();
            if (!held.equals(eatItem)) {
                if (clientTick - eatStartedAt > 10) {
                    ActionableLog.LOGGER.info("[Actionable] eat_problem wanted={} in_hand={} slot={}",
                            ActionableLog.text(eatItem), ActionableLog.text(held), player.getInventory().getSelectedSlot());
                    endEat(client, false);
                }
                player.getInventory().setSelectedSlot(InventoryLayout.FOOD_SLOT);
                return;
            }
            // Press and hold the real use key, exactly like a player, so the game runs the eating.
            com.mojang.blaze3d.platform.InputConstants.Key use =
                    com.mojang.blaze3d.platform.InputConstants.getKey(client.options.keyUse.saveString());
            KeyMapping.set(use, true);
            if (!player.isUsingItem()) {
                long waited = clientTick - eatStartedAt;
                if (waited % 10 == 0) {
                    KeyMapping.click(use);
                }
                if (waited % 10 == 5) {
                    client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                }
            }
            if (player.getFoodData().getFoodLevel() > eatFoodBefore) {
                endEat(client, true);
            } else if (clientTick - eatStartedAt > 80) {
                ActionableLog.LOGGER.info("[Actionable] eat_problem wanted={} in_hand={} using={} remaining_ticks={}",
                        ActionableLog.text(eatItem), ActionableLog.text(held), player.isUsingItem(),
                        player.getUseItemRemainingTicks());
                endEat(client, false);
            }
            return;
        }
        if (!working() || clientTick % 20 != 0 || clientTick < eatBackoffUntil || defending || pendingCraft != null
                || pendingSmelt != null || pendingBreak != null || player.containerMenu != player.inventoryMenu
                || !FoodPlan.shouldEat(player.getFoodData().getFoodLevel(), player.getHealth(), player.getMaxHealth())) {
            return;
        }
        java.util.Optional<String> food = FoodPlan.pick(WorldProbe.inventory(player), player.getFoodData().getFoodLevel());
        if (food.isEmpty()) {
            return;
        }
        eatResume = baritoneCommandRunning ? currentBaritoneCommand : null;
        String pending = pendingMine;
        stopBaritone(client);
        pendingMine = pending;
        eatItem = food.get();
        eatFoodBefore = player.getFoodData().getFoodLevel();
        eatPhase = 1;
        eatStartedAt = clientTick;
        tell(client, "Eating " + eatItem);
    }

    private void endEat(Minecraft client, boolean ate) {
        KeyMapping.set(com.mojang.blaze3d.platform.InputConstants.getKey(client.options.keyUse.saveString()), false);
        client.options.keyUse.setDown(false);
        eatPhase = 0;
        int food = client.player == null ? 0 : client.player.getFoodData().getFoodLevel();
        ActionableLog.LOGGER.info("[Actionable] eat item={} ok={} food={}->{}", ActionableLog.text(eatItem), ate,
                eatFoodBefore, food);
        if (!ate) {
            eatBackoffUntil = clientTick + 200;
        }
        if (eatResume != null && client.getConnection() != null) {
            executeBaritone(client, client.getConnection(), eatResume);
        }
        eatResume = null;
    }

    /**
     * Keeps the hotbar laid out: best pickaxe, axe and shovel first (Baritone only uses hotbar tools),
     * food in slot 7 and the best weapon in slot 8, swapping each in from wherever it is.
     */
    /**
     * Lights the player's surroundings without the model: when it is dark here and a torch is held,
     * places one on the darkest reachable spot that is at least as dark as the player's own cell, so
     * torches spread out. Cheap enough to run while Baritone is walking; the held slot is restored.
     */
    private void tickTorch(Minecraft client) {
        var player = client.player;
        var level = client.level;
        var gameMode = client.gameMode;
        if (!working() || player == null || level == null || gameMode == null || clientTick < nextTorchTick
                || clientTick % 5 != 0 || player.containerMenu != player.inventoryMenu || player.isInWater()
                || player.isSleeping() || eatPhase != 0 || pendingCraft != null || pendingBreak != null
                || pendingSmelt != null || defending) {
            return;
        }
        BlockPos feet = player.blockPosition();
        int here = level.getMaxLocalRawBrightness(feet);
        if (here > TORCH_LIGHT) {
            return;
        }
        Item torch = inventoryHas(player, net.minecraft.world.item.Items.TORCH) ? net.minecraft.world.item.Items.TORCH
                : inventoryHas(player, net.minecraft.world.item.Items.SOUL_TORCH)
                ? net.minecraft.world.item.Items.SOUL_TORCH : null;
        if (torch == null) {
            return;
        }
        Vec3 eyes = player.getEyePosition();
        BlockPos bestAnchor = null;
        Direction bestFace = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos cell = feet.offset(dx, dy, dz);
                    if (!level.getBlockState(cell).isAir()) {
                        continue;
                    }
                    int light = level.getMaxLocalRawBrightness(cell);
                    if (light > here) {
                        continue;
                    }
                    for (Direction toward : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                            Direction.EAST, Direction.WEST}) {
                        BlockPos anchor = cell.relative(toward);
                        Direction face = toward.getOpposite();
                        var anchorState = level.getBlockState(anchor);
                        if (!anchorState.getFluidState().isEmpty() || !anchorState.isFaceSturdy(level, anchor, face)) {
                            continue;
                        }
                        Vec3 hit = Vec3.atCenterOf(anchor).add(face.getStepX() * 0.5, face.getStepY() * 0.5,
                                face.getStepZ() * 0.5);
                        double distance = eyes.distanceToSqr(hit);
                        double score = light * 100 + distance;
                        if (distance <= 20.25 && score < bestScore
                                && level.getEntities((net.minecraft.world.entity.Entity) null,
                                        new net.minecraft.world.phys.AABB(cell), entity -> true).isEmpty()) {
                            bestScore = score;
                            bestAnchor = anchor;
                            bestFace = face;
                        }
                    }
                }
            }
        }
        nextTorchTick = clientTick + (bestAnchor == null ? 100 : 40);
        if (bestAnchor == null) {
            return;
        }
        int slot = Hotbar.bring(client, torch);
        if (slot < 0) {
            return;
        }
        int previous = player.getInventory().getSelectedSlot();
        player.getInventory().setSelectedSlot(slot);
        Vec3 hit = Vec3.atCenterOf(bestAnchor).add(bestFace.getStepX() * 0.5, bestFace.getStepY() * 0.5,
                bestFace.getStepZ() * 0.5);
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, bestFace, bestAnchor, false));
        player.getInventory().setSelectedSlot(previous);
        ActionableLog.LOGGER.info("[Actionable] auto_torch light={} anchor={}", here, bestAnchor.toShortString());
    }

    private void arrangeHotbar(Minecraft client) {
        var player = client.player;
        if (player == null || client.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return;
        }
        for (var entry : InventoryLayout.plan(WorldProbe.inventory(player)).entrySet()) {
            moveToHotbar(client, entry.getValue(), entry.getKey());
        }
    }

    /** Swaps an item into a hotbar slot unless it is already there. False when the item is not held. */
    private boolean moveToHotbar(Minecraft client, String item, int hotbarSlot) {
        var player = java.util.Objects.requireNonNull(client.player);
        if (itemId(player.getInventory().getItem(hotbarSlot)).equals(item)) {
            return true;
        }
        for (int slot = 0; slot < InventoryMenu.INV_SLOT_END; slot++) {
            if (slot == hotbarSlot || !itemId(player.getInventory().getItem(slot)).equals(item)) {
                continue;
            }
            // Inventory indexes 9-35 are the same slot numbers in the inventory menu; hotbar 0-8 are 36-44.
            int menuSlot = slot < 9 ? InventoryMenu.USE_ROW_SLOT_START + slot : slot;
            java.util.Objects.requireNonNull(client.gameMode).handleContainerInput(player.inventoryMenu.containerId,
                    menuSlot, hotbarSlot, ContainerInput.SWAP, player);
            return true;
        }
        return false;
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private void tickSmelt(Minecraft client) {
        if (pendingSmelt == null || defending) {
            return;
        }
        if (!enabled) {
            cancelSmelt(client);
            return;
        }
        long[] loaded = {0};
        String result = pendingSmelt.tick(client, clientTick, count -> loaded[0] = count);
        if (result == null) {
            return;
        }
        pendingSmelt = null;
        lastAction = NeedsPlanner.SMELT;
        lastResult = result;
        reportBlockResult(client);
        remember(lastAction, lastResult);
        ActionableLog.LOGGER.info("[Actionable] smelt {}", ActionableLog.text(result));
        furnaceReadyAt = 0;
        if (!result.startsWith("SMELTED")) {
            // No fuel or the furnace would not open: do something else for a while.
            smeltCooldownUntil = clientTick + 2400;
        }
        if (!taskSteps.isEmpty() && taskSteps.get(currentStep).equalsIgnoreCase(NeedsPlanner.SMELT)) {
            advanceStep(client, NeedsPlanner.SMELT);
            return;
        }
        ticksUntilPlan = 1;
    }

    private void tickStairs(Minecraft client) {
        if (pendingStairs == null || defending || eatPhase != 0) {
            return;
        }
        var connection = client.getConnection();
        if (!enabled || connection == null) {
            cancelHomeWork(client);
            return;
        }
        String result = pendingStairs.tick(client, clientTick,
                command -> executeBaritone(client, connection, command), () -> stopBaritone(client));
        if (result == null) {
            return;
        }
        pendingStairs = null;
        lastAction = "dig house mine";
        lastResult = result;
        reportBlockResult(client);
        remember(lastAction, lastResult);
        ActionableLog.LOGGER.info("[Actionable] house_mine {}", ActionableLog.text(result));
        if (morningPending) {
            morningPending = false;
            activeTask = null;
            taskSteps = java.util.List.of();
            currentGoal = "Idle";
            tell(client, "Morning: out of the mine, back to work.");
            ticksUntilGoal = 20;
            return;
        }
        if (result.startsWith("MINE_STOPPED_HEALTH") || result.startsWith("MINE_STOPPED_INVENTORY_FULL")
                || result.startsWith("MINE_STOPPED_TIMEOUT")) {
            // Rest from the mine for two minutes; other home jobs (or waiting) come first.
            mineRestUntil = clientTick + 2400;
            activeTask = null;
            taskSteps = java.util.List.of();
            nightJob = false;
            return;
        }
        ticksUntilPlan = 1;
    }

    private void cancelSmelt(Minecraft client) {
        if (pendingSmelt != null) {
            pendingSmelt = null;
            if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
                client.player.closeContainer();
            }
        }
    }

    /** Stops furnace and house-mine work immediately (pause, defense of the task, abandon). */
    private void cancelHomeWork(Minecraft client) {
        cancelSmelt(client);
        if (pendingStairs != null) {
            pendingStairs = null;
            stopBaritone(client);
        }
        morningPending = false;
    }

    private static net.minecraft.client.multiplayer.ClientPacketListener connection(Minecraft client) {
        return java.util.Objects.requireNonNull(client.getConnection());
    }

    /** Walks an unequipped player back when they stray past the home range. */
    private void tickHomeRange(Minecraft client) {
        var player = client.player;
        if (!enabled || !autonomous || player == null || client.getConnection() == null || clientTick % 40 != 0) {
            return;
        }
        int[] anchor = anchor();
        if (anchor == null) {
            return;
        }
        boolean geared = rangeExempt(client);
        if (returningHome) {
            if (!baritoneCommandRunning || !currentBaritoneCommand.startsWith("goto")) {
                // Something else (a mine that kept running) took over; send the walk home again.
                executeBaritone(client, client.getConnection(),
                        "goto " + anchor[0] + " " + anchor[1] + " " + anchor[2]);
            }
            if (geared || !HomeRange.outside(anchor, false, player.getBlockX(), player.getBlockY(),
                    player.getBlockZ(), -8)) {
                returningHome = false;
                activeTask = null;
                taskSteps = java.util.List.of();
                currentGoal = "Idle";
                stopBaritone(client);
                tell(client, "Back near home.");
                ticksUntilGoal = 20;
            }
            return;
        }
        // Hunting may travel as far as it takes to find sheep and food; anything else stays near home.
        boolean hunting = pendingHunt != null || (!taskSteps.isEmpty() && (taskSteps.get(currentStep).startsWith("hunt ")
                || taskSteps.get(currentStep).equalsIgnoreCase(ProgressPlanner.GATHER_FOOD)));
        if (escaping || retreating || nightRoutine || defending || hunting
                || !HomeRange.outside(anchor, geared, player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                HomeRange.LEASH_SLACK)) {
            return;
        }
        if (activeTask != null) {
            noteAttempt(activeTask + " (stopped: too far from home without gear)");
        }
        ActionableLog.LOGGER.info("[Actionable] home_leash distance={}",
                Math.round(HomeRange.distance(anchor, player.getBlockX(), player.getBlockZ())));
        cancelCraft(client);
        pendingHunt = null;
        stopBaritone(client);
        returningHome = true;
        startGoal(client, "return home", java.util.List.of("goto " + anchor[0] + " " + anchor[1] + " " + anchor[2]));
        goalReason = "too far from home without a stone pickaxe and a stone sword";
    }

    /**
     * Runs a code-started step to completion without the model: waits while Baritone works on it,
     * and starts the step again when Baritone stops short. Hands over to the model only when restarts
     * stop making progress. Returns false to hand over.
     */
    private boolean continueDirectStep(Minecraft client) {
        String step = taskSteps.get(currentStep);
        if (client.player == null || client.getConnection() == null || !step.equals(directStep)) {
            return false;
        }
        int progress = directProgress(client, step);
        if (progress > directBest) {
            directBest = progress;
            directProgressAt = clientTick;
            directResends = 0;
        }
        boolean working = pendingMine != null || baritoneBusy(client);
        // Finding sheep can take several walks around home, so hunting gets more restarts.
        boolean hunt = step.startsWith("hunt ") || step.equalsIgnoreCase(ProgressPlanner.GATHER_FOOD);
        int limit = hunt ? Recovery.HUNT_RESENDS : Recovery.DIRECT_RESENDS;
        // Walking and placing should get closer within 30 seconds; gathering can take longer between items.
        int stall = ProgressPlanner.parse(step).isPresent() ? Recovery.DIRECT_STALL_TICKS : Recovery.WALK_STALL_TICKS;
        Recovery.DirectMine decision = Recovery.directMine(working, clientTick - directProgressAt, directResends, limit,
                stall);
        if (decision == Recovery.DirectMine.WAIT) {
            ticksUntilPlan = 200;
            return true;
        }
        if (decision == Recovery.DirectMine.HAND_OVER) {
            ActionableLog.LOGGER.info("[Actionable] direct_step_handover step={} progress={}",
                    ActionableLog.text(step), progress);
            directStep = null;
            return false;
        }
        directResends++;
        directProgressAt = clientTick;
        ActionableLog.LOGGER.info("[Actionable] direct_step_restart step={} progress={} restart={}",
                ActionableLog.text(step), progress, directResends);
        if (working) {
            stopBaritone(client);
        }
        stepActionTaken = false;
        return executeStepDirectly(client);
    }

    private void startCraft(Minecraft client, String item, int count) {
        stopBaritone(client);
        lastAction = "craft " + count + " " + item;
        Crafter.Start start = Crafter.start(client, item, count, clientTick);
        if (start.crafter() == null) {
            lastResult = start.result();
            reportBlockResult(client);
            return;
        }
        pendingCraft = start.crafter();
        lastResult = "Crafting " + item + " until holding " + count;
        errorCode = "none";
        lastError = "none";
        tell(client, lastResult);
    }

    private void tickCraft(Minecraft client) {
        if (pendingCraft == null) {
            return;
        }
        String result = enabled ? pendingCraft.tick(client, clientTick) : pendingCraft.cancel(client);
        if (result == null) {
            return;
        }
        pendingCraft = null;
        lastResult = result;
        reportBlockResult(client);
        remember(lastAction, lastResult);
        ticksUntilPlan = 1;
    }

    private void cancelBreak(Minecraft client) {
        if (pendingBreak != null) {
            pendingBreak.cancel(client);
            pendingBreak = null;
        }
        unstickResume = null;
    }

    private void cancelCraft(Minecraft client) {
        if (pendingCraft != null) {
            lastResult = pendingCraft.cancel(client);
            pendingCraft = null;
        }
    }

    /**
     * Gates a mine command before Baritone sees it: maps items to the block that drops them, refuses
     * blocks the player cannot harvest, and walks to an exposed, lit deposit instead of letting Baritone
     * pick the nearest one, which may be deep in a dark cave.
     */
    /** Blocks that only grow on the surface, where Baritone's mine must never be left to dig for them. */
    private static boolean isSurfaceBlock(String blockId) {
        return blockId.endsWith("_log") || blockId.endsWith("_wood");
    }

    private void startMine(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection,
            String command) {
        String[] parts = command.split("\\s+");
        String requested = parts[2].contains(":") ? parts[2] : "minecraft:" + parts[2];
        lastAction = command;
        if (TechTree.recipeFor(requested).isPresent() && !TechTree.isReclaimable(requested)) {
            lastResult = "MINE_FAILED_CRAFTED_ITEM: " + requested + " is crafted, not mined; use a craft action";
            reportBlockResult(client);
            return;
        }
        String blockId = TechTree.blockToMine(requested);
        if (blockId.endsWith("_log") && client.level != null && client.player != null) {
            // Any wood works; Baritone counts only the exact log asked for, so use the type actually in reach
            // (else the type already held) instead of a literal oak_log that may be far away.
            String near = WorldProbe.nearestLogType(client.level, client.player.blockPosition(), SURFACE_SCAN_RADIUS);
            String held = near != null ? near : WorldProbe.preferredLog(WorldProbe.inventory(client.player), "");
            if (!held.equals(blockId)) {
                lastResult = "Using " + held + " instead of " + blockId + ": any wood type works for this goal";
                blockId = held;
            }
        }
        String mine = "mine " + parts[1] + " " + blockId;
        if (blockId.equals(TechTree.CRAFTING_TABLE) && client.level != null && client.player != null) {
            BlockPos table = WorldProbe.nearestCraftingTable(client.level, client.player.blockPosition());
            if (table != null && isHouseCell(table)) {
                lastResult = "MINE_FAILED_HOUSE_TABLE: the crafting table in the house stays there; go to the house"
                        + " to craft instead of picking it up";
                reportBlockResult(client);
                return;
            }
        }
        var player = client.player;
        var level = client.level;
        Identifier id = Identifier.tryParse(blockId);
        if (player == null || level == null || id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            executeBaritone(client, connection, mine);
            return;
        }
        net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.getValue(id);
        var state = block.defaultBlockState();
        if (state.requiresCorrectToolForDrops() && player.getInventory().getNonEquipmentItems().stream()
                .noneMatch(stack -> !stack.isEmpty() && stack.isCorrectToolForDrops(state))) {
            TechTree.Tier needed = TechTree.toolFor(blockId);
            lastResult = "MINE_FAILED_NO_TOOL: " + blockId + " drops nothing without a "
                    + TechTree.pickaxeFor(needed == TechTree.Tier.NONE ? TechTree.Tier.WOOD : needed)
                    + " (or the right tool); craft it first instead of mining by hand";
            reportBlockResult(client);
            return;
        }
        SafetyAssessment.Result safety = WorldProbe.safety(client);
        boolean surfaceOnly = isSurfaceBlock(blockId);
        java.util.List<MiningPolicy.Site> sites = WorldProbe.exposedSites(level, player.blockPosition(), block,
                surfaceOnly ? SURFACE_SCAN_RADIUS : SITE_SCAN_RADIUS);
        int[] anchor = anchor();
        boolean geared = rangeExempt(client);
        sites.removeIf(site -> HomeRange.outside(anchor, geared, site.x(), site.y(), site.z(), 0));
        if (sites.isEmpty() && surfaceOnly) {
            // With no log in loaded chunks Baritone's mine digs down into caves to look for one; walk the
            // surface to fresh ground instead and ask again from there.
            int[] here = {player.getBlockX(), player.getBlockY(), player.getBlockZ()};
            int[] target = HomeRange.searchTarget(here, wanderAttempts++, SURFACE_SCAN_RADIUS * 2);
            if (!geared && anchor != null && HomeRange.outside(anchor, false, target[0], target[1], target[2], 0)) {
                target = HomeRange.searchTarget(anchor, wanderAttempts, HomeRange.RADIUS);
            }
            executeBaritone(client, connection, HomeRange.surfaceGoto(target));
            pendingMine = mine;
            pendingMineSince = clientTick;
            lastAction = mine;
            lastResult = "No " + blockId + " in loaded chunks nearby; walking the surface to " + target[0] + ","
                    + target[2] + " to look for trees (never digging down for them); mining starts on arrival."
                    + " " + TreeBiomes.table().lines().findFirst().orElse("");
            return;
        }
        if (sites.isEmpty() && anchor != null && !geared && surfaceOnly) {
            // Baritone's mine would roam unbounded to find more; look around near home instead.
            int[] target = HomeRange.searchTarget(anchor, wanderAttempts++, HomeRange.RADIUS);
            executeBaritone(client, connection, HomeRange.surfaceGoto(target));
            pendingMine = mine;
            pendingMineSince = clientTick;
            lastAction = mine;
            lastResult = "No " + blockId + " in sight; looking near home at " + target[0] + "," + target[2]
                    + "; mining starts on arrival";
            return;
        }
        if (!sites.isEmpty()) {
            java.util.Optional<MiningPolicy.Site> best = MiningPolicy.choose(sites, safety.caveReady(),
                    java.util.List.copyOf(avoidAreas));
            if (best.isEmpty()) {
                // Baritone knows where the block is, buried or not; let it dig to it.
                executeBaritone(client, connection, mine);
                mineStart = new int[] {player.getBlockX(), player.getBlockY(), player.getBlockZ()};
                return;
            }
            MiningPolicy.Site site = best.get();
            // From water, always walk to the dry stand cell first so Baritone starts mining from land.
            if (player.isInWater()) {
                executeBaritone(client, connection, "goto " + site.standX() + " " + site.standY() + " " + site.standZ());
                pendingMine = mine;
                pendingMineSince = clientTick;
                lastAction = mine;
                lastResult = "Walking to exposed " + blockId + " at " + site.describe() + "; mining starts on arrival";
                ActionableLog.LOGGER.info("[Actionable] mine_site block={} site={}",
                        ActionableLog.text(blockId), ActionableLog.text(site.describe()));
                return;
            }
        }
        executeBaritone(client, connection, mine);
        mineStart = new int[] {player.getBlockX(), player.getBlockY(), player.getBlockZ()};
        if (!blockId.equals(requested)) {
            lastResult += " (mining " + blockId + ", which drops " + requested + ")";
        }
    }

    /** Starts a deferred mine once the player reaches its site, and leashes running mine commands. */
    private void tickMining(Minecraft client) {
        var player = client.player;
        var connection = client.getConnection();
        if (!enabled || player == null || connection == null || clientTick % 10 != 0) {
            return;
        }
        if (pendingMine != null) {
            double remaining = gotoDistance(client);
            boolean arrived = remaining >= 0 && remaining <= 3;
            boolean stalled = clientTick - lastBaritoneMovementAt > 100 || clientTick - pendingMineSince > 1200;
            if (arrived || stalled) {
                String mine = pendingMine;
                if (isSurfaceBlock(mine.split("\\s+")[2])) {
                    // Re-check for trees from the new spot; this walks on if there still are none.
                    startMine(client, connection, mine);
                    return;
                }
                executeBaritone(client, connection, mine);
                mineStart = new int[] {player.getBlockX(), player.getBlockY(), player.getBlockZ()};
                remember(mine, lastResult);
            }
            return;
        }
        boolean mining = baritoneCommandRunning && currentBaritoneCommand.startsWith("mine ");
        if (mining && mineStart != null && player.getBlockY() < mineStart[1] - SURFACE_DROP_LIMIT
                && isSurfaceBlock(currentBaritoneCommand.split("\\s+")[2])) {
            // Baritone went underground looking for trees: stop and search the surface instead.
            String command = currentBaritoneCommand;
            stopBaritone(client);
            lastAction = command;
            lastResult = "MINE_ABORTED_DESCENDING: Baritone was digging down for wood; stopped. Searching the"
                    + " surface for trees instead.";
            remember(lastAction, lastResult);
            startMine(client, connection, command);
            return;
        }
        waterTicks = mining && player.isInWater() ? waterTicks + 10 : 0;
        if (waterTicks >= Recovery.WATER_STUCK_TICKS) {
            // Mining from water: give up on this spot, swim to land, and let the step re-send from there.
            waterTicks = 0;
            String command = currentBaritoneCommand;
            avoidAreas.addLast(new int[] {player.getBlockX(), player.getBlockY(), player.getBlockZ()});
            while (avoidAreas.size() > AVOID_MEMORY) {
                avoidAreas.removeFirst();
            }
            stopBaritone(client);
            ActionableLog.LOGGER.info("[Actionable] mine_water_stuck command={} at={},{},{}", ActionableLog.text(command),
                    player.getBlockX(), player.getBlockY(), player.getBlockZ());
            leaveWater(client);
            lastAction = command;
            lastResult = "MINE_STOPPED_IN_WATER: stuck in water while mining; swimming to land to mine from there";
            remember(lastAction, lastResult);
            tell(client, "Stuck in water; heading back to land.");
            return;
        }
    }

    /**
     * Tracks the safety assessment for the model and the debug panel. Darkness is only reported;
     * it never cancels a task.
     */
    private void tickSafety(Minecraft client) {
        var player = client.player;
        var level = client.level;
        if (!enabled || player == null || level == null || clientTick % 20 != 0
                || (!autonomous && activeTask == null)) {
            return;
        }
        SafetyAssessment.Result safety = WorldProbe.safety(client);
        safetyStatus = safety.level().name().toLowerCase(java.util.Locale.ROOT)
                + (safety.reasons().isEmpty() ? "" : " - " + safety.reasons().get(0));
        int x = player.getBlockX();
        int y = player.getBlockY();
        int z = player.getBlockZ();
        if (safety.safeSpot()) {
            lastSafePosition = new int[] {x, y, z};
        }
        if (retreating) {
            if (!safety.underground() || safety.light() > 7) {
                tell(client, "Back in a safe, lit area.");
                remember("retreat", "Reached safety at " + x + "," + y + "," + z);
                stopBaritone(client);
                retreating = false;
                activeTask = null;
                taskSteps = java.util.List.of();
                lastResult = "Retreated from a dark area without cave gear";
                currentGoal = "Idle";
                ticksUntilGoal = 40;
            }
            return;
        }
    }

    /** Advances as soon as the inventory shows the current step is done, instead of at the next plan. */
    private void tickStepCheck(Minecraft client) {
        if (!enabled || activeTask == null || taskSteps.isEmpty() || finalCheck || planning || judging || decomposing
                || pendingCraft != null || pendingJumpPlace != null || client.player == null
                || clientTick % 20 != 0) {
            return;
        }
        String step = taskSteps.get(currentStep);
        // Not table placement: a table placed earlier would otherwise skip a fresh placement step.
        boolean worldStep = step.equalsIgnoreCase(ProgressPlanner.BUILD_HOUSE)
                || step.equalsIgnoreCase(ProgressPlanner.PLACE_BED)
                || step.equalsIgnoreCase(ProgressPlanner.PLACE_TABLE_IN_HOUSE)
                || step.equalsIgnoreCase(ProgressPlanner.PLACE_DOOR)
                || step.equalsIgnoreCase(ProgressPlanner.GO_HOME)
                || step.equalsIgnoreCase(ProgressPlanner.LAY_FLOOR)
                || step.equalsIgnoreCase(NeedsPlanner.PLACE_FURNACE)
                || step.equalsIgnoreCase(ProgressPlanner.GATHER_FOOD);
        if ((ProgressPlanner.parse(step).isPresent() || worldStep) && ProgressPlanner.stepSatisfied(step,
                WorldProbe.inventory(client.player), false, facts(client)).orElse(false)) {
            advanceStep(client, step);
        }
    }

    /** Remaining distance to the running goto target, or -1 when no goto is running. */
    private double gotoDistance(Minecraft client) {
        if (!baritoneCommandRunning || !currentBaritoneCommand.startsWith("goto ") || client.player == null) {
            return -1;
        }
        return gotoRemaining(currentBaritoneCommand, client.player.blockPosition());
    }

    /** Distance left to a goto goal; a column-only goto ("goto x z") counts sideways distance only. */
    private static double gotoRemaining(String command, BlockPos current) {
        String[] parts = command.split("\\s+");
        try {
            if (parts.length == 3) {
                return Math.hypot(Integer.parseInt(parts[1]) - current.getX(), Integer.parseInt(parts[2]) - current.getZ());
            }
            if (parts.length == 4) {
                return Math.sqrt(Math.pow(Integer.parseInt(parts[1]) - current.getX(), 2)
                        + Math.pow(Integer.parseInt(parts[2]) - current.getY(), 2)
                        + Math.pow(Integer.parseInt(parts[3]) - current.getZ(), 2));
            }
        } catch (NumberFormatException ignored) {
            return -1;
        }
        return -1;
    }

    private void toggle(Minecraft client) {
        enabled = !enabled;
        if (!enabled) {
            history.clear();
        }
        generation++;
        planning = false;
        cancelCraft(client);
        cancelBreak(client);
        cancelHomeWork(client);
        tell(client, "Actionable " + (enabled ? "enabled." : "disabled."));
        var connection = client.getConnection();
        if (!enabled && client.player != null && connection != null
                && FabricLoader.getInstance().isModLoaded("baritone")) {
            connection.sendChat("#cancel");
            lastResult = "Baritone cancellation requested";
        }
    }

    private void executeBaritone(Minecraft client,
                                 net.minecraft.client.multiplayer.ClientPacketListener connection,
                                 String command) {
        if (!baritoneConfigured) {
            // Baritone resets its settings each launch; make swimming expensive so it prefers land routes
            // and land-reachable blocks, and let it build from the whole inventory.
            connection.sendChat("#set walkOnWaterOnePenalty 20");
            connection.sendChat("#set allowInventory true");
            for (String setting : BARITONE_SETTINGS) {
                connection.sendChat("#set " + setting);
            }
            baritoneConfigured = true;
        }
        if (baritoneCommandRunning && command.equals(currentBaritoneCommand)) {
            lastAction = command;
            lastResult = "Existing Baritone command left running; " + baritoneProgress(client);
            tell(client, "Keeping Baritone task running: " + command);
            return;
        }
        if (!command.equals("cancel") && (baritoneCommandRunning
                || BaritoneProbe.query().map(BaritoneProbe.State::active).orElse(false))) {
            // A running mine does not always yield to a new goto; cancel it explicitly first.
            connection.sendChat("#cancel");
        }
        connection.sendChat("#" + command);
        lastAction = command;
        travelDirection = null;
        pendingMine = null;
        mineStart = null;
        baritoneCommandRunning = !command.equals("cancel");
        currentBaritoneCommand = baritoneCommandRunning ? command : "";
        BlockPos position = client.player == null ? null : client.player.blockPosition();
        baritoneStartPosition = position;
        lastBaritonePosition = position;
        baritoneCommandStartedAt = clientTick;
        lastBaritoneMovementAt = clientTick;
        lastResult = baritoneCommandRunning
                ? "Started Baritone command; " + baritoneProgress(client)
                : "Baritone cancellation requested";
        tell(client, "Baritone: " + command);
    }

    /** A ~500 block walk in one compass direction: "#goto x z" leaves y open, so Baritone stays on the surface. */
    private void travel(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection,
            String direction) {
        var player = client.player;
        if (player == null) {
            return;
        }
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int[] anchor = anchor();
        if (anchor != null && !rangeExempt(client)) {
            int[] target = HomeRange.searchTarget(anchor, wanderAttempts++, HomeRange.RADIUS);
            tell(client, "Travelling near home instead of wandering off.");
            executeBaritone(client, connection, HomeRange.surfaceGoto(target));
            travelDirection = "";
            return;
        }
        switch (direction) {
            case "NORTH" -> z -= TRAVEL_LEG;
            case "SOUTH" -> z += TRAVEL_LEG;
            case "EAST" -> x += TRAVEL_LEG;
            default -> x -= TRAVEL_LEG;
        }
        executeBaritone(client, connection, "goto " + x + " " + z);
        travelDirection = direction;
        lastResult = "Started travelling " + direction + " " + TRAVEL_LEG + " blocks to x=" + x + " z=" + z
                + "; " + baritoneProgress(client);
    }

    private void stopBaritone(Minecraft client) {
        pendingMine = null;
        mineStart = null;
        if (!baritoneCommandRunning && !BaritoneProbe.query().map(BaritoneProbe.State::active).orElse(false)) {
            return;
        }
        var connection = client.getConnection();
        if (connection != null) {
            connection.sendChat("#cancel");
        }
        baritoneCommandRunning = false;
        currentBaritoneCommand = "";
    }

    private void updateBaritoneProgress(Minecraft client) {
        if (!baritoneCommandRunning || client.player == null) {
            return;
        }
        BlockPos position = java.util.Objects.requireNonNull(client.player).blockPosition();
        if (lastBaritonePosition == null || !position.equals(lastBaritonePosition)) {
            lastBaritonePosition = position;
            lastBaritoneMovementAt = clientTick;
        }
        // Baritone needs a moment to pick up a freshly sent command before "idle" means finished.
        if (clientTick % 10 == 0 && clientTick - baritoneCommandStartedAt > 40) {
            java.util.Optional<BaritoneProbe.State> state = BaritoneProbe.query();
            if (state.isPresent() && !state.get().active()) {
                String finished = currentBaritoneCommand;
                baritoneCommandRunning = false;
                currentBaritoneCommand = "";
                lastResult = "Baritone finished or stopped by itself (idle): " + finished
                        + ". Check the inventory/surroundings to see if the goal was reached.";
                if (finished.startsWith("build " + HouseDesign.SCHEMATIC_FILE) && home != null) {
                    // Baritone's build only ends when the schematic is done; a block or two may stay with terrain.
                    houseBuiltSeen = true;
                    lastResult = "HOUSE_BUILD_DONE: Baritone finished the house schematic. The house is complete;"
                            + " do not place extra planks around it.";
                }
                if (travelDirection != null && !travelDirection.isEmpty()) {
                    lastResult = "TRAVEL_LEG_DONE: walked " + TRAVEL_LEG + " blocks " + travelDirection
                            + ". Check the synopsis for what you were looking for. If it is still missing, call"
                            + " travel again (the same direction continues another " + TRAVEL_LEG
                            + " blocks from here, or pick another); otherwise act on what you found.";
                }
                travelDirection = null;
                if (activeTask != null && !planning) {
                    ticksUntilPlan = 0;
                }
            }
        }
    }

    /** Live Baritone state for the model, from the Baritone API when available. */
    String baritoneLiveState() {
        return BaritoneProbe.query().map(state -> "baritone_live=" + state.describe())
                .orElse("baritone_live=unknown");
    }

    private String baritoneProgress(Minecraft client) {
        if (!baritoneCommandRunning) {
            return "baritone_task=idle," + baritoneLiveState();
        }
        BlockPos current = client.player == null ? lastBaritonePosition : client.player.blockPosition();
        long elapsedTicks = clientTick - baritoneCommandStartedAt;
        long idleTicks = clientTick - lastBaritoneMovementAt;
        double moved = 0;
        if (current != null && baritoneStartPosition != null) {
            double dx = current.getX() - baritoneStartPosition.getX();
            double dy = current.getY() - baritoneStartPosition.getY();
            double dz = current.getZ() - baritoneStartPosition.getZ();
            moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        StringBuilder progress = new StringBuilder("baritone_task=running{command=")
                .append(currentBaritoneCommand)
                .append(",").append(baritoneLiveState())
                .append(",elapsed_seconds=").append(elapsedTicks / 20)
                .append(",distance_moved_blocks=").append(String.format(java.util.Locale.ROOT, "%.1f", moved))
                .append(",seconds_since_movement=").append(idleTicks / 20);
        if (currentBaritoneCommand.startsWith("goto ") && current != null) {
            double distance = gotoRemaining(currentBaritoneCommand, current);
            progress.append(",distance_to_goto_goal=").append(distance < 0 ? "unknown"
                    : String.format(java.util.Locale.ROOT, "%.1f", distance));
        }
        return progress.append('}').toString();
    }

    private void reportBlockResult(Minecraft client) {
        if (lastResult.contains("_FAILED")) {
            errorCode = lastResult.substring(0, lastResult.indexOf(':'));
            lastError = compact(lastResult, 140);
            tell(client, lastResult);
        } else {
            errorCode = "none";
            lastError = "none";
            tell(client, lastResult);
        }
    }

    private String startJumpPlace(Minecraft client, ActionPlanner.PlannedAction action) {
        if (client.player == null || client.level == null || client.gameMode == null) {
            return "BLOCK_PLACE_FAILED_NO_WORLD: player world is unavailable";
        }
        var player = java.util.Objects.requireNonNull(client.player);
        var level = java.util.Objects.requireNonNull(client.level);
        var gameMode = java.util.Objects.requireNonNull(client.gameMode);
        BlockPos target = new BlockPos(action.x(), action.y(), action.z());
        BlockPos feet = player.blockPosition();
        if (target.getX() != feet.getX() || target.getY() != feet.getY() || target.getZ() != feet.getZ()) {
            return "JUMP_PLACE_FAILED_NOT_CURRENT_CELL: target must equal current feet cell "
                    + feet.getX() + "," + feet.getY() + "," + feet.getZ();
        }
        Identifier blockId = Identifier.tryParse(java.util.Objects.requireNonNull(action.block()));
        if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
            return "BLOCK_PLACE_FAILED_UNKNOWN_BLOCK: " + action.block();
        }
        Item blockItem = BuiltInRegistries.BLOCK.getValue(blockId).asItem();
        if (blockItem == net.minecraft.world.item.Items.AIR || !inventoryHas(player, blockItem)) {
            return "BLOCK_PLACE_FAILED_MATERIAL_MISSING: " + action.block()
                    + " is not in inventory; gather required materials before placing";
        }
        var targetState = level.getBlockState(target);
        if (!targetState.isAir()) {
            if (targetState.canBeReplaced() && gameMode.destroyBlock(target)) {
                return "BLOCK_PLACE_DEFERRED_TARGET_CLEARED: cleared " + blockIdentifier(targetState.getBlock())
                        + " at the player's feet; retry jump_place";
            }
            return "JUMP_PLACE_FAILED_TARGET_OCCUPIED: " + blockIdentifier(targetState.getBlock());
        }
        var headroom = target.above();
        var headroomState = level.getBlockState(headroom);
        if (!headroomState.isAir()) {
            if (headroomState.canBeReplaced() && gameMode.destroyBlock(headroom)) {
                return "BLOCK_PLACE_DEFERRED_HEADROOM_CLEARED: cleared plant above feet; retry jump_place";
            }
            return "JUMP_PLACE_FAILED_NO_HEADROOM: " + blockIdentifier(headroomState.getBlock());
        }
        if (!player.onGround()) {
            return "JUMP_PLACE_FAILED_NOT_GROUNDED: wait until standing on the ground";
        }
        player.jumpFromGround();
        pendingJumpPlace = new JumpPlace(action, target, clientTick);
        lastResult = "Jump started; waiting until the player clears " + target;
        tell(client, lastResult);
        return lastResult;
    }

    private void tickJumpPlace(Minecraft client) {
        if (pendingJumpPlace == null) {
            return;
        }
        if (!enabled) {
            pendingJumpPlace = null;
            return;
        }
        if (client.player == null || client.level == null || client.gameMode == null) {
            pendingJumpPlace = null;
            errorCode = "JUMP_PLACE_FAILED_WORLD_ENDED";
            lastError = "Player or world unavailable during jump-place";
            lastResult = lastError;
            return;
        }
        JumpPlace jumpPlace = pendingJumpPlace;
        var player = java.util.Objects.requireNonNull(client.player);
        BlockPos target = java.util.Objects.requireNonNull(jumpPlace.target());
        if (player.blockPosition().getY() > target.getY()
                && !player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(target))) {
            pendingJumpPlace = null;
            lastResult = placeBlock(client, jumpPlace.action());
            reportBlockResult(client);
            ticksUntilPlan = 1;
        } else if (clientTick - jumpPlace.startedAtTick() > 40) {
            pendingJumpPlace = null;
            errorCode = "JUMP_PLACE_FAILED_JUMP_TIMEOUT";
            lastError = "Player did not clear the target cell";
            lastResult = errorCode + ": " + lastError;
            tell(client, lastResult);
            ticksUntilPlan = 1;
        }
    }

    private static boolean inventoryHas(net.minecraft.world.entity.player.Player player, Item item) {
        return player.getInventory().getNonEquipmentItems().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.is(item));
    }

    private static String blockIdentifier(net.minecraft.world.level.block.Block block) {
        return java.util.Objects.requireNonNull(
                BuiltInRegistries.BLOCK.getKey(java.util.Objects.requireNonNull(block))).toString();
    }

    private static boolean waterOnly(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.WATER);
    }

    private String placeBlock(Minecraft client, ActionPlanner.PlannedAction action) {
        if (client.player == null || client.level == null || client.gameMode == null) {
            return "BLOCK_PLACE_FAILED_NO_WORLD: player world is unavailable";
        }
        var player = java.util.Objects.requireNonNull(client.player);
        var level = java.util.Objects.requireNonNull(client.level);
        var gameMode = java.util.Objects.requireNonNull(client.gameMode);
        Identifier blockId = Identifier.tryParse(java.util.Objects.requireNonNull(action.block()));
        if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
            return "BLOCK_PLACE_FAILED_UNKNOWN_BLOCK: " + action.block();
        }
        Item blockItem = BuiltInRegistries.BLOCK.getValue(blockId).asItem();
        if (blockItem == net.minecraft.world.item.Items.AIR) {
            return "BLOCK_PLACE_FAILED_NO_ITEM: " + action.block();
        }

        BlockPos target = new BlockPos(action.x(), action.y(), action.z());
        if (player.distanceToSqr(Vec3.atCenterOf(target)) > 20.25) {
            return "BLOCK_PLACE_FAILED_OUT_OF_REACH: move within 4.5 blocks of " + target;
        }
        if (player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(target))) {
            return "BLOCK_PLACE_FAILED_PLAYER_IN_WAY: target " + target
                    + " intersects the player; choose a nearby placement candidate";
        }
        var targetState = level.getBlockState(target);
        // Plain water is placed into directly; a block cannot be "broken" out of it first.
        if (!targetState.isAir() && !waterOnly(targetState)) {
            if (targetState.canBeReplaced() && gameMode.destroyBlock(target)) {
                return "BLOCK_PLACE_DEFERRED_TARGET_CLEARED: removed replaceable obstruction at "
                        + target + "; recheck the location and place next";
            }
            return "BLOCK_PLACE_FAILED_TARGET_OCCUPIED: target " + target + " contains "
                    + BuiltInRegistries.BLOCK.getKey(targetState.getBlock());
        }
        BlockPos headroom = target.above();
        var headroomState = level.getBlockState(headroom);
        if (!headroomState.isAir() && !waterOnly(headroomState)) {
            if (headroomState.canBeReplaced() && gameMode.destroyBlock(headroom)) {
                return "BLOCK_PLACE_DEFERRED_HEADROOM_CLEARED: removed replaceable obstruction at "
                        + headroom + "; recheck the location and place next";
            }
            return "BLOCK_PLACE_FAILED_NO_HEADROOM: block above target " + target + " is "
                    + BuiltInRegistries.BLOCK.getKey(headroomState.getBlock());
        }
        int hotbarSlot = Hotbar.bring(client, blockItem);
        if (hotbarSlot < 0) {
            return "BLOCK_PLACE_FAILED_MATERIAL_MISSING: " + action.block()
                    + " is not in inventory; gather required materials before placing";
        }

        for (Direction offset : Direction.values()) {
            BlockPos supportPos = target.relative(offset);
            Direction clickedFace = offset.getOpposite();
            if (!level.getBlockState(supportPos).isFaceSturdy(level, supportPos, clickedFace)) {
                continue;
            }
            Vec3 hitLocation = Vec3.atCenterOf(supportPos).add(
                    clickedFace.getStepX() * 0.5,
                    clickedFace.getStepY() * 0.5,
                    clickedFace.getStepZ() * 0.5
            );
            if (player.distanceToSqr(hitLocation) > 20.25) {
                continue;
            }

            player.getInventory().setSelectedSlot(hotbarSlot);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, hitLocation);
            BlockHitResult hit = new BlockHitResult(hitLocation, clickedFace, supportPos, false);
            InteractionResult result = gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            if (result.consumesAction()) {
                return "placement request sent for " + action.block() + " at " + target;
            }
        }
        return "BLOCK_PLACE_FAILED_NO_SUPPORT: no reachable usable support block adjacent to " + target;
    }

    private void renderDebugSidebar(Minecraft client, net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
        if (!debugEnabled || client.player == null || client.level == null) {
            return;
        }
        int maxTextWidth = DEBUG_PANEL_WIDTH - 12;
        String state = (!enabled ? "paused" : defending ? "defending" : choosingGoal ? "choosing goal"
                : decomposing ? "decomposing"
                : planning ? "planning" : activeTask == null ? "idle" : "active")
                + (autonomous ? " [auto]" : " [manual]");
        String step = "Step: " + (taskSteps.isEmpty() ? "no plan"
                : (currentStep + 1) + "/" + taskSteps.size() + " " + taskSteps.get(currentStep));
        String error = "Error: " + errorCode + (lastError.equals("none") ? "" : " - " + lastError);
        java.util.List<String> rawLines = new java.util.ArrayList<>();
        rawLines.add("Actionable - " + state);
        rawLines.add("Task: " + (activeTask == null ? "none" : activeTask));
        if (debugDetailed) {
            rawLines.add("Achieved: " + (achievedGoals.isEmpty() ? "none" : joined(achievedGoals)));
        }
        rawLines.add(step);
        rawLines.add("Why: " + (goalReason.isBlank() ? "none" : goalReason));
        rawLines.add("Safety: " + safetyStatus);
        if (debugDetailed) {
            rawLines.add("Player xyz: " + position(client));
            rawLines.add("Baritone cmd: " + (currentBaritoneCommand.isBlank() ? "none" : currentBaritoneCommand));
            rawLines.add("Baritone: " + compact(baritoneProgress(client), 100));
            rawLines.add("Baritone feedback: " + baritoneStatus);
            rawLines.add("LLM goal: " + currentGoal);
        }
        rawLines.add("Result: " + lastResult);
        if (debugDetailed || !errorCode.equals("none")) {
            rawLines.add(error);
        }
        java.util.List<String> lines = new java.util.ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            String shortened = client.font.plainSubstrByWidth(java.util.Objects.requireNonNull(line), maxTextWidth);
            lines.add(shortened == null ? "" : shortened);
        }
        int lineHeight = client.font.lineHeight + 1;
        int panelHeight = lines.size() * lineHeight + 6;
        // Draw in unscaled panel coordinates, anchored to the top-right corner of the screen.
        graphics.pose().pushMatrix();
        graphics.pose().translate(graphics.guiWidth() - 4 - DEBUG_PANEL_WIDTH * DEBUG_PANEL_SCALE, 4);
        graphics.pose().scale(DEBUG_PANEL_SCALE, DEBUG_PANEL_SCALE);
        graphics.fill(0, 0, DEBUG_PANEL_WIDTH, panelHeight, 0x70000000);
        for (int index = 0; index < lines.size(); index++) {
            graphics.text(client.font, lines.get(index), 6, 4 + index * lineHeight,
                    index == 0 ? 0xFFFFD34E : 0xFFFFFFFF);
        }
        graphics.pose().popMatrix();
    }

    private static String position(Minecraft client) {
        if (client.player == null) {
            return "unavailable";
        }
        BlockPos pos = client.player.blockPosition();
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String plannerErrorCode(Throwable error) {
        String message = ActionPlanner.safeMessage(error);
        if (message.startsWith("CLAUDE_INVALID_JSON:")) {
            return "CLAUDE_INVALID_JSON";
        }
        if (message.startsWith("CLAUDE_TIMEOUT:")) {
            return "CLAUDE_TIMEOUT";
        }
        if (message.startsWith("CLAUDE_REQUEST_FAILED:")) {
            return "CLAUDE_REQUEST_FAILED";
        }
        if (message.contains("HTTP 404")) {
            return "OLLAMA_HTTP_404";
        }
        if (message.contains("HTTP ")) {
            return "OLLAMA_HTTP_ERROR";
        }
        if (message.contains("ConnectException") || message.contains("Connection refused")) {
            return "OLLAMA_UNREACHABLE";
        }
        return "PLANNER_REQUEST_FAILED";
    }

    private static String compact(String text, int maxLength) {
        StringBuilder cleaned = new StringBuilder(text.length());
        text.codePoints()
            .filter(codePoint -> !Character.isISOControl(codePoint))
            .forEach(cleaned::appendCodePoint);
        String clean = cleaned.toString();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength - 3) + "...";
    }

    private static void tell(Minecraft client, String message) {
        ContextCapture.event("mod", message);
        ActionableLog.LOGGER.info("[Actionable] {}", ActionableLog.text(message));
        if (client.player != null) {
            client.gui.hud.setOverlayMessage(Component.literal("[Actionable] " + message), false);
        }
    }

    private record JumpPlace(ActionPlanner.PlannedAction action, BlockPos target, long startedAtTick) {
    }
}
