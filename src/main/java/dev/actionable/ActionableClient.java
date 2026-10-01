package dev.actionable;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
    private long generation;
    private int ticksUntilPlan;
    private int ticksSinceAction;
    private String activeTask;
    private String lastAction = "";
    private String currentGoal = "Idle";
    private String lastResult = "No action issued";
    private String baritoneStatus = "No response captured";
    private String errorCode = "none";
    private String lastError = "none";

    @Override
    @SuppressWarnings("null")
    public void onInitializeClient() {
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

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("actionable", "debug_sidebar"),
                (graphics, deltaTracker) -> renderDebugSidebar(Minecraft.getInstance(), graphics)
        );

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            if (text.contains("[Baritone]")) {
                baritoneStatus = compact(text, 140);
                lastResult = "Baritone feedback: " + baritoneStatus;
                if (text.contains("Error") || text.contains("error")
                        || text.contains("Unknown command") || text.contains("insufficient permissions")) {
                    errorCode = "BARITONE_COMMAND_REJECTED";
                    lastError = baritoneStatus;
                    lastResult = "Baritone rejected the last command: " + lastError;
                    if (activeTask != null && !planning) {
                        ticksUntilPlan = 0;
                    }
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ticksSinceAction++;
            while (toggleKey.consumeClick()) {
                boolean controlDown = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
                        || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
                if (controlDown) {
                    toggle(client);
                }
            }
            if (enabled && activeTask != null && !planning && ticksUntilPlan-- <= 0) {
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

        generation++;
        activeTask = prompt;
        lastAction = "";
        currentGoal = "Planning first step";
        lastResult = "Waiting for planner";
        baritoneStatus = "No response captured";
        errorCode = "none";
        lastError = "none";
        planning = false;
        ticksUntilPlan = 0;
        tell(client, "Starting task at " + position(client) + ": " + prompt);
        requestNextPlan(client);
    }

    private void requestNextPlan(Minecraft client) {
        if (client.player == null || client.level == null || client.getConnection() == null || activeTask == null) {
            ticksUntilPlan = 20;
            return;
        }
        long requestGeneration = generation;
        planning = true;
        String synopsis = WorldSynopsis.capture(client);
        planner.plan(activeTask, synopsis, lastAction, lastResult).whenComplete((plan, error) -> client.execute(() -> {
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
                tell(client, "Task complete.");
                connection.sendChat("#cancel");
                lastResult = "Planner marked the task complete";
                currentGoal = "Complete";
                activeTask = null;
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
            if (action.type().equals("place_block")) {
                lastAction = "place " + action.block() + " at " + action.x() + "," + action.y() + "," + action.z();
                lastResult = placeBlock(client, action);
                if (lastResult.startsWith("BLOCK_PLACE_FAILED")) {
                    errorCode = lastResult.substring(0, lastResult.indexOf(':'));
                    lastError = compact(lastResult, 140);
                    tell(client, lastResult);
                } else {
                    tell(client, "Block placement: " + lastResult);
                }
                ticksSinceAction = 0;
            } else {
                String command = action.command();
                if (!command.equals(lastAction) || ticksSinceAction >= 1200) {
                    connection.sendChat("#" + command);
                    tell(client, "Baritone command sent: " + command);
                    lastAction = command;
                    lastResult = "Command sent; awaiting Baritone response";
                    ticksSinceAction = 0;
                }
            }
            ticksUntilPlan = lastResult.startsWith("BLOCK_PLACE_DEFERRED") ? 1 : 200;
        }));
    }

    private void toggle(Minecraft client) {
        enabled = !enabled;
        generation++;
        planning = false;
        tell(client, "Actionable " + (enabled ? "enabled." : "disabled."));
        var connection = client.getConnection();
        if (!enabled && client.player != null && connection != null
                && FabricLoader.getInstance().isModLoaded("baritone")) {
            connection.sendChat("#cancel");
            lastResult = "Baritone cancellation requested";
        }
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
                "State: " + (!enabled ? "paused" : planning ? "planning" : activeTask == null ? "idle" : "active"),
                "Player xyz: " + position(client),
                "Baritone cmd: " + (lastAction == null || lastAction.isBlank() ? "none" : lastAction),
                "Baritone feedback: " + baritoneStatus,
                "LLM goal: " + currentGoal,
                "Result: " + lastResult,
                "Error: " + errorCode + (lastError.equals("none") ? "" : " - " + lastError),
                "/actiondebug toggles this panel");
        java.util.List<String> lines = new java.util.ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            String shortened = client.font.plainSubstrByWidth(line, maxTextWidth);
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
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[Actionable] " + message));
        }
    }
}
