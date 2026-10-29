package dev.actionable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Game-thread half of the Gemma tactical loop. Each tick it snapshots the situation, pushes it to Gemma on a
 * background request (never waiting for the answer), and applies the newest legal tactic through Baritone.
 * When Gemma is slow or sends garbage it falls back to a shield-up hold (FR-10).
 */
final class TacticalAgent {
    interface Baritone {
        void run(String command);

        void cancel();
    }

    enum Source { PENDING, GEMMA, DEFAULT, FALLBACK }

    static final double ENGAGE_RANGE = 32;
    private static final int PUSH_INTERVAL = 10;
    private static final int REISSUE_INTERVAL = 20;
    private static final int SHRIEKER_SCAN_INTERVAL = 20;
    private static final int SHRIEKER_RADIUS = 16;
    private static final long STALE_NANOS = 3_000_000_000L;

    /** One Gemma reply, tagged with the TaskControl generation it was requested in. */
    private record Answer(GemmaClient.Outcome outcome, long at, long latencyMs, long generation) { }

    /**
     * The request generation (TaskControl's). Deadline integration: tactical requests are not owned by
     * TaskControl yet, but a reply from an older generation is never applied, and nothing here changes
     * the generation or planner ownership.
     */
    private final java.util.function.LongSupplier generation;

    TacticalAgent() {
        this(() -> 0L);
    }

    TacticalAgent(java.util.function.LongSupplier generation) {
        this.generation = java.util.Objects.requireNonNull(generation, "generation");
    }

    private final GemmaClient gemma = new GemmaClient();
    private final AtomicReference<Answer> latest = new AtomicReference<>();
    private volatile boolean pending;
    private long lastPush = -PUSH_INTERVAL;
    private long lastIssueTick = -REISSUE_INTERVAL;
    private long lastScan = -SHRIEKER_SCAN_INTERVAL;
    private long lastGearTick;
    private String lastCommand = "";
    private Tactic lastTactic;
    private BlockPos shrieker;
    private Vec3 escapeOrigin;
    private String escapeGoto;
    private boolean leaving;
    private boolean usedKeys;

    private Tactic hudTactic = Tactic.CONTINUE;
    private Source hudSource = Source.PENDING;
    private String hudTarget = "none";
    private String hudBaritone = "idle";
    private long hudLatency = -1;
    private boolean hudActive;
    private volatile boolean tracking;
    private final java.util.List<Long> latencies = new java.util.ArrayList<>();
    private String trackedKey = "";

