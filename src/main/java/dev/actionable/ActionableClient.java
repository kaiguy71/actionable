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
    private boolean baritoneCommandRunning;
    private String currentBaritoneCommand = "";
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
    private static final int MAX_TASK_TICKS = 6000;
    private static final int GOAL_MEMORY = 20;
    private boolean autonomous = true;
    private boolean escaping;
    private boolean defending;
    private final EscapeControl escapeControl = new EscapeControl();
    private final DangerMemory dangerMemory = new DangerMemory();
    private long escapeRouteAt;
    private int escapeAttempts;
    private ThreatRules.Threat escapeThreat;
    private boolean deathObserved;
    private int ticksUntilGoal;
    private long taskStartedAtTick;
    private final java.util.ArrayDeque<String> achievedGoals = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> attemptedGoals = new java.util.ArrayDeque<>();
    private boolean stepJudged;
    private final java.util.ArrayDeque<String> history = new java.util.ArrayDeque<>();
    private final PlannerRequests requests = new PlannerRequests();
    private final ActionLoopGuard loopGuard = new ActionLoopGuard();
    private final GoalRetryGuard goalRetries = new GoalRetryGuard();
    private static final boolean MODEL_JUDGE = Boolean.getBoolean("actionable.judge.model");
    private static final int AVOID_MEMORY = 4;
    private static final int SITE_SCAN_RADIUS = 24;
    private Crafter pendingCraft;
    private boolean retreating;
    private boolean stepActionTaken;
    private int replans;
    /** House corner, saved per world; before a house exists, {@link #startArea} stands in as home. */
    private int[] home;
    private int[] startArea;
    private String homeWorld = "";
    private boolean houseBuiltSeen;
    private boolean houseEnclosedSeen;
    private ProgressPlanner.Facts lastHomeFacts = ProgressPlanner.Facts.NONE;
    private Hunter pendingHunt;
    private BlockBreaker pendingBreak;
    private Smelter pendingSmelt;
    private StairMiner pendingStairs;
    private DeepMiner pendingDeep;
    private int waterSearchAttempts;
    /** Where furniture was broken out of the way; the drop is walked over so it is picked up. */
    private BlockPos furnitureDrop;
    /**
     * Item to pick up after a code-driven block break for a mine step (flint from gravel). Drops were
     * left on the ground, so "mine 1 flint" broke gravel forever without ever holding flint.
     */
    private String collectItem;
    private long collectStartAt;
    private long collectUntil;
    private BlockPos collectGoto;
    /** Cells whose furniture was displaced once; never treated as an obstruction again. */
    private final java.util.Set<BlockPos> displacedCells = new java.util.HashSet<>();
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
    private boolean holdingJump;
    private boolean baritoneConfigured;
    private int waterTicks;
    private boolean waterRescuing;
    private long waterRouteAt;
    private long lastSleepAttempt;
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
    private String pendingMine;
    private long pendingMineSince;
    private String goalReason = "";
    private String safetyStatus = "unknown";
    private final java.util.ArrayDeque<int[]> avoidAreas = new java.util.ArrayDeque<>();

    @Override
    @SuppressWarnings("null")
    public void onInitializeClient() {
        ActionableLog.LOGGER.info("[Actionable] initialized provider={} autonomous={} background={} log=logs/latest.log",
                ActionableLog.text(System.getProperty("actionable.model.provider", "ollama")), autonomous,
                Boolean.getBoolean("actionable.background"));
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

        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (victim, source, baseDamage, taken, blocked) -> {
                    var client = Minecraft.getInstance();
                    if (taken <= 0 || client.player == null || !victim.getUUID().equals(client.player.getUUID())) return;
                    var attacker = source.getEntity();
                    if (attacker != null) {
                        ThreatProbe.recordAttacker(attacker.getUUID());
                        ActionableLog.LOGGER.info("[Actionable] DAMAGE_THREAT attacker={} damage={} health={}",
                                BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()), taken, victim.getHealth());
                    }
                });
        ClientLifecycleEvents.CLIENT_STOPPING.register(Screenshots::archiveSession);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            cancelHomeWork(client);
            Screenshots.archiveSession(client);
            waterSearchAttempts = 0;
            ThreatProbe.clear();
            goalRetries.clear();
            dangerMemory.clear();
            requests.invalidate();
            escapeControl.reset();
            escaping = defending = waterRescuing = deathObserved = false;
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
                    loopGuard.markLastFailed();
                    lastResult = "Baritone rejected the last command: " + lastError;
                    if (activeTask != null && !requests.planning) {
                        ticksUntilPlan = 0;
                    }
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            clientTick++;
            while (toggleKey.consumeClick()) {
                boolean controlDown = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
                        || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
                if (controlDown) {
                    toggle(client);
                }
            }
            if (client.isPaused()) return;
            if (tickWater(client)) {
                return;
            }
            updateBaritoneProgress(client);
            if (tickThreatResponse(client)) {
                return;
            }
            tickUnstick(client);
            tickJumpPlace(client);
            tickCraft(client);
            tickMining(client);
            tickHunt(client);
            tickBreak(client);
            tickCollect(client);
            tickSmelt(client);
            tickStairs(client);
            tickDeepMine(client);
            tickEat(client);
            if (enabled && clientTick % 100 == 0 && eatPhase == 0 && pendingCraft == null && pendingSmelt == null
                    && pendingBreak == null && !defending) {
                arrangeHotbar(client);
            }
            tickStepCheck(client);
            tickHouseDoor(client);
            tickSafety(client);
            tickNight(client);
            if (enabled && activeTask != null && (!nightRoutine || nightJob)
                    && pendingStairs == null && pendingDeep == null && clientTick - taskStartedAtTick > MAX_TASK_TICKS) {
                abandonTask(client, "took too long");
            }
            if (enabled && autonomous && activeTask == null && !requests.choosingGoal && !requests.decomposing && !nightRoutine
                    && client.player != null && client.level != null && ticksUntilGoal-- <= 0) {
                requestGoalSelection(client);
            }
            // Night, return-home and hunting are run by code; the model is not consulted while they run.
            if (enabled && activeTask != null && !requests.planning && !requests.decomposing && !requests.judging && (!nightRoutine || nightJob)
                    && pendingJumpPlace == null && pendingCraft == null && pendingHunt == null
                    && pendingBreak == null && collectItem == null && pendingSmelt == null && pendingStairs == null
                    && pendingDeep == null
                    && eatPhase == 0 && ticksUntilPlan-- <= 0) {
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
        cancelHomeWork(client);
        client.getConnection().sendChat("#stop");
        baritoneCommandRunning = false;
        currentBaritoneCommand = "";
        pendingMine = null;
        pendingCraft = null;
        achievedGoals.clear();
        attemptedGoals.clear();
        avoidAreas.clear();
        requests.choosingGoal = false;
        requests.decomposing = false;
        requests.judging = false;
        escaping = false;
        retreating = false;
        planner.attachImage(null);
        requests.invalidate();
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
        requests.planning = false;
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
        loopGuard.reset();
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
            tickThreatResponse(client);
            return;
        }

        long requestGeneration = requests.begin(PlannerRequests.Phase.DIRECTOR);
        currentGoal = "Choosing a goal";
        planner.chooseGoal(synopsis, briefing, joined(achievedGoals), joined(attemptedGoals))
                .whenComplete((choice, error) -> client.execute(() -> {
                    if (!requests.complete(requestGeneration)) {
                        return;
                    }
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
                    // Repeat avoidance is also in code: the model re-picked goals it had just achieved,
                    // and goals it had already failed twice.
                    if (chosen.isEmpty() || alreadyAchieved(chosen) || attemptedRepeatedly(chosen)
                            || !goalRetries.available(chosen, goalRetryContext(client), clientTick)
                            || problem.isPresent()) {
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
                            // Every milestone is done or unreachable; there is always a goal, and diamonds
                            // are what the Nether route and every later stage need.
                            tell(client, "No new goal available; looking for diamonds.");
                            chosen = ProgressPlanner.FALLBACK_GOAL;
                            why = "no milestone is reachable right now; diamonds are needed for every later stage";
                        } else {
                            chosen = milestone.get().goal();
                            why = milestone.get().why();
                            tell(client, "Using milestone goal: " + chosen);
                        }
                    }
                    chosen = PlanSanity.clampQuantity(chosen);
                    if (!goalRetries.available(chosen, goalRetryContext(client), clientTick)) {
                        ticksUntilGoal = (int) GoalRetryGuard.COOLDOWN_TICKS;
                        currentGoal = "Waiting for failed goal cooldown";
                        return;
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
        var world = facts(client);
        if (home != null && client.level != null && WorldProbe.houseStructure(client.level, home).loaded()
                && !world.houseBuilt()) {
            return java.util.Optional.of(new ProgressPlanner.Milestone("house", 1,
                    "repair every wall/roof gap in the existing house", ProgressPlanner.Kind.HOUSE));
        }
        // Exact goal text: goalKey would merge "build house" with "place ... bed in house".
        return ProgressPlanner.nextMilestone(WorldProbe.inventory(client.player), world)
                .filter(next -> goalRetries.available(next.goal(), goalRetryContext(client), clientTick))
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
        HouseStructure.Result structure = home == null ? null : WorldProbe.houseStructure(client.level, home);
        if (structure != null && structure.loaded()) {
            if (houseBuiltSeen != structure.shellReady() || houseEnclosedSeen != structure.enclosed()) {
                ActionableLog.LOGGER.info("[Actionable] house_structure at={},{},{} shell={} enclosed={} reason={}",
                        home[0], home[1], home[2], structure.shellReady(), structure.enclosed(), structure.reason());
            }
            houseBuiltSeen = structure.shellReady();
            houseEnclosedSeen = structure.enclosed();
        }
        BlockPos center = home != null ? new BlockPos(home[0] + 2, home[1], home[2] + 2) : client.player.blockPosition();
        boolean bed = home == null ? WorldProbe.nearestBed(client.level, center, 24) != null : lastHomeFacts.bedPlaced();
        boolean door = lastHomeFacts.doorPlaced();
        boolean atHome = false;
        boolean floor = lastHomeFacts.floorReady();
        boolean furnace = lastHomeFacts.furnaceInHouse();
        boolean mine = lastHomeFacts.mineFinished();
        if (structure != null && structure.loaded()) {
            bed = WorldProbe.nearestBed(client.level, center, 6) != null;
            // Exact cells, so a table from the old layout (now the stairs) is moved to its new spot.
            houseTableSeen = client.level.getBlockState(homeCell(HouseDesign.TABLE_CELL))
                    .is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
            furnace = client.level.getBlockState(homeCell(HouseDesign.FURNACE_CELL))
                    .is(net.minecraft.world.level.block.Blocks.FURNACE);
            StairMine.Probe probe = StairMiner.probe(client.level, home);
            int maxStep = StairMine.maxStep(home[1]);
            int depth = StairMine.depth(probe, maxStep);
            mine = depth >= maxStep || (depth > 0 && StairMine.check(probe, depth) != StairMine.Check.OK);
            door = structure.doorReady() && WorldProbe.strayDoors(client.level, home).isEmpty();
            BlockPos feet = client.player.blockPosition();
            atHome = HouseDesign.inside(home, feet.getX(), feet.getY(), feet.getZ());
            floor = structure.floorReady() && structure.dryInterior() && structure.entryClear() && structure.interiorClear();
        }
        lastHomeFacts = new ProgressPlanner.Facts(houseBuiltSeen, bed, houseTableSeen, door, atHome, floor, furnace, mine,
                client.level.dimension().equals(net.minecraft.world.level.Level.NETHER));
        return lastHomeFacts;
    }

    /** Loads this world's saved home once per world. */
    private void loadHome(Minecraft client) {
        String key = HomeStore.worldKey(client);
        if (!key.equals(homeWorld)) {
            homeWorld = key;
            home = HomeStore.load(client);
            WorldProbe.home = home;
            houseBuiltSeen = false;
            houseEnclosedSeen = false;
            lastHomeFacts = ProgressPlanner.Facts.NONE;
            houseTableSeen = false;
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
                + "\n" + NeedsPlanner.report(inventory, situation(client, facts));
    }

    /** Used by task selection only; the survival tick owns all fighting and escape movement. */
    private String dangerEscape(Minecraft client) {
        ThreatRules.Decision decision = ThreatProbe.scan(client);
        if (decision.mode() != ThreatRules.Mode.FLEE || client.player == null) return null;
        var player = client.player;
        var threat = decision.threat();
        return fleeTo(player.getBlockX(), player.getBlockY(), player.getBlockZ(), (int) threat.x(), (int) threat.z());
    }

    /** Interrupt every kind of planner request and action before survival takes control. */
    private void interruptForSurvival(Minecraft client) {
        if (requests.decomposing && taskSteps.isEmpty() && activeTask != null) {
            taskSteps = java.util.List.of(activeTask);
            currentStep = 0;
        }
        requests.invalidate();
        pendingJumpPlace = null;
        pendingHunt = null;
        cancelCraft(client);
        cancelBreak(client);
        cancelHomeWork(client);
        stopBaritone(client);
        if (client.player != null) client.player.stopUsingItem();
        eatPhase = 0;
        directStep = null;
        stepActionTaken = false;
        stepJudged = false;
        nightRoutine = false;
        nightJob = false;
        retreating = false;
        errorCode = "none";
        lastError = "none";
    }

    /** Code-owned survival: no model requests, completion claims or task timeout can end an escape. */
    private boolean tickThreatResponse(Minecraft client) {
        var player = client.player;
        if (!enabled || player == null || client.level == null || client.getConnection() == null
                || (!autonomous && activeTask == null)) return false;
        if (!player.isAlive()) {
            if (!deathObserved) {
                interruptForSurvival(client);
                escapeControl.reset();
                escaping = defending = false;
                activeTask = null;
                taskSteps = java.util.List.of();
                deathObserved = true;
                ActionableLog.LOGGER.info("[Actionable] SURVIVAL_DEAD: planning stopped until respawn");
            }
            return true;
        }
        if (deathObserved) {
            deathObserved = false;
            requests.invalidate();
            ticksUntilGoal = 20;
            ActionableLog.LOGGER.info("[Actionable] SURVIVAL_RESPAWN: observation restarted");
        }
        ThreatRules.Decision decision = ThreatProbe.scan(client);
        ThreatRules.Mode mode = escapeControl.update(decision.mode(), clientTick);
        if (decision.threat() != null && clientTick % 20 == 0) {
            var threat = decision.threat();
            ActionableLog.LOGGER.info("[Actionable] THREAT mode={} kind={} entity_id={} xyz={},{},{} distance={} visible={} vertical_gap={} reachable={} targeting={} closing={} primed={} health={} reason={}",
                    mode, threat.kind(), threat.entityId(), Math.round(threat.x()), Math.round(threat.y()), Math.round(threat.z()),
                    Math.round(threat.distance()), threat.visible(), Math.round(threat.verticalGap()), threat.reachable(),
                    threat.targeting(), threat.closing(), threat.primed(), player.getHealth(), decision.reason());
        }
        if (mode == ThreatRules.Mode.FIGHT) {
            if (!defending || escaping) {
                interruptForSurvival(client);
                defending = true;
                escaping = false;
                ActionableLog.LOGGER.info("[Actionable] defense_started entity_id={} task={}",
                        decision.threat().entityId(), ActionableLog.text(activeTask));
            }
            // A live non-pausing menu must not prevent emergency defense in a test client.
            if (client.gui.screen() != null) {
                player.closeContainer();
                client.gui.setScreen(null);
            }
            MeleeCombat.ensureWeaponInHotbar(client);
            lastAction = "attack entity_id=" + decision.threat().entityId();
            lastResult = MeleeCombat.attack(client, decision.threat().entityId());
            if (lastResult.startsWith("ATTACK_SENT")) {
                ActionableLog.LOGGER.info("[Actionable] combat {}", ActionableLog.text(lastResult));
            }
            currentGoal = "Defending against " + decision.threat().kind();
            return true;
        }
        if (mode == ThreatRules.Mode.FLEE) {
            if (decision.mode() == ThreatRules.Mode.FLEE && decision.threat() != null) {
                dangerMemory.record(decision.threat(), clientTick);
            }
            if (!escaping) {
                interruptForSurvival(client);
                escaping = true;
                defending = false;
                escapeAttempts = 0;
                escapeRouteAt = clientTick - 100;
                var threat = decision.threat();
                ActionableLog.LOGGER.info("[Actionable] ESCAPE_STARTED kind={} entity_id={} xyz={},{},{} distance={} reason={} task={}",
                        threat == null ? "unknown" : threat.kind(), threat == null ? -1 : threat.entityId(),
                        threat == null ? 0 : Math.round(threat.x()), threat == null ? 0 : Math.round(threat.y()),
                        threat == null ? 0 : Math.round(threat.z()), threat == null ? 0 : Math.round(threat.distance()),
                        decision.reason(), ActionableLog.text(activeTask));
            }
            if (decision.mode() == ThreatRules.Mode.FLEE && decision.threat() != null) escapeThreat = decision.threat();
            if (EscapeControl.reroute(clientTick, escapeRouteAt, baritoneBusy(client), clientTick - lastBaritoneMovementAt)) {
                var threat = escapeThreat;
                BlockPos here = player.blockPosition();
                double tx = threat == null ? here.getX() + 1 : threat.x();
                double tz = threat == null ? here.getZ() + 1 : threat.z();
                BlockPos destination = WorldProbe.escapeTarget(client.level, here, tx, tz, escapeAttempts++);
                if (destination == null && player.isInWater()) {
                    destination = WorldProbe.nearestWaterExit(client.level, here, 16);
                }
                String command = destination == null
                        ? fleeTo(here.getX(), here.getY(), here.getZ(), (int) tx, (int) tz)
                        : "goto " + destination.getX() + " " + destination.getY() + " " + destination.getZ();
                stopBaritone(client);
                executeBaritone(client, client.getConnection(), command);
                escapeRouteAt = clientTick;
                ActionableLog.LOGGER.info("[Actionable] ESCAPE_ROUTE attempt={} command={} reason={}",
                        escapeAttempts, command, decision.reason());
            }
            currentGoal = "Escaping threats (code controlled)";
            return true;
        }
        if (escaping || defending) {
            boolean wasEscape = escaping;
            stopBaritone(client);
            escaping = defending = false;
            escapeThreat = null;
            requests.invalidate();
            loopGuard.reset();
            taskStartedAtTick = clientTick;
            directStep = null;
            stepActionTaken = false;
            stepJudged = false;
            ticksUntilPlan = 0;
            ticksUntilGoal = 0;
            remember(wasEscape ? "escape" : "defend", "SURVIVAL_CLEAR: observed threat cleared; resume interrupted task");
        }
        return false;
    }

    static String fleeTo(int fromX, int fromY, int fromZ, int awayX, int awayZ) {
        int dx = fromX - awayX;
        int dz = fromZ - awayZ;
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        double length = Math.sqrt((double) dx * dx + (double) dz * dz);
        return "goto " + (fromX + (int) Math.round(dx / length * 40))
                + " " + (fromZ + (int) Math.round(dz / length * 40));
    }

    private void startGoal(Minecraft client, String goal, java.util.List<String> steps) {
        requests.invalidate();
        activeTask = goal;
        taskSteps = java.util.List.copyOf(steps);
        currentStep = 0;
        stepJudged = false;
        stepActionTaken = false;
        directStep = null;
        replans = 0;
        history.clear();
        loopGuard.reset();
        taskStartedAtTick = clientTick;
        taskOrigin = java.util.Objects.requireNonNull(client.player).blockPosition();
        lastAction = "";
        lastResult = "Starting: " + goal;
        currentGoal = steps.isEmpty() ? goal : steps.get(0);
        errorCode = "none";
        lastError = "none";
        requests.planning = false;
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
        requests.invalidate();
        if (client.player != null) {
            var inventory = WorldProbe.inventory(client.player);
            ActionableLog.LOGGER.info("[Actionable] situation task={} inventory={} report={}", ActionableLog.text(prompt),
                    ActionableLog.text(inventory.toString()),
                    ActionableLog.text(NeedsPlanner.report(inventory, situation(client, facts(client)))));
        }
        taskSteps = java.util.List.of();
        currentStep = 0;
        stepJudged = false;
        stepActionTaken = false;
        directStep = null;
        replans = 0;

        history.clear();
        loopGuard.reset();
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
        requests.planning = false;
        ticksUntilPlan = 0;
        requestDecomposition(client);
    }

    private void abandonTask(Minecraft client, String reason) {
        if (activeTask != null && (reason.startsWith("no progress") || reason.equals("took too long"))) {
            goalRetries.failed(activeTask, goalRetryContext(client), clientTick);
            ActionableLog.LOGGER.info("[Actionable] GOAL_COOLDOWN goal={} ticks={} reason={}",
                    ActionableLog.text(activeTask), GoalRetryGuard.COOLDOWN_TICKS, reason);
        }
        requests.invalidate();
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
        activeTask = null;
        taskSteps = java.util.List.of();
        ticksUntilGoal = 40;
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

    /** True when this goal was already attempted twice recently without being achieved. */
    private boolean attemptedRepeatedly(String goal) {
        String key = attemptKey(goal);
        if (key.isEmpty() || alreadyAchieved(goal)) {
            return false;
        }
        int attempts = 0;
        for (String tried : attemptedGoals) {
            if (attemptKey(tried).equals(key)) {
                attempts++;
            }
        }
        return attempts >= 2;
    }

    /**
     * Whole-text identity ignoring case, spacing and quantities. Deliberately not goalKey: that keeps
     * only the last word, so every "... crafting table" goal would count as the same goal.
     */
    static String attemptKey(String goal) {
        return goal.toLowerCase(java.util.Locale.ROOT).replaceAll("\\d+", "").replaceAll("\\s+", " ").strip();
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

    /** Goal identity preserves the action and location: house furniture is not the house itself. */
    static String goalKey(String goal) {
        return ProgressPlanner.parse(goal)
                .map(step -> step.verb() + ":" + step.count() + ":" + TechTree.dropOf(step.item()))
                .orElseGet(() -> goal.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip());
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
        long requestGeneration = requests.begin(PlannerRequests.Phase.DECOMPOSITION);
        currentGoal = "Breaking the task into steps";
        planner.decompose(activeTask, synopsis, briefing(client, synopsis)).whenComplete((steps, error) -> client.execute(() -> {
            if (!requests.complete(requestGeneration)) {
                return;
            }
            if (!enabled || activeTask == null || client.player == null) {
                return;
            }
            java.util.List<String> usable = error == null && steps != null
                    ? PlanSanity.sanitizeSteps(steps) : java.util.List.of();
            if (steps != null && usable.size() < steps.size()) {
                ActionableLog.LOGGER.info("[Actionable] plan_filtered kept={} of={}", usable.size(), steps.size());
            }
            if (error != null || usable.isEmpty()) {
                // Decomposition is an optimisation: fall back to treating the prompt as one step.
                taskSteps = java.util.List.of(activeTask);
                if (error != null) {
                    errorCode = plannerErrorCode(error);
                    lastError = compact(ActionPlanner.safeMessage(error), 140);
                }
                tell(client, "Could not split the task; working on it directly.");
            } else {
                taskSteps = ProgressPlanner.normalizeSteps(usable);
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
        long requestGeneration = requests.begin(PlannerRequests.Phase.JUDGE);
        String objective = taskSteps.get(currentStep);
        String inventory = inventoryOf(WorldSynopsis.capture(client));
        planner.checkStep(objective, inventory, historyText()).whenComplete((done, error) -> client.execute(() -> {
            if (!requests.complete(requestGeneration)) {
                return;
            }
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
        stopBaritone(client);
        currentStep++;
        stepJudged = false;
        loopGuard.reset();
        stepActionTaken = false;
        directStep = null;

        currentGoal = taskSteps.get(currentStep);
        lastAction = "";
        lastResult = "Step " + currentStep + " done (" + finished + "); starting next step";
        tell(client, "Step " + (currentStep + 1) + "/" + taskSteps.size() + ": " + taskSteps.get(currentStep));
        ticksUntilPlan = 1;
    }

    /** Changes whenever carried items, the current step, or the player's coarse position change. */
    private String progressKey(Minecraft client, String synopsis) {
        BlockPos pos = client.player == null ? BlockPos.ZERO : client.player.blockPosition();
        return inventoryOf(synopsis) + "|step=" + currentStep
                + "|cell=" + (pos.getX() >> 3) + ',' + (pos.getY() >> 3) + ',' + (pos.getZ() >> 3);
    }

    private String goalRetryContext(Minecraft client) {
        if (client.player == null) return "no player";
        BlockPos pos = client.player.blockPosition();
        return WorldProbe.inventory(client.player).toString() + "|cell=" + (pos.getX() >> 3) + ','
                + (pos.getY() >> 3) + ',' + (pos.getZ() >> 3);
    }

    private static String inventoryOf(String synopsis) {
        StringBuilder items = new StringBuilder();
        for (String field : synopsis.split("; ")) {
            if (field.startsWith("totals=") || field.startsWith("inventory=")
                    || field.startsWith("held_item=") || field.startsWith("armor=")
                    || field.startsWith("offhand=")) {
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
        requests.invalidate();
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
        pendingMine = null;
        activeTask = null;
        taskSteps = java.util.List.of();
        ticksUntilGoal = 60;
    }

    private void requestNextPlan(Minecraft client) {
        if (escaping || defending || waterRescuing) return;
        if (client.player == null || client.level == null || client.getConnection() == null || activeTask == null) {
            ticksUntilPlan = 20;
            return;
        }
        if (leaveWater(client)) {
            ticksUntilPlan = 60;
            return;
        }
        loopGuard.observe(progressKey(client, WorldSynopsis.capture(client)));
        if (loopGuard.stalled()) {
            abandonTask(client, "no progress for " + loopGuard.stalledRounds() + " planning rounds");
            return;
        }
        if (!taskSteps.isEmpty()) {
            // Step completion is decided in code from the inventory; the model call is opt-in.
            String step = taskSteps.get(currentStep);
            java.util.Optional<Boolean> done = ProgressPlanner.stepSatisfied(step,
                    WorldProbe.inventory(client.player), tableNearby(client), facts(client));
            if (done.orElse(false)) {
                advanceStep(client, step);
                return;
            }
            var verdict = done.isPresent() ? StepCheck.Verdict.NOT_DONE
                    : StepCheck.check(step, InventoryAccess.counts(client.player));
            if (verdict == StepCheck.Verdict.DONE) {
                advanceStep(client, step);
                return;
            }
            boolean askJudge = !stepJudged;
            stepJudged = true;
            if (verdict == StepCheck.Verdict.UNKNOWN && MODEL_JUDGE && askJudge && !history.isEmpty()) {
                requestStepCheck(client);
                return;
            }
        }
        if (!taskSteps.isEmpty() && Recovery.replannable(lastResult) && replans < Recovery.MAX_REPLANS
                && replanFromInventory(client, lastResult)) {
            return;
        }
        if (!taskSteps.isEmpty() && !stepActionTaken && executeStepDirectly(client)) {
            return;
        }
        if (!taskSteps.isEmpty() && directStep != null && continueDirectStep(client)) {
            return;
        }
        long requestGeneration = requests.begin(PlannerRequests.Phase.ACTION);
        String synopsis = WorldSynopsis.capture(client) + "; " + baritoneProgress(client);
        String taskWithOrigin = activeTask + "\nTask prompt origin xyz: "
                + (taskOrigin == null ? "unknown" : taskOrigin.getX() + "," + taskOrigin.getY() + "," + taskOrigin.getZ());
        planner.plan(taskWithOrigin, synopsis, lastAction, lastResult, planContext(), historyText())
                .whenComplete((plan, error) -> client.execute(() -> {
            if (!requests.complete(requestGeneration)) {
                return;
            }
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
                loopGuard.record("model_error", "MODEL_FAILED", progressKey(client, WorldSynopsis.capture(client)));
                ticksUntilPlan = 200;
                return;
            }
            errorCode = "none";
            lastError = "none";
            currentGoal = plan.goal();
            tell(client, "Plan: " + plan.summary());
            if ((plan.complete() || plan.stepComplete()) && !taskSteps.isEmpty()) {
                var inventory = WorldProbe.inventory(client.player);
                var world = facts(client);
                var stepVerdict = CompletionCheck.verdict(taskSteps.get(currentStep), inventory, tableNearby(client), world);
                var taskVerdict = CompletionCheck.verdict(activeTask, inventory, tableNearby(client), world);
                if (!CompletionCheck.mayComplete(plan.complete(), currentStep, taskSteps.size(), stepVerdict, taskVerdict)) {
                    lastAction = "step_complete";
                    lastResult = "STEP_COMPLETION_REJECTED: current step/task is not satisfied by inventory or world facts";
                    errorCode = "STEP_COMPLETION_REJECTED";
                    lastError = lastResult;
                    remember(lastAction, lastResult);
                    loopGuard.record(lastAction, lastResult, progressKey(client, WorldSynopsis.capture(client)));
                    ticksUntilPlan = 20;
                    return;
                }
            }
            if (plan.complete()) {
                finishTask(client, connection, "Planner marked the task complete");
                return;
            }
            if (plan.stepComplete() && !taskSteps.isEmpty()) {
                advanceStep(client, taskSteps.get(currentStep));
                return;
            }
            String progress = progressKey(client, WorldSynopsis.capture(client));
            loopGuard.observe(progress);
            if (loopGuard.stalled()) {
                abandonTask(client, "no progress for " + loopGuard.stalledRounds() + " planning rounds");
                return;
            }
            if (plan.action().isEmpty()) {
                lastResult = "No valid action returned. " + plan.summary();
                errorCode = "PLAN_NO_VALID_ACTION";
                lastError = compact(plan.summary(), 140);
                tell(client, "No valid action [" + errorCode + "]: " + lastError);
                loopGuard.record("none", "NO_ACTION_FAILED", progress);
                ticksUntilPlan = Recovery.delayAfter("", "PLAN_NO_VALID_ACTION");
                return;
            }

            ActionPlanner.PlannedAction action = plan.action().orElseThrow();
            String signature = ActionLoopGuard.signature(action);
            var vetoed = loopGuard.veto(signature);
            if (vetoed.isPresent()) {
                lastAction = signature;
                lastResult = vetoed.get();
                errorCode = "REPEAT_BLOCKED";
                lastError = compact(lastResult, 140);
                tell(client, lastResult);
                remember(lastAction, lastResult);
                loopGuard.record(signature, lastResult, progress);
                ticksUntilPlan = 20;
                return;
            }
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
                case "equip" -> {
                    lastAction = "equip " + action.command();
                    lastResult = InventoryAccess.equip(client, action.command());
                    if (lastResult.contains("_FAILED")) {
                        errorCode = lastResult.substring(0, lastResult.indexOf(':'));
                        lastError = compact(lastResult, 140);
                    } else {
                        errorCode = "none";
                        lastError = "none";
                    }
                    tell(client, lastResult);
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
                case "use_item" -> {
                    stopBaritone(client);
                    lastAction = "use_item " + action.block() + " at " + action.x() + "," + action.y() + "," + action.z();
                    lastResult = ItemUse.use(client, action.block(), action.command(), action.x(), action.y(), action.z());
                    reportBlockResult(client);
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
                    long shotGeneration = requests.generation;
                    Screenshots.capture(client, debugEnabled, image -> {
                        if (shotGeneration == requests.generation && image != null) {
                            planner.attachImage(image);
                        }
                    });
                    tell(client, "Taking a screenshot for the model.");
                    ticksUntilPlan = 15;
                }
                case "place_block" -> {
                    stopBaritone(client);
                    lastAction = "place " + action.block() + " at " + action.x() + "," + action.y() + "," + action.z();
                    lastResult = placeWithFallback(client, action);
                    reportBlockResult(client);
                }
                case "jump_place" -> {
                    stopBaritone(client);
                    lastAction = "jump-place " + action.block() + " at " + action.x() + "," + action.y() + "," + action.z();
                    // Jumping is only needed when boxed in; a free spot nearby is faster and more reliable.
                    lastResult = client.level != null && client.player != null
                            && !WorldProbe.placementCandidates(client.level, client.player.blockPosition()).isEmpty()
                            ? placeWithFallback(client, action) : startJumpPlace(client, action);
                    reportBlockResult(client);
                }
                case "baritone" -> {
                    String command = taskSteps.isEmpty() ? action.command()
                            : ProgressPlanner.alignMineQuantity(action.command(), taskSteps.get(currentStep));
                    if (command.startsWith("mine ")) {
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
            loopGuard.record(signature, lastResult, progress);
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
        if (step.equalsIgnoreCase("fill water bucket")) {
            fillWaterBucket(client, connection);
            ticksUntilPlan = 20;
        } else if (parsed.isPresent() && parsed.get().verb().equals("craft")) {
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
            boolean repairing = home != null;
            startHouseBuild(client, connection);
            ticksUntilPlan = lastResult.contains("_FAILED") ? 20 : repairing ? 10 : 200;
        } else if (step.equalsIgnoreCase(ProgressPlanner.GO_TABLE)) {
            BlockPos table = WorldProbe.nearestCraftingTable(client.level, player.blockPosition());
            if (table == null) {
                lastAction = step;
                lastResult = "CRAFT_FAILED_NO_TABLE: no loaded crafting table remains; re-plan from current inventory";
                reportBlockResult(client);
                return true;
            }
            BlockPos stand = java.util.List.of(table.north(), table.south(), table.east(), table.west()).stream()
                    .filter(pos -> client.level.getBlockState(pos).isAir()
                            && client.level.getBlockState(pos.above()).isAir()
                            && client.level.getBlockState(pos.below()).getFluidState().isEmpty()
                            && client.level.getBlockState(pos.below()).isFaceSturdy(client.level, pos.below(), Direction.UP))
                    .min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(player.blockPosition())))
                    .orElse(table.above());
            executeBaritone(client, connection, "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            lastAction = step;
            lastResult = "Walking to the existing crafting table; leaving it standing";
            ticksUntilPlan = 100;
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
            // Any bed in the inventory goes back in, including one picked up after being moved.
            String bed = WorldProbe.inventory(player).keySet().stream().filter(TechTree::isBed).findFirst()
                    .orElse(TechTree.WHITE_BED);
            placeInHouse(client, connection, step, bed, HouseDesign.BED_STAND, HouseDesign.BED_FOOT);
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
    private void fillWaterBucket(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        var inventory = WorldProbe.inventory(java.util.Objects.requireNonNull(client.player));
        var decision = BucketPlan.choose(inventory.getOrDefault("minecraft:water_bucket", 0) > 0,
                inventory.getOrDefault("minecraft:bucket", 0) > 0, WorldProbe.bucketSources(client));
        lastAction = "fill water bucket";
        var source = decision.source();
        switch (decision.mode()) {
            case DONE -> lastResult = "WATER_BUCKET_VERIFIED: filled bucket observed in inventory";
            case NEED_BUCKET -> lastResult = "ITEM_USE_FAILED_NO_BUCKET: craft an empty bucket first";
            case FILL -> {
                stopBaritone(client);
                lastResult = ItemUse.use(client, "minecraft:bucket", "up", source.x(), source.y(), source.z());
            }
            case WALK -> {
                executeBaritone(client, connection, "goto " + source.standX() + " " + source.standY() + " " + source.standZ());
                lastResult = "Walking to a dry bank within reach of observed water source at "
                        + source.x() + "," + source.y() + "," + source.z();
            }
            case EXPLORE -> {
                if (!baritoneBusy(client)) {
                    var player = java.util.Objects.requireNonNull(client.player);
                    executeBaritone(client, connection,
                            BucketPlan.searchCommand(player.getBlockX(), player.getBlockZ(), waterSearchAttempts++));
                }
                lastResult = "Searching loaded terrain for a water source reachable from dry ground";
            }
        }
        ActionableLog.LOGGER.info("[Actionable] BUCKET mode={} result={}", decision.mode(), ActionableLog.text(lastResult));
    }

    private boolean fixHouseFloor(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        var player = java.util.Objects.requireNonNull(client.player);
        var level = java.util.Objects.requireNonNull(client.level);
        BlockPos cell = WorldProbe.houseFloorProblem(level, home, displacedCells::contains);
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
        if (walkable && level.getBlockState(cell).getFluidState().isEmpty()
                && !level.getBlockState(cell).canBeReplaced()) {
            return breakForDoor(client, cell, "Clearing blocked entrance headroom");
        }
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
            if (WorldProbe.houseStructure(level, home).doorReady()) return false;
            return breakForDoor(client, lower, "Replacing a malformed house door");
        }
        if (level.getBlockState(upper).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
            return breakForDoor(client, upper, "Removing an orphaned upper door half");
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
        if (goal.equalsIgnoreCase(ProgressPlanner.BUILD_HOUSE) && home != null && client.level != null) {
            var structure = WorldProbe.houseStructure(client.level, home);
            if (!structure.loaded()) return java.util.List.of(ProgressPlanner.GO_HOME, ProgressPlanner.BUILD_HOUSE);
            String planks = WorldProbe.mostHeldPlanks(inventory, preferredLog);
            var steps = new java.util.ArrayList<>(ProgressPlanner.stepsFor(planks, structure.missingShell(),
                    inventory, tableAccess(client), preferredLog));
            steps.add(ProgressPlanner.BUILD_HOUSE);
            return java.util.List.copyOf(steps);
        }
        return ProgressPlanner.stepsForGoal(goal, inventory, tableAccess(client), preferredLog, facts(client));
    }

    private boolean breakForDoor(Minecraft client, BlockPos pos, String why) {
        stopBaritone(client);
        var state = java.util.Objects.requireNonNull(client.level).getBlockState(pos);
        if (WorldProbe.furniture(state)) {
            // Breaking furniture out of the way is fine, but it is picked back up and put back afterwards.
            furnitureDrop = pos.immutable();
            displacedCells.add(pos.immutable());
            ActionableLog.LOGGER.info("[Actionable] furniture_displaced block={} at={},{},{}",
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()), pos.getX(), pos.getY(), pos.getZ());
        }
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

    private void startCollect(String item) {
        collectItem = item;
        collectStartAt = clientTick + 4;
        collectUntil = clientTick + 100;
        collectGoto = null;
    }

    /** Walks onto dropped items of the step's kind so they are picked up, then lets planning continue. */
    private void tickCollect(Minecraft client) {
        if (collectItem == null || clientTick < collectStartAt) {
            return;
        }
        var player = client.player;
        var connection = client.getConnection();
        if (!enabled || player == null || client.level == null || connection == null || clientTick > collectUntil) {
            collectItem = null;
            ticksUntilPlan = 1;
            return;
        }
        net.minecraft.world.entity.item.ItemEntity nearest = null;
        double best = 10;
        for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity drop && drop.isAlive()
                    && itemId(drop.getItem()).equals(collectItem) && drop.distanceTo(player) < best) {
                nearest = drop;
                best = drop.distanceTo(player);
            }
        }
        if (nearest == null) {
            // Nothing of that kind dropped (gravel gives flint only sometimes): carry on.
            collectItem = null;
            ticksUntilPlan = 1;
            return;
        }
        BlockPos at = nearest.blockPosition();
        if (!at.equals(collectGoto)) {
            collectGoto = at;
            executeBaritone(client, connection, "goto " + at.getX() + " " + at.getY() + " " + at.getZ());
            ActionableLog.LOGGER.info("[Actionable] collect item={} at={},{},{}", ActionableLog.text(collectItem),
                    at.getX(), at.getY(), at.getZ());
        }
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
        if (step.equalsIgnoreCase(ProgressPlanner.GO_TABLE) && client.level != null) {
            BlockPos table = WorldProbe.nearestCraftingTable(client.level, player.blockPosition());
            return table == null ? Integer.MIN_VALUE : -(int) Math.round(Math.sqrt(player.blockPosition().distSqr(table)));
        }
        if (home == null || client.level == null) {
            return 0;
        }
        if (step.equalsIgnoreCase(ProgressPlanner.BUILD_HOUSE)) {
            return (int) Math.round(WorldProbe.houseCompletion(client.level, home) * HouseDesign.planksNeeded());
        }
        if (step.equalsIgnoreCase(ProgressPlanner.LAY_FLOOR)) {
            return -WorldProbe.houseStructure(client.level, home).roomRepairs();
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
        if (steps.size() > ActionPlanner.MAX_STEPS + 4) {
            return false;
        }
        replans++;
        loopGuard.reset();
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
        boolean exact = TechTree.isDoor(action.block()) || action.block().endsWith("_bed")
                || action.block().equals("minecraft:obsidian");
        if (exact || !Recovery.badPlacementSpot(result) || client.player == null || client.level == null) {
            return result;
        }
        BlockPos requested = new BlockPos(action.x(), action.y(), action.z());
        int tries = 0;
        for (BlockPos spot : WorldProbe.placementCandidates(client.level, client.player.blockPosition())) {
            if (spot.equals(requested)) {
                continue;
            }
            if (tries++ >= Recovery.PLACEMENT_RETRIES) {
                break;
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
     * Builds the starter house with Baritone from a generated schematic, next to the player. The house
     * corner becomes home and is saved for this world.
     */
    /** Repairs only shell cells; never reapplies schematic air over a furnished house. */
    private void repairHouseShell(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        var player = java.util.Objects.requireNonNull(client.player);
        var level = java.util.Objects.requireNonNull(client.level);
        lastAction = ProgressPlanner.BUILD_HOUSE;
        if (!WorldProbe.houseStructure(level, home).loaded()) {
            BlockPos stand = homeCell(HouseDesign.DOOR_STAND);
            executeBaritone(client, connection, "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            lastResult = "Walking home to inspect the unloaded structure";
            return;
        }
        BlockPos target = WorldProbe.houseShellProblem(level, home);
        if (target == null) {
            lastResult = "HOUSE_SHELL_VERIFIED: every wall and roof cell is a solid dry barrier";
            return;
        }
        boolean occupied = player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(target));
        if (occupied || !MiningPolicy.inReach(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                target.getX(), target.getY(), target.getZ())) {
            BlockPos stand = WorldProbe.dryStandNear(level, target,
                    pos -> !pos.equals(target) && !pos.above().equals(target));
            if (stand == null) {
                lastResult = "BUILD_FAILED_UNREACHABLE: no dry repair position for " + target;
                reportBlockResult(client);
                return;
            }
            executeBaritone(client, connection, "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            lastResult = "Walking within reach of missing wall/roof at " + target;
            return;
        }
        var state = level.getBlockState(target);
        if (!state.isAir() && state.getFluidState().isEmpty() && !state.canBeReplaced()) {
            breakForDoor(client, target, "Removing a non-barrier from the house shell");
            return;
        }
        var inventory = WorldProbe.inventory(player);
        String planks = WorldProbe.mostHeldPlanks(inventory, WorldProbe.preferredLog(inventory, ""));
        stopBaritone(client);
        lastResult = placeBlock(client, new ActionPlanner.PlannedAction("place_block", "", planks,
                target.getX(), target.getY(), target.getZ(), ""));
        ActionableLog.LOGGER.info("[Actionable] house_repair at={},{},{} result={}", target.getX(), target.getY(),
                target.getZ(), ActionableLog.text(lastResult));
        reportBlockResult(client);
    }

    /** Close a valid door after moving clear of the doorway; Baritone can still use it for passage. */
    private void tickHouseDoor(Minecraft client) {
        if (!enabled || !autonomous || home == null || client.player == null || client.level == null
                || clientTick % 20 != 0 || pendingCraft != null || pendingSmelt != null || pendingBreak != null
                || pendingHunt != null || eatPhase != 0 || client.gui.screen() != null) return;
        var structure = WorldProbe.houseStructure(client.level, home);
        if (!structure.loaded() || !structure.shellReady() || !structure.doorReady() || structure.doorClosed()) return;
        BlockPos door = homeCell(HouseDesign.DOOR_CELL);
        if (client.player.blockPosition().distSqr(door) <= 2.25
                || client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(door)) > 20.25) return;
        ActionableLog.LOGGER.info("[Actionable] house_close_door {}",
                MenuActions.useBlock(client, door.getX(), door.getY(), door.getZ()));
    }

    private void startHouseBuild(Minecraft client, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        if (home != null) {
            repairHouseShell(client, connection);
            return;
        }
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
        var evidence = pendingBreak.workEvidence(client, result);
        evidence.ifPresent(event -> {
            String step = taskSteps.isEmpty() ? String.valueOf(activeTask) : taskSteps.get(currentStep);
            String objective = step + ProgressPlanner.parse(step)
                    .map(parsed -> "|held=" + TechTree.held(WorldProbe.inventory(client.player), parsed.item())).orElse("");
            if (loopGuard.recordWork(event, objective)) {
                directProgressAt = clientTick;
                directResends = 0;
                ActionableLog.LOGGER.info("[Actionable] VERIFIED_WORK event={} step={}", event, ActionableLog.text(step));
            }
        });
        pendingBreak = null;
        if (!taskSteps.isEmpty() && result.startsWith("BROKE")) {
            ProgressPlanner.parse(taskSteps.get(currentStep)).filter(step -> step.verb().equals("mine"))
                    .ifPresent(step -> startCollect(TechTree.dropOf(TechTree.blockToMine(step.item()))));
        }
        if (furnitureDrop != null) {
            // Step onto the spot so the dropped bed, table or furnace goes back into the inventory.
            BlockPos drop = furnitureDrop;
            furnitureDrop = null;
            executeBaritone(client, connection, "goto " + drop.getX() + " " + drop.getY() + " " + drop.getZ());
            ActionableLog.LOGGER.info("[Actionable] furniture_pickup at={},{},{}", drop.getX(), drop.getY(), drop.getZ());
        }
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

    /** Emergency surfacing overrides tasks and pending model replies before any action tick runs. */
    private boolean tickWater(Minecraft client) {
        var player = client.player;
        boolean rescue = player != null && WaterSafety.rescue(enabled, player.isAlive(), player.isInWater(),
                player.isUnderWater(), player.canBreatheUnderwater(), player.getAirSupply(), player.getMaxAirSupply(), waterRescuing);
        if (rescue && client.level != null && client.getConnection() != null) {
            if (!waterRescuing) {
                waterRescuing = true;
                requests.invalidate();
                defending = false;
                pendingJumpPlace = null;
                pendingHunt = null;
                cancelCraft(client);
                cancelBreak(client);
                cancelHomeWork(client);
                stopBaritone(client);
                player.stopUsingItem();
                eatPhase = 0;
                directStep = null;
                stepActionTaken = false;
                stepJudged = false;
                waterRouteAt = clientTick - 100;
                ActionableLog.LOGGER.info("[Actionable] WATER_RESCUE_STARTED air={} max_air={} task={}",
                        player.getAirSupply(), player.getMaxAirSupply(), ActionableLog.text(activeTask));
            }
            if (clientTick - waterRouteAt >= 100) {
                BlockPos exit = WorldProbe.nearestWaterExit(client.level, player.blockPosition(), 12);
                if (exit != null) {
                    stopBaritone(client);
                    executeBaritone(client, client.getConnection(), "goto " + exit.getX() + " " + exit.getY() + " " + exit.getZ());
                }
                waterRouteAt = clientTick;
            }
            client.options.keyJump.setDown(true);
            holdingJump = true;
            client.options.keyShift.setDown(false);
            if (player.isUnderWater()) {
                player.setSwimming(false);
                var velocity = player.getDeltaMovement();
                player.setDeltaMovement(velocity.x, WaterSafety.upwardVelocity(velocity.y), velocity.z);
            }
            currentGoal = "Surfacing for air";
            return true;
        }
        if (waterRescuing) {
            waterRescuing = false;
            stopBaritone(client);
            loopGuard.reset();
            ticksUntilPlan = 0;
            remember("surface for air", "WATER_RESCUE_ENDED: breathing restored; resume the interrupted task");
        }
        boolean baritoneMoving = player != null && player.isInWater()
                && BaritoneProbe.query().map(BaritoneProbe.State::active).orElse(baritoneCommandRunning);
        boolean hold = enabled && player != null && player.isInWater() && !baritoneMoving && !player.isSleeping();
        if (hold) {
            client.options.keyJump.setDown(true);
            holdingJump = true;
        } else if (holdingJump) {
            client.options.keyJump.setDown(false);
            holdingJump = false;
        }
        return false;
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
        if (level != null && !ItemUseRules.mayUseBed(level.dimension().identifier().toString())) {
            nightRoutine = nightJob = false;
            return;
        }
        if (!enabled || !autonomous || player == null || level == null || client.getConnection() == null
                || clientTick % 20 != 0) {
            return;
        }
        if (home != null) {
            var structure = WorldProbe.houseStructure(level, home);
            if (!structure.loaded()) {
                nightRoutine = nightJob = false;
                return;
            }
            String repair = !structure.shellReady() ? ProgressPlanner.BUILD_HOUSE
                    : !structure.floorReady() || !structure.dryInterior() || !structure.entryClear() || !structure.interiorClear()
                    ? ProgressPlanner.LAY_FLOOR : null;
            if (repair != null) {
                nightRoutine = nightJob = false;
                if (activeTask == null || !taskSteps.contains(repair)) {
                    interruptForSurvival(client);
                    goalReason = "repair the house structure before home jobs";
                    var inventory = WorldProbe.inventory(player);
                    startGoal(client, repair, planFor(client, repair,
                            inventory, WorldProbe.preferredLog(inventory, "")));
                }
                return;
            }
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
            cancelDeepMine(client);
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
        if (!enabled || clientTick % 20 != 0 || clientTick < eatBackoffUntil || defending || pendingCraft != null
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
    private void arrangeHotbar(Minecraft client) {
        var player = client.player;
        if (player == null || client.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return;
        }
        for (var entry : InventoryLayout.plan(WorldProbe.inventory(player)).entrySet()) {
            moveToHotbar(client, entry.getValue(), entry.getKey());
        }
        var worn = new java.util.HashMap<Integer, String>();
        worn.put(EquipRules.HEAD, itemId(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)));
        worn.put(EquipRules.CHEST, itemId(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)));
        worn.put(EquipRules.LEGS, itemId(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS)));
        worn.put(EquipRules.FEET, itemId(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)));
        worn.put(EquipRules.OFFHAND, itemId(player.getOffhandItem()));
        EquipRules.upgrades(WorldProbe.inventory(player), worn, client.level != null
                && client.level.dimension().equals(net.minecraft.world.level.Level.NETHER)).stream().findFirst()
                .ifPresent(item -> ActionableLog.LOGGER.info("[Actionable] auto_equip {}", InventoryAccess.equip(client, item)));
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

    /** Runs the diamond mine; its result goes back to planning like any other finished action. */
    private void tickDeepMine(Minecraft client) {
        if (pendingDeep == null || defending || eatPhase != 0) {
            return;
        }
        var connection = client.getConnection();
        if (!enabled || connection == null) {
            cancelHomeWork(client);
            return;
        }
        String result = pendingDeep.tick(client, clientTick,
                command -> executeBaritone(client, connection, command), () -> stopBaritone(client),
                () -> baritoneBusy(client));
        if (result == null) {
            return;
        }
        pendingDeep = null;
        lastAction = "dig diamond mine";
        lastResult = result;
        reportBlockResult(client);
        remember(lastAction, lastResult);
        ActionableLog.LOGGER.info("[Actionable] diamond_mine {}", ActionableLog.text(result));
        if (!result.startsWith("MINED:")) {
            goalRetries.failed(activeTask, goalRetryContext(client), clientTick);
            abandonTask(client, result);
            return;
        }
        ticksUntilPlan = 1;
    }

    /** Starts the diamond mine for a mine goal on diamonds, unless the player is not fit to go deep. */
    private void startDeepMine(Minecraft client, String requested, int quantity) {
        var level = java.util.Objects.requireNonNull(client.level);
        SafetyAssessment.Result safety = WorldProbe.safety(client);
        if (!level.dimension().identifier().toString().equals("minecraft:overworld")) {
            lastResult = "MINE_FAILED_WRONG_DIMENSION: the diamond mine digs down from the Overworld surface";
        } else if (!safety.caveReady()) {
            lastResult = "MINE_FAILED_NOT_CAVE_READY: the diamond mine goes deep underground; first get "
                    + String.join(", ", safety.missingForCaves());
        } else {
            stopBaritone(client);
            pendingDeep = new DeepMiner(requested, quantity, home, clientTick);
            lastResult = "Starting the " + pendingDeep.describe() + ": stairs down to y=" + DeepMine.TUNNEL_Y
                    + ", then tunnels, scanning for diamond ore every " + DeepMine.SCAN_INTERVAL / 20 + " seconds";
            tell(client, lastResult);
        }
        reportBlockResult(client);
    }

    /** Stops furnace and house-mine work immediately (pause, defense of the task, abandon). */
    private void cancelHomeWork(Minecraft client) {
        cancelSmelt(client);
        if (pendingStairs != null) {
            pendingStairs = null;
            stopBaritone(client);
        }
        cancelDeepMine(client);
        morningPending = false;
    }

    private void cancelDeepMine(Minecraft client) {
        if (pendingDeep != null) {
            pendingDeep.cancel(client);
            pendingDeep = null;
            stopBaritone(client);
        }
    }

    private static net.minecraft.client.multiplayer.ClientPacketListener connection(Minecraft client) {
        return java.util.Objects.requireNonNull(client.getConnection());
    }

    /**
     * Runs a code-started step to completion without the model: waits while Baritone works on it,
     * and starts the step again when Baritone stops short. Hands over to the model only when restarts
     * stop making progress. Returns false to hand over.
     */
    private boolean continueDirectStep(Minecraft client) {
        String step = taskSteps.get(currentStep);
        if (step.equalsIgnoreCase("fill water bucket")) return executeStepDirectly(client);
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
        if (DeepMine.isDiamondTarget(requested) || DeepMine.isDiamondTarget(blockId)) {
            // Diamonds are rarely loaded near the surface: code digs down to them and keeps scanning.
            startDeepMine(client, "minecraft:diamond", Integer.parseInt(parts[1]));
            return;
        }
        SafetyAssessment.Result safety = WorldProbe.safety(client);
        java.util.List<MiningPolicy.Site> sites = WorldProbe.exposedSites(level, player.blockPosition(), block,
                SITE_SCAN_RADIUS);
        if (requested.equals("minecraft:flint") && sites.isEmpty()) {
            stopBaritone(client);
            lastResult = "MINE_FAILED_NO_GRAVEL: find an exposed gravel deposit; ordinary gravel inventory is not flint";
            reportBlockResult(client);
            return;
        }
        if (!sites.isEmpty()) {
            java.util.List<MiningPolicy.Site> safeSites = sites.stream()
                    .filter(site -> dangerMemory.safe(site, clientTick)).toList();
            if (safeSites.isEmpty()) {
                stopBaritone(client);
                lastResult = "MINE_FAILED_RECENT_DANGER: loaded deposits are near a recent threat; choose another resource or goto open ground first";
                reportBlockResult(client);
                return;
            }
            java.util.Optional<MiningPolicy.Site> best = MiningPolicy.choose(safeSites, safety.caveReady(),
                    java.util.List.copyOf(avoidAreas));
            if (best.isEmpty()) {
                lastResult = MiningPolicy.rejection(blockId, sites, safety.missingForCaves());
                reportBlockResult(client);
                return;
            }
            MiningPolicy.Site site = best.get();
            if (dangerMemory.active(clientTick) || requested.equals("minecraft:flint")) {
                // A global mine can pick the dangerous tree even after selecting a safe site.
                // Break the selected block, then inventory completion/retry selects the next one.
                stopBaritone(client);
                BlockBreaker.Start start = BlockBreaker.start(client, site.x(), site.y(), site.z(), clientTick);
                pendingBreak = start.breaker();
                lastResult = pendingBreak == null ? start.result() : "Mining selected safe block " + pendingBreak.describe();
                reportBlockResult(client);
                return;
            }
            // From water, always walk to the dry stand cell first so Baritone starts mining from land.
            if (site.distance() > MiningPolicy.WALK_FIRST_DISTANCE || player.isInWater()) {
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
        if (sites.isEmpty() && dangerMemory.active(clientTick)) {
            stopBaritone(client);
            lastResult = "MINE_FAILED_RECENT_DANGER: no observed safe deposit; goto open ground elsewhere before mining";
            reportBlockResult(client);
            return;
        }
        executeBaritone(client, connection, mine);
        if (!blockId.equals(requested)) {
            lastResult += " (mining " + blockId + ", which drops " + requested + ")";
        }
    }

    /** Starts a deferred mine at its site and recovers mining that gets stuck in water. */
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
                executeBaritone(client, connection, mine);
                remember(mine, lastResult);
            }
            return;
        }
        boolean mining = baritoneCommandRunning && currentBaritoneCommand.startsWith("mine ");
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
     * Pulls the player out of dark underground areas when they lack cave gear. Decided in code, like
     * fleeing, because the model walked into dark caves bare-handed.
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
        if (!safety.retreat() || escaping || defending || HouseDesign.nearHouse(home, x, y, z)
                || (home != null && StairMine.onStairs(x - home[0], y - home[1], z - home[2]))) {
            return;
        }
        avoidAreas.addLast(new int[] {x, y, z});
        while (avoidAreas.size() > AVOID_MEMORY) {
            avoidAreas.removeFirst();
        }
        String interrupted = activeTask;
        if (interrupted != null) {
            noteAttempt(interrupted + " (stopped: dark underground without cave gear at " + x + "," + y + "," + z + ")");
        }
        ActionableLog.LOGGER.info("[Actionable] safety_retreat task={} position={},{},{} reasons={}",
                ActionableLog.text(interrupted), x, y, z, ActionableLog.text(safety.reasons().toString()));
        stopBaritone(client);
        cancelCraft(client);
        retreating = true;
        goalReason = "dark underground without " + String.join(", ", safety.missingForCaves());
        startGoal(client, "retreat from dark area to safety", java.util.List.of(SafetyAssessment.retreatCommand(
                lastSafePosition, x, y, z, WorldProbe.surfaceY(level, x, z))));
    }

    /** Advances as soon as the inventory shows the current step is done, instead of at the next plan. */
    private void tickStepCheck(Minecraft client) {
        if (!enabled || activeTask == null || taskSteps.isEmpty() || requests.planning || requests.judging || requests.decomposing
                || pendingCraft != null || pendingJumpPlace != null || pendingDeep != null || client.player == null
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
                || step.equalsIgnoreCase(ProgressPlanner.GO_TABLE)
                || step.equalsIgnoreCase(ProgressPlanner.LAY_FLOOR)
                || step.equalsIgnoreCase(NeedsPlanner.PLACE_FURNACE)
                || step.equalsIgnoreCase(ProgressPlanner.GATHER_FOOD);
        if ((ProgressPlanner.parse(step).isPresent() || worldStep) && ProgressPlanner.stepSatisfied(step,
                WorldProbe.inventory(client.player), step.equalsIgnoreCase(ProgressPlanner.GO_TABLE) && tableNearby(client),
                facts(client)).orElse(false)) {
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
        requests.invalidate();
        requests.planning = false;
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
            // Baritone echoed every command into chat; with goto/cancel per block that was a flood.
            connection.sendChat("#set echoCommands false");
            baritoneConfigured = true;
        }
        if (baritoneCommandRunning && command.equals(currentBaritoneCommand)) {
            lastAction = command;
            lastResult = "Existing Baritone command left running; " + baritoneProgress(client);
            tell(client, "Keeping Baritone task running: " + command);
            return;
        }
        connection.sendChat("#" + command);
        lastAction = command;
        pendingMine = null;
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

    private void stopBaritone(Minecraft client) {
        pendingMine = null;
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
                if (activeTask != null && !requests.planning) {
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
        int hotbarSlot = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(blockItem)) {
                hotbarSlot = slot;
                break;
            }
        }
        if (hotbarSlot < 0) {
            if (player.containerMenu == player.inventoryMenu) {
                for (int inventorySlot = InventoryMenu.INV_SLOT_START;
                     inventorySlot < InventoryMenu.INV_SLOT_END; inventorySlot++) {
                    ItemStack stack = player.getInventory().getItem(inventorySlot);
                    if (!stack.isEmpty() && stack.is(blockItem)) {
                        int selectedSlot = player.getInventory().getSelectedSlot();
                        // Arguments are (container, slot, button); they were swapped, so the swap never happened.
                        gameMode.handleContainerInput(player.inventoryMenu.containerId, inventorySlot, selectedSlot,
                                ContainerInput.SWAP, player);
                        hotbarSlot = selectedSlot;
                        break;
                    }
                }
            }
            if (hotbarSlot < 0) {
                return "BLOCK_PLACE_FAILED_MATERIAL_MISSING: " + action.block()
                        + " is not in inventory; gather required materials before placing";
            }
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
        String state = (!enabled ? "paused" : waterRescuing ? "surfacing" : escaping ? "escaping" : defending ? "defending" : requests.choosingGoal ? "choosing goal"
                : requests.decomposing ? "decomposing"
                : requests.planning ? "planning" : activeTask == null ? "idle" : "active")
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
        if (message.contains("OLLAMA_RESPONSE_TRUNCATED:")) {
            return "OLLAMA_RESPONSE_TRUNCATED";
        }
        if (message.contains("OLLAMA_RESPONSE_INVALID:")) {
            return "OLLAMA_RESPONSE_INVALID";
        }
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
        ActionableLog.LOGGER.info("[Actionable] {}", ActionableLog.text(message));
        if (client.player != null) {
            client.gui.hud.setOverlayMessage(Component.literal("[Actionable] " + message), false);
        }
    }

    private record JumpPlace(ActionPlanner.PlannedAction action, BlockPos target, long startedAtTick) {
    }
}
