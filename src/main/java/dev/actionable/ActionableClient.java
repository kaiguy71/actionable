package dev.actionable;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class ActionableClient implements ClientModInitializer {
    private final ActionPlanner planner = new ActionPlanner();
    private KeyMapping toggleKey;
    private boolean enabled = true;
    private boolean planning;
    private long generation;
    private int ticksUntilPlan;
    private int ticksSinceAction;
    private String activeTask;
    private String lastAction;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(
                        "key.actionable.toggle",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_GRAVE_ACCENT,
                        KeyMapping.Category.MISC
                )
        );
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("action")
                        .then(ClientCommandManager.argument("prompt", StringArgumentType.greedyString())
                                .executes(context -> {
                                    submit(context.getArgument("prompt", String.class));
                                    return 1;
                                }))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ticksSinceAction++;
            while (toggleKey.consumeClick()) {
                long window = client.getWindow().getWindow();
                boolean controlDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                        || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
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
        if (!FabricLoader.getInstance().isModLoaded("baritone")) {
            tell(client, "Baritone is required to execute actions; install the Minecraft 26.3 Baritone mod.");
            return;
        }

        generation++;
        activeTask = prompt;
        lastAction = "";
        planning = false;
        ticksUntilPlan = 0;
        tell(client, "Starting task: " + prompt);
        requestNextPlan(client);
    }

    private void requestNextPlan(Minecraft client) {
        if (client.player == null || client.level == null || activeTask == null) {
            ticksUntilPlan = 20;
            return;
        }
        long requestGeneration = generation;
        planning = true;
        String synopsis = WorldSynopsis.capture(client);
        planner.plan(activeTask, synopsis, lastAction).whenComplete((plan, error) -> client.execute(() -> {
            if (requestGeneration != generation) {
                return;
            }
            planning = false;
            if (!enabled || client.player == null) {
                return;
            }
            if (error != null) {
                tell(client, "Planning failed: " + ActionPlanner.safeMessage(error));
                ticksUntilPlan = 200;
                return;
            }
            tell(client, "Plan: " + plan.summary());
            if (plan.complete()) {
                tell(client, "Task complete.");
                client.getConnection().sendChat("#cancel");
                activeTask = null;
                return;
            }
            if (plan.actions().isEmpty()) {
                ticksUntilPlan = 200;
                return;
            }

            String command = plan.actions().getFirst();
            if (!command.equals(lastAction) || ticksSinceAction >= 1200) {
                client.getConnection().sendChat("#" + command);
                tell(client, "Baritone: " + command);
                lastAction = command;
                ticksSinceAction = 0;
            }
            ticksUntilPlan = 200;
        }));
    }

    private void toggle(Minecraft client) {
        enabled = !enabled;
        generation++;
        planning = false;
        tell(client, "Actionable " + (enabled ? "enabled." : "disabled."));
        if (!enabled && client.player != null && FabricLoader.getInstance().isModLoaded("baritone")) {
            client.getConnection().sendChat("#cancel");
        }
    }

    private static void tell(Minecraft client, String message) {
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal("[Actionable] " + message), false);
        }
    }
}