    /** Nearest tactical mob in range; among raid mobs the evoker/pillager come first (FR-9). */
    static Entity pickTarget(Minecraft client) {
        if (client.player == null || client.level == null) {
            return null;
        }
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        int bestPriority = Integer.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!entity.isAlive() || entity == client.player) {
                continue;
            }
            String id = id(entity);
            if (!TacticBook.isTactical(id) || id.equals("sculk_shrieker")) {
                continue;
            }
            double distance = entity.distanceToSqr(client.player);
            if (distance > ENGAGE_RANGE * ENGAGE_RANGE) {
                continue;
            }
            int priority = TacticBook.raidPriority(id);
            if (priority < bestPriority || (priority == bestPriority && distance < bestDistance)) {
                best = entity;
                bestPriority = priority;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** True while the agent has something to do: a tactical mob, a shrieker, or a deep-dark escape under way. */
    boolean wants(Minecraft client, long tick) {
        var player = client.player;
        if (player == null || client.level == null) {
            return false;
        }
        if (leaving) {
            if (horizontalFromOrigin(player.position()) > TacticBook.DEEP_DARK_ESCAPE - 20) {
                leaving = false;
                escapeOrigin = null;
                escapeGoto = null;
                return false;
            }
            return true;
        }
        if (tick - lastScan >= SHRIEKER_SCAN_INTERVAL) {
            lastScan = tick;
            shrieker = findShrieker(client);
        }
        return pickTarget(client) != null || shrieker != null;
    }

    /** One tick of tactics; returns a short status for the debug sidebar. */
    String tick(Minecraft client, long tick, Baritone baritone) {
        var player = client.player;
        Entity target = pickTarget(client);
        if (target == null && shrieker == null && !leaving) {
            release(client);
            return "TACTICS_IDLE";
        }
        TacticalState state = snapshot(client, target);
        if (tick - lastPush >= PUSH_INTERVAL && !pending) {
            lastPush = tick;
            push(state, generation.getAsLong());
        }
        Tactic tactic = choose(state);
        if (leaving) {
            tactic = Tactic.LEAVE_DEEP_DARK;
        }
        TacticBook.Plan plan = TacticBook.translate(state, tactic);
        if (tactic == Tactic.KITE && target != null && ReachRule.cooldownReady(player.getAttackStrengthScale(0))) {
            plan = TacticBook.translate(state, Tactic.APPROACH);
        }
        if (tactic == Tactic.LEAVE_DEEP_DARK) {
            plan = deepDarkPlan(state, plan);
        }
        hudTactic = tactic;
        hudActive = true;
        hudTarget = target == null ? "sculk_shrieker" : id(target) + " " + Math.round(state.distance()) + "m";
        hudBaritone = state.baritone();

        if (tracking) {
            track(tactic, hudSource, hudTarget, hudLatency);
        }
        applyBaritone(plan, tactic, tick, baritone);
        applyBody(client, plan, tick);

        String status = "TACTIC " + tactic + " source=" + hudSource + " target=" + hudTarget;
        if (target != null && tactic != Tactic.DEFENSIVE_HOLD && MeleeCombat.allowed(target)
                && ReachRule.inReach(ReachRule.distanceToHitbox(player, target))) {
            // Shield down only when the swing is actually ready, so the guard stays up while recharging.
            if (ReachRule.cooldownReady(player.getAttackStrengthScale(0))) {
                client.options.keyUse.setDown(false);
                if (player.isUsingItem()) {
                    return status;
                }
                status = status + " " + MeleeCombat.attack(client, target.getId());
            }
        }
        return status;
    }

    private Tactic choose(TacticalState state) {
        Answer answer = latest.get();
        if (answer == null) {
            hudSource = Source.PENDING;
            return TacticBook.enforce(state, TacticBook.defaultFor(state));
        }
        if (answer.generation() != generation.getAsLong()) {
            // Requested before the task or the engagement changed: stale, so the deterministic default applies.
            hudSource = Source.DEFAULT;
            return TacticBook.enforce(state, TacticBook.defaultFor(state));
        }
        hudLatency = answer.latencyMs();
        if (!answer.outcome().ok()) {
            hudSource = Source.FALLBACK;
            return Tactic.DEFENSIVE_HOLD;
        }
        if (System.nanoTime() - answer.at() > STALE_NANOS) {
            hudSource = Source.DEFAULT;
            return TacticBook.enforce(state, TacticBook.defaultFor(state));
        }
        hudSource = Source.GEMMA;
        return TacticBook.enforce(state, answer.outcome().decision().tactic());
    }

    private void push(TacticalState state, long requestGeneration) {
        if (requestGeneration != generation.getAsLong()) {
            // Preflight: a request whose generation is already stale is never sent.
            return;
        }
        pending = true;
        long started = System.nanoTime();
        gemma.decide(state).whenComplete((outcome, error) -> {
            GemmaClient.Outcome result = error != null || outcome == null
                    ? new GemmaClient.Outcome(null, "client error") : outcome;
            long latency = (System.nanoTime() - started) / 1_000_000;
            latest.set(new Answer(result, System.nanoTime(), latency, requestGeneration));
            if (result.ok()) {
                recordLatency(latency);
            } else {
                recordLatency(Long.MAX_VALUE / 4);
            }
            pending = false;
        });
    }

    private TacticBook.Plan deepDarkPlan(TacticalState state, TacticBook.Plan plan) {
        if (!leaving) {
            leaving = true;
            Vec3 here = new Vec3(state.px(), state.py(), state.pz());
            escapeOrigin = here;
            double dx = here.x - state.mx();
            double dz = here.z - state.mz();
            if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
                dx = 1;
            }
            double length = Math.max(1e-6, Math.hypot(dx, dz));
            escapeGoto = "goto " + Math.round(here.x + dx / length * TacticBook.DEEP_DARK_ESCAPE) + " "
                    + Math.round(here.y) + " " + Math.round(here.z + dz / length * TacticBook.DEEP_DARK_ESCAPE);
        }
        return new TacticBook.Plan(Tactic.LEAVE_DEEP_DARK, List.of(escapeGoto), false, true, false, false, false);
    }

    private double horizontalFromOrigin(Vec3 position) {
        return escapeOrigin == null ? 0 : Math.hypot(position.x - escapeOrigin.x, position.z - escapeOrigin.z);
    }

