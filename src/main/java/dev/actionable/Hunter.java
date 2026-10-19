package dev.actionable;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;

/**
 * Hunts sheep until the inventory holds enough of an item (white wool). Walks to the nearest white
 * sheep inside the home range, hits it, then walks over the drops to collect them.
 */
final class Hunter {
    private static final int SEARCH_RADIUS = 48;
    private static final int LOOT_RADIUS = 12;
    private static final int TIMEOUT_TICKS = 2400;
    private static final int REPATH_TICKS = 40;

    private final String item;
    private final int target;
    private final long startedAt;
    private BlockPos lastGoto;
    private long lastGotoAt = -REPATH_TICKS;
    private int kills;
    private int lastTargetId = -1;

    Hunter(String item, int target, long tick) {
        this.item = item;
        this.target = target;
        this.startedAt = tick;
    }

    String item() {
        return item;
    }

    /**
     * Advances the hunt. {@code walk} sends a Baritone goto; {@code stop} cancels movement before a
     * hit. Returns null while hunting, otherwise the final result.
     */
    String tick(Minecraft client, long tick, int[] home, boolean geared, Consumer<String> walk, Runnable stop) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return "HUNT_FAILED_NO_WORLD: player world is unavailable";
        }
        int held = TechTree.held(WorldProbe.inventory(player), item);
        if (held >= target) {
            stop.run();
            return "HUNTED: now holding " + held + " " + item + " after " + kills + " kill(s)";
        }
        if (tick - startedAt > TIMEOUT_TICKS) {
            stop.run();
            return "HUNT_FAILED_TIMEOUT: holding " + held + "/" + target + " " + item + " after 2 minutes";
        }
        if (tick % 5 != 0) {
            return null;
        }
        Entity lastTarget = lastTargetId < 0 ? null : client.level.getEntity(lastTargetId);
        if (lastTargetId >= 0 && (lastTarget == null || !lastTarget.isAlive())) {
            kills++;
            lastTargetId = -1;
        }
        // Pick up drops first; they despawn and are why the sheep was killed.
        ItemEntity loot = null;
        double lootDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ItemEntity drop && drop.isAlive()
                    && BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString().equals(item)) {
                double distance = drop.distanceTo(player);
                if (distance <= LOOT_RADIUS && distance < lootDistance) {
                    loot = drop;
                    lootDistance = distance;
                }
            }
        }
        if (loot != null) {
            walkTo(loot.blockPosition(), tick, walk);
            return null;
        }
        Sheep sheep = nearestSheep(client, player, home, geared);
        if (sheep == null) {
            stop.run();
            return "HUNT_FAILED_NO_SHEEP: no adult white sheep within " + SEARCH_RADIUS + " blocks"
                    + (home != null && !geared ? " inside the home range" : "")
                    + "; holding " + held + "/" + target + " " + item + ". Wander near home and try again later.";
        }
        if (player.isWithinEntityInteractionRange(sheep, 0) && player.hasLineOfSight(sheep)) {
            stop.run();
            lastGoto = null;
            lastTargetId = sheep.getId();
            MeleeCombat.hitAnimal(client, sheep);
            return null;
        }
        walkTo(sheep.blockPosition(), tick, walk);
        return null;
    }

    private void walkTo(BlockPos pos, long tick, Consumer<String> walk) {
        boolean moved = lastGoto == null || lastGoto.distSqr(pos) > 4;
        if (moved || tick - lastGotoAt > REPATH_TICKS) {
            lastGoto = pos;
            lastGotoAt = tick;
            walk.accept("goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
    }

    private Sheep nearestSheep(Minecraft client, LocalPlayer player, int[] home, boolean geared) {
        Sheep best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : java.util.Objects.requireNonNull(client.level).entitiesForRendering()) {
            if (!(entity instanceof Sheep sheep) || !sheep.isAlive() || sheep.isBaby()
                    || (item.equals(TechTree.WHITE_WOOL) && (sheep.getColor() != DyeColor.WHITE || sheep.isSheared()))) {
                continue;
            }
            double distance = sheep.distanceTo(player);
            if (distance > SEARCH_RADIUS || HomeRange.outside(home, geared, sheep.getBlockX(), sheep.getBlockZ(), 0)) {
                continue;
            }
            if (distance < bestDistance) {
                best = sheep;
                bestDistance = distance;
            }
        }
        return best;
    }
}
