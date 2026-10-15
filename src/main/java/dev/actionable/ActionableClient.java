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
    private KeyMapping toggleKey;
    private boolean enabled = true;
    private boolean debugEnabled;
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
    private static final int MAX_TASK_TICKS = 6000;
    private static final int GOAL_MEMORY = 20;
    private boolean autonomous = true;
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
    private Crafter pendingCraft;
    private boolean retreating;
    private boolean stepActionTaken;
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
                                    submit(context.getArgument("prompt", String.class));
                                    return 1;
                                }))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("actiondebug")
                        .executes(context -> {
                            debugEnabled = !debugEnabled;
                            tell(Minecraft.getInstance(), "Debug sidebar " + (debugEnabled ? "enabled." : "disabled.")
                                    + " (use /actiondebug to toggle).");
                            return 1;
                        })));

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

        ClientLifecycleEvents.CLIENT_STOPPING.register(Screenshots::archiveSession);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Screenshots.archiveSession(client));

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
            tickJumpPlace(client);
            tickCraft(client);
            tickMining(client);
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
            if (enabled && activeTask != null && clientTick - taskStartedAtTick > MAX_TASK_TICKS) {
                abandonTask(client, "took too long");
            }
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
            if (enabled && autonomous && activeTask == null && !choosingGoal && !decomposing
                    && client.player != null && client.level != null && ticksUntilGoal-- <= 0) {
                requestGoalSelection(client);
            }
            if (enabled && activeTask != null && !planning && !decomposing && !judging
                    && pendingJumpPlace == null && pendingCraft == null && ticksUntilPlan-- <= 0) {
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
        goalReason = "requested with /action";
        taskStartedAtTick = clientTick;
        history.clear();
        noteAttempt(prompt);
        tell(client, "Planning steps for: " + prompt);
        requestDecomposition(client);
    }

    private void requestGoalSelection(Minecraft client) {
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
                    // Goals that need a missing tool are replaced in code: the model chose to punch stone.
                    java.util.Optional<String> problem = chosen.isEmpty() ? java.util.Optional.empty()
                            : ProgressPlanner.goalProblem(chosen, WorldProbe.inventory(client.player));
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
                            chosen = "explore to find new resources";
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
        return ProgressPlanner.nextMilestone(WorldProbe.inventory(client.player))
                .filter(next -> attemptedGoals.stream()
                        .filter(goal -> goalKey(goal).equals(goalKey(next.goal()))).count() < 2);
    }

    private boolean tableNearby(Minecraft client) {
        if (client.player == null || client.level == null) {
            return false;
        }
        BlockPos table = WorldProbe.nearestCraftingTable(client.level, client.player.blockPosition());
        return table != null && table.distSqr(client.player.blockPosition()) <= 64;
    }

    private String briefing(Minecraft client, String synopsis) {
        var inventory = WorldProbe.inventory(java.util.Objects.requireNonNull(client.player));
        return ProgressPlanner.briefing(inventory, tableNearby(client),
                WorldProbe.preferredLog(inventory, synopsis));
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
        activeTask = goal;
        taskSteps = java.util.List.copyOf(steps);
        currentStep = 0;
        stepJudged = false;
        stepActionTaken = false;
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
        taskSteps = java.util.List.of();
        currentStep = 0;
        stepJudged = false;
        stepActionTaken = false;
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
        java.util.List<String> known = ProgressPlanner.stepsForGoal(activeTask, inventory, tableNearby(client),
                WorldProbe.preferredLog(inventory, synopsis));
        if (!known.isEmpty() && known.size() <= ActionPlanner.MAX_STEPS) {
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
        stopBaritone(client);
        currentStep++;
        stepJudged = false;
        stepActionTaken = false;
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
        tell(client, "Task complete.");
        connection.sendChat("#cancel");
        baritoneCommandRunning = false;
        currentBaritoneCommand = "";
        lastResult = reason;
        currentGoal = "Complete";
        noteAchieved(activeTask);
        escaping = false;
        retreating = false;
        mineStart = null;
        pendingMine = null;
        activeTask = null;
        taskSteps = java.util.List.of();
        ticksUntilGoal = 60;
    }

    private void requestNextPlan(Minecraft client) {
        if (client.player == null || client.level == null || client.getConnection() == null || activeTask == null) {
            ticksUntilPlan = 20;
            return;
        }
        if (!taskSteps.isEmpty()) {
            // Quantity steps are judged from the inventory, without a model call.
            String step = taskSteps.get(currentStep);
            java.util.Optional<Boolean> done = ProgressPlanner.stepSatisfied(step,
                    WorldProbe.inventory(client.player), tableNearby(client));
            if (done.orElse(false)) {
                advanceStep(client, step);
                return;
            }
            if (done.isPresent()) {
                stepJudged = true;
            }
        }
        if (!taskSteps.isEmpty() && !history.isEmpty() && !stepJudged) {
            requestStepCheck(client);
            return;
        }
        if (!taskSteps.isEmpty() && !stepActionTaken && executeStepDirectly(client)) {
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
            if (plan.stepComplete() && !taskSteps.isEmpty()) {
                advanceStep(client, taskSteps.get(currentStep));
                return;
            }
            if (plan.action().isEmpty()) {
                lastResult = "No valid action returned. " + plan.summary();
                errorCode = "PLAN_NO_VALID_ACTION";
                lastError = compact(plan.summary(), 140);
                tell(client, "No valid action [" + errorCode + "]: " + lastError);
                ticksUntilPlan = 200;
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
                case "craft" -> startCraft(client, action.block(), Integer.parseInt(action.command()));
                case "lookup" -> {
                    lastAction = "lookup " + action.query();
                    lastResult = GameKnowledge.lookup(client, action.query());
                    tell(client, lastResult);
                    ticksUntilPlan = 1;
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
                    lastAction = "place " + action.block() + " at " + action.x() + "," + action.y() + "," + action.z();
                    lastResult = placeBlock(client, action);
                    reportBlockResult(client);
                }
                case "jump_place" -> {
                    stopBaritone(client);
                    lastAction = "jump-place " + action.block() + " at " + action.x() + "," + action.y() + "," + action.z();
                    lastResult = startJumpPlace(client, action);
                    reportBlockResult(client);
                }
                case "baritone" -> {
                    if (action.command().startsWith("mine ")) {
                        startMine(client, connection, action.command());
                    } else {
                        executeBaritone(client, connection, action.command());
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
            if (action.type().equals("attack")) {
                ticksUntilPlan = 20;
            } else if (action.type().equals("craft") || lastResult.startsWith("MINE_FAILED")) {
                ticksUntilPlan = 20;
            } else if (action.type().equals("place_block")) {
                ticksUntilPlan = lastResult.startsWith("BLOCK_PLACE_DEFERRED") ? 1 : 200;
            } else if (action.type().equals("jump_place") && pendingJumpPlace == null) {
                ticksUntilPlan = lastResult.startsWith("BLOCK_PLACE_DEFERRED") ? 1 : 200;
            } else if (!action.type().equals("lookup")) {
                ticksUntilPlan = 200;
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
        java.util.Optional<ProgressPlanner.QuantifiedStep> parsed = ProgressPlanner.parse(step);
        if (parsed.isPresent() && parsed.get().verb().equals("craft")) {
            stepActionTaken = true;
            startCraft(client, parsed.get().item(), parsed.get().count());
            ticksUntilPlan = 20;
        } else if (parsed.isPresent() && parsed.get().verb().equals("mine")
                && ActionPlanner.validateCommand("mine " + parsed.get().count() + " " + parsed.get().item()).isPresent()) {
            stepActionTaken = true;
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
            lastResult = placeBlock(client, new ActionPlanner.PlannedAction("place_block", "", TechTree.CRAFTING_TABLE,
                    spot.getX(), spot.getY(), spot.getZ(), ""));
            reportBlockResult(client);
            // A cleared plant only prepares the spot, so the direct attempt is not used up yet.
            stepActionTaken = !lastResult.startsWith("BLOCK_PLACE_DEFERRED");
            ticksUntilPlan = stepActionTaken ? 10 : 1;
        } else {
            return false;
        }
        currentGoal = step;
        remember(lastAction, lastResult);
        return true;
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
        if (TechTree.recipeFor(requested).isPresent()) {
            lastResult = "MINE_FAILED_CRAFTED_ITEM: " + requested + " is crafted, not mined; use a craft action";
            reportBlockResult(client);
            return;
        }
        String blockId = TechTree.blockToMine(requested);
        String mine = "mine " + parts[1] + " " + blockId;
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
        java.util.List<MiningPolicy.Site> sites = WorldProbe.exposedSites(level, player.blockPosition(), block,
                SITE_SCAN_RADIUS);
        if (!sites.isEmpty()) {
            java.util.Optional<MiningPolicy.Site> best = MiningPolicy.choose(sites, safety.caveReady(),
                    java.util.List.copyOf(avoidAreas));
            if (best.isEmpty()) {
                lastResult = MiningPolicy.rejection(blockId, sites, safety.missingForCaves());
                reportBlockResult(client);
                return;
            }
            MiningPolicy.Site site = best.get();
            if (site.distance() > MiningPolicy.WALK_FIRST_DISTANCE) {
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
                executeBaritone(client, connection, mine);
                mineStart = new int[] {player.getBlockX(), player.getBlockY(), player.getBlockZ()};
                remember(mine, lastResult);
            }
            return;
        }
        if (mineStart != null && baritoneCommandRunning && currentBaritoneCommand.startsWith("mine ")
                && MiningPolicy.strayed(mineStart, player.getBlockX(), player.getBlockY(), player.getBlockZ())) {
            String command = currentBaritoneCommand;
            stopBaritone(client);
            lastAction = command;
            lastResult = "MINE_STOPPED_STRAYED: Baritone went more than " + MiningPolicy.LEASH_DISTANCE
                    + " blocks from where mining started; choose a closer exposed deposit or explore the surface";
            errorCode = "MINE_STOPPED_STRAYED";
            lastError = lastResult;
            ActionableLog.LOGGER.info("[Actionable] mine_leash command={}", ActionableLog.text(command));
            remember(lastAction, lastResult);
            tell(client, lastResult);
            ticksUntilPlan = 0;
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
        if (!safety.retreat() || escaping || defending) {
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
        if (!enabled || activeTask == null || taskSteps.isEmpty() || planning || judging || decomposing
                || pendingCraft != null || pendingJumpPlace != null || client.player == null
                || clientTick % 20 != 0) {
            return;
        }
        String step = taskSteps.get(currentStep);
        // Only quantity steps: a table placed earlier would otherwise skip a fresh placement step.
        if (ProgressPlanner.parse(step).isPresent()
                && ProgressPlanner.stepSatisfied(step, WorldProbe.inventory(client.player), false).orElse(false)) {
            advanceStep(client, step);
        }
    }

    /** Remaining distance to the running goto target, or -1 when no goto is running. */
    private double gotoDistance(Minecraft client) {
        if (!baritoneCommandRunning || !currentBaritoneCommand.startsWith("goto ") || client.player == null) {
            return -1;
        }
        String[] parts = currentBaritoneCommand.split("\\s+");
        if (parts.length != 4) {
            return -1;
        }
        BlockPos current = client.player.blockPosition();
        return Math.sqrt(Math.pow(Integer.parseInt(parts[1]) - current.getX(), 2)
                + Math.pow(Integer.parseInt(parts[2]) - current.getY(), 2)
                + Math.pow(Integer.parseInt(parts[3]) - current.getZ(), 2));
    }

    private void toggle(Minecraft client) {
        enabled = !enabled;
        if (!enabled) {
            history.clear();
        }
        generation++;
        planning = false;
        cancelCraft(client);
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
        if (baritoneCommandRunning && command.equals(currentBaritoneCommand)) {
            lastAction = command;
            lastResult = "Existing Baritone command left running; " + baritoneProgress(client);
            tell(client, "Keeping Baritone task running: " + command);
            return;
        }
        connection.sendChat("#" + command);
        lastAction = command;
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

    private void stopBaritone(Minecraft client) {
        pendingMine = null;
        mineStart = null;
        if (!baritoneCommandRunning) {
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
            String[] parts = currentBaritoneCommand.split("\\s+");
            if (parts.length == 4) {
                try {
                    double distance = Math.sqrt(
                            Math.pow(Integer.parseInt(parts[1]) - current.getX(), 2)
                                    + Math.pow(Integer.parseInt(parts[2]) - current.getY(), 2)
                                    + Math.pow(Integer.parseInt(parts[3]) - current.getZ(), 2));
                    progress.append(",distance_to_goto_goal=")
                            .append(String.format(java.util.Locale.ROOT, "%.1f", distance));
                } catch (NumberFormatException ignored) {
                    progress.append(",distance_to_goto_goal=unknown");
                }
            }
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
        if (!targetState.isAir()) {
            if (targetState.canBeReplaced() && gameMode.destroyBlock(target)) {
                return "BLOCK_PLACE_DEFERRED_TARGET_CLEARED: removed replaceable obstruction at "
                        + target + "; recheck the location and place next";
            }
            return "BLOCK_PLACE_FAILED_TARGET_OCCUPIED: target " + target + " contains "
                    + BuiltInRegistries.BLOCK.getKey(targetState.getBlock());
        }
        BlockPos headroom = target.above();
        var headroomState = level.getBlockState(headroom);
        if (!headroomState.isAir()) {
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
                        gameMode.handleContainerInput(inventorySlot, selectedSlot,
                                player.inventoryMenu.containerId, ContainerInput.SWAP, player);
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
        java.util.List<String> rawLines = java.util.List.of(
                "Actionable DEBUG",
                "State: " + (!enabled ? "paused" : defending ? "defending" : choosingGoal ? "choosing goal"
                        : decomposing ? "decomposing"
                        : planning ? "planning" : activeTask == null ? "idle" : "active")
                        + (autonomous ? " [auto]" : " [manual]"),
                "Task: " + (activeTask == null ? "none" : activeTask),
                "Achieved: " + (achievedGoals.isEmpty() ? "none" : joined(achievedGoals)),
                "Step: " + (taskSteps.isEmpty() ? "no plan"
                        : (currentStep + 1) + "/" + taskSteps.size() + " " + taskSteps.get(currentStep)),
                "Player xyz: " + position(client),
                "Baritone cmd: " + (currentBaritoneCommand.isBlank() ? "none" : currentBaritoneCommand),
                "Baritone: " + compact(baritoneProgress(client), 100),
                "Baritone feedback: " + baritoneStatus,
                "LLM goal: " + currentGoal,
                "Why: " + (goalReason.isBlank() ? "none" : goalReason),
                "Safety: " + safetyStatus,
                "Result: " + lastResult,
                "Error: " + errorCode + (lastError.equals("none") ? "" : " - " + lastError),
                "/actionauto toggles autonomy");
        java.util.List<String> lines = new java.util.ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            String shortened = client.font.plainSubstrByWidth(java.util.Objects.requireNonNull(line), maxTextWidth);
            lines.add(shortened == null ? "" : shortened);
        }
        int lineHeight = client.font.lineHeight + 2;
        int panelHeight = lines.size() * lineHeight + 8;
        int left = graphics.guiWidth() - DEBUG_PANEL_WIDTH - 6;
        graphics.fill(left, 6, left + DEBUG_PANEL_WIDTH, 6 + panelHeight, 0xA0000000);
        for (int index = 0; index < lines.size(); index++) {
            graphics.text(client.font, lines.get(index), left + 6, 10 + index * lineHeight,
                    index == 0 ? 0xFFFFD34E : 0xFFFFFFFF);
        }
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
        ActionableLog.LOGGER.info("[Actionable] {}", ActionableLog.text(message));
        if (client.player != null) {
            client.gui.hud.setOverlayMessage(Component.literal("[Actionable] " + message), false);
        }
    }

    private record JumpPlace(ActionPlanner.PlannedAction action, BlockPos target, long startedAtTick) {
    }
}