    private void applyBaritone(TacticBook.Plan plan, Tactic tactic, long tick, Baritone baritone) {
        boolean changed = tactic != lastTactic;
        lastTactic = tactic;
        if (plan.cancelPathing()) {
            if (changed) {
                baritone.cancel();
                lastCommand = "";
            }
            return;
        }
        if (plan.baritone().isEmpty()) {
            return;
        }
        String command = plan.baritone().get(0);
        if (command.equals(lastCommand) || (!changed && tick - lastIssueTick < REISSUE_INTERVAL)) {
            return;
        }
        lastCommand = command;
        lastIssueTick = tick;
        baritone.run(command);
    }

    private void applyBody(Minecraft client, TacticBook.Plan plan, long tick) {
        var player = client.player;
        usedKeys = true;
        if (tick - lastGearTick >= 20) {
            lastGearTick = tick;
            if (plan.raiseShield() || plan.tactic() == Tactic.DEFENSIVE_HOLD) {
                equipShield(client);
            }
            if (plan.equipGold()) {
                equipGold(client);
            }
        }
        boolean shield = plan.raiseShield() && player.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD);
        client.options.keyUse.setDown(shield);
        client.options.keyShift.setDown(plan.sneak());
        if (plan.sprint()) {
            client.options.keySprint.setDown(true);
            client.options.keyUp.setDown(true);
        } else {
            client.options.keySprint.setDown(false);
        }
        if (plan.tactic() == Tactic.RUSH) {
            Entity target = pickTarget(client);
            if (target != null) {
                player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
            }
        } else {
            client.options.keyUp.setDown(false);
        }
    }

    TacticalState snapshot(Minecraft client, Entity target) {
        var player = client.player;
        List<String> hotbar = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                hotbar.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        Vec3 mob = target != null ? target.position()
                : shrieker != null ? Vec3.atCenterOf(shrieker) : player.position();
        String mobId = target != null ? id(target) : "sculk_shrieker";
        boolean charging = false;
        boolean drinking = false;
        boolean shooting = false;
        if (target != null) {
            drinking = target instanceof Witch witch && witch.isDrinkingPotion();
            shooting = target instanceof LivingEntity living && living.isUsingItem();
            charging = switch (mobId) {
                case "warden" -> target.getPose().name().equals("SONIC_BOOM");
                case "hoglin", "zoglin" -> target.getDeltaMovement().horizontalDistanceSqr() > 0.04
                        && target.distanceTo(player) < 10;
                default -> false;
            };
            if (mobId.equals("shulker")) {
                shooting = bulletNearby(client);
            }
        }
        boolean shield = player.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD) || findShield(client) >= 0;
        double distance = target != null ? player.distanceTo(target) : player.position().distanceTo(mob);
        String baritone = BaritoneProbe.query().map(BaritoneProbe.State::describe).orElse("unknown");
        return new TacticalState(player.getHealth(), hotbar, mobId, distance, baritone,
                player.getX(), player.getY(), player.getZ(), mob.x, mob.y, mob.z,
                charging, drinking, shooting, shield, wearingGold(client), waterNearby(client),
                shrieker != null, coverBlock(client, mob));
    }

    /** A solid, tall-enough block close to the player that sits between the player and the threat's side. */
    private static int[] coverBlock(Minecraft client, Vec3 mob) {
        var player = client.player;
        BlockPos here = player.blockPosition();
        double dx = mob.x - player.getX();
        double dz = mob.z - player.getZ();
        double length = Math.max(1e-6, Math.hypot(dx, dz));
        for (int step = 1; step <= 4; step++) {
            BlockPos probe = BlockPos.containing(player.getX() + dx / length * step, here.getY(),
                    player.getZ() + dz / length * step);
            if (!client.level.getBlockState(probe).isAir() && !client.level.getBlockState(probe.above()).isAir()) {
                return new int[] {probe.getX(), probe.getY(), probe.getZ()};
            }
        }
        return null;
    }

    private static boolean waterNearby(Minecraft client) {
        BlockPos here = client.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(here.offset(-8, -2, -8), here.offset(8, 2, 8))) {
            if (client.level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean bulletNearby(Minecraft client) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (id(entity).equals("shulker_bullet") && entity.distanceToSqr(client.player) < 24 * 24) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findShrieker(Minecraft client) {
        BlockPos here = client.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(here.offset(-SHRIEKER_RADIUS, -6, -SHRIEKER_RADIUS),
                here.offset(SHRIEKER_RADIUS, 6, SHRIEKER_RADIUS))) {
            if (client.level.getBlockState(pos).is(Blocks.SCULK_SHRIEKER)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static boolean wearingGold(Minecraft client) {
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (isGold(client.player.getItemBySlot(slot))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGold(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.startsWith("minecraft:golden_") && (id.endsWith("_boots") || id.endsWith("_helmet")
                || id.endsWith("_chestplate") || id.endsWith("_leggings"));
    }

    /** Swaps a gold piece (boots first) from the inventory onto the body with a shift-click. */
    private static void equipGold(Minecraft client) {
        var player = client.player;
        if (wearingGold(client) || client.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return;
        }
        int best = -1;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isGold(stack) && (best < 0 || stack.is(net.minecraft.world.item.Items.GOLDEN_BOOTS))) {
                best = slot;
                if (stack.is(net.minecraft.world.item.Items.GOLDEN_BOOTS)) {
                    break;
                }
            }
        }
        if (best >= 0) {
            client.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot(best), 0,
                    ContainerInput.QUICK_MOVE, player);
        }
    }

    private static void equipShield(Minecraft client) {
        var player = client.player;
        if (player.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD) || client.gameMode == null
                || player.containerMenu != player.inventoryMenu) {
            return;
        }
        int slot = findShield(client);
        if (slot >= 0) {
            client.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot(slot), 40,
                    ContainerInput.SWAP, player);
        }
    }

    private static int findShield(Minecraft client) {
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).is(net.minecraft.world.item.Items.SHIELD)) {
                return slot;
            }
        }
        return -1;
    }

    /** Player-inventory index to inventory-menu slot: the hotbar sits after the main grid in the menu. */
    private static int menuSlot(int inventoryIndex) {
        return inventoryIndex < 9 ? InventoryMenu.USE_ROW_SLOT_START + inventoryIndex : inventoryIndex;
    }

    /** Runtime tracking: logs tactic changes and a latency summary (p95 against the 100 ms budget) to gemma_agent.log. */
    void setTracking(boolean on) {
        if (tracking && !on) {
            logLatencySummary();
        }
        tracking = on;
        trackedKey = "";
        GemmaLog.info("tactic_tracking " + (on ? "on" : "off"));
    }

    private void track(Tactic tactic, Source source, String target, long latency) {
        String key = tactic + "/" + source + "/" + target.split(" ")[0];
        if (!key.equals(trackedKey)) {
            trackedKey = key;
            GemmaLog.info("tactic tactic=" + tactic + " source=" + source + " target=" + ActionableLog.text(target)
                    + " latency_ms=" + latency + " baritone=" + ActionableLog.text(hudBaritone));
        }
    }

    private synchronized void recordLatency(long ms) {
        if (tracking) {
            latencies.add(ms);
            if (latencies.size() >= 50) {
                logLatencySummary();
            }
        }
    }

    private synchronized void logLatencySummary() {
        if (latencies.isEmpty()) {
            return;
        }
        var sorted = new ArrayList<>(latencies);
        java.util.Collections.sort(sorted);
        long p95 = sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(sorted.size() * 0.95) - 1));
        GemmaLog.info("latency_summary samples=" + sorted.size() + " p95_ms=" + p95
                + " budget_ms=" + GemmaClient.TIMEOUT_MS + " " + (p95 <= GemmaClient.TIMEOUT_MS ? "PASS" : "FAIL"));
        latencies.clear();
    }

    /** Lets go of every key the tactics held. */
    void release(Minecraft client) {
        hudActive = false;
        lastTactic = null;
        lastCommand = "";
        if (usedKeys) {
            usedKeys = false;
            client.options.keyUse.setDown(false);
            client.options.keyShift.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyUp.setDown(false);
        }
    }

    List<String> hudLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Gemma agent [/actiondebug combat]");
        lines.add("Tactic: " + (hudActive ? hudTactic + " (" + hudSource.name().toLowerCase(java.util.Locale.ROOT)
                + ")" : "idle"));
        lines.add("Target: " + (hudActive ? hudTarget : "none"));
        lines.add("Baritone path: " + (hudActive ? hudBaritone : "idle"));
        lines.add("Ollama: " + (hudLatency < 0 ? "no reply yet" : hudLatency + " ms") + (pending ? " (waiting)" : ""));
        return lines;
    }

    private static String id(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }
}
