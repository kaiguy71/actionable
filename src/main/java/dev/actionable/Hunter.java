package dev.actionable;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;

/**
 * Hunts until the inventory holds enough of an item (white wool), searching by itself. It scans every
 * loaded animal several times a second, including while walking. With no sheep loaded it goes on
 * expeditions: far out from home in one compass direction, then the next, then farther, because
 * everything near home is already loaded and searching there finds nothing new. Food animals are
 * killed on the way when food is low, and any of them when the player is starving.
 */
final class Hunter {
    private static final int TIMEOUT_TICKS = 18000;
    private static final int REPATH_TICKS = 40;
    private static final int LOOT_RADIUS = 12;
    private static final int FOOD_DETOUR = 24;
    private static final int SIGHTING_TICKS = 6000;
    /** An expedition leg that makes no progress for this long is given up for the next direction. */
    private static final int LEG_STALL_TICKS = 400;

    /** Where a white sheep was last seen, kept across hunts so searching starts there. */
    private static BlockPos lastSheepSeen;
    private static long lastSheepSeenAt;
    /** Expedition progress, kept across hunts so the next hunt tries new ground. */
    private static int expedition;

    private final String item;
    private final int target;
    private final long startedAt;
    private BlockPos lastGoto;
    private long lastGotoAt = -REPATH_TICKS;
    private int kills;
    private int foodKills;
    private int lastTargetId = -1;
    private boolean lastTargetFood;
    private int[] leg;
    private double legBest = Double.MAX_VALUE;
    private long legProgressAt;

    Hunter(String item, int target, long tick) {
        this.item = item;
        this.target = target;
        this.startedAt = tick;
    }

    /** Hunts any food animal until the inventory carries {@code points} hunger points of food. */
    static Hunter forFood(int points, long tick) {
        return new Hunter(null, points, tick);
    }

    /** The hunted item, or null when hunting for food in general. */
    String item() {
        return item;
    }

    private boolean forFood() {
        return item == null;
    }

    String tick(Minecraft client, long tick, int[] home, Consumer<String> walk, Runnable stop) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return "HUNT_FAILED_NO_WORLD: player world is unavailable";
        }
        var inventory = WorldProbe.inventory(player);
        String wanted = forFood() ? "hunger points of food" : item;
        int held = forFood() ? FoodPlan.stock(inventory) : TechTree.held(inventory, item);
        if (held >= target) {
            stop.run();
            return "HUNTED: now holding " + held + " " + wanted + " after " + kills + " sheep and " + foodKills
                    + " food animal(s)";
        }
        if (tick - startedAt > TIMEOUT_TICKS) {
            stop.run();
            return "HUNT_FAILED_TIMEOUT: holding " + held + "/" + target + " " + wanted + " after 15 minutes of searching";
        }
        if (tick % 5 != 0) {
            return null;
        }
        Entity last = lastTargetId < 0 ? null : client.level.getEntity(lastTargetId);
        if (lastTargetId >= 0 && (last == null || !last.isAlive())) {
            if (lastTargetFood) {
                foodKills++;
            } else {
                kills++;
            }
            lastTargetId = -1;
        }
        boolean foodNeeded = forFood() || FoodPlan.needFood(inventory);
        // Gathering food treats every food animal in range as worth it, like starving.
        boolean starving = forFood() || (player.getFoodData().getFoodLevel() <= 8 && FoodPlan.stock(inventory) < 6);
        ItemEntity loot = nearestLoot(client, player, foodNeeded);
        if (loot != null) {
            walkTo(loot.blockPosition(), tick, walk);
            return null;
        }
        Sheep sheep = null;
        Animal food = null;
        double sheepDistance = Double.MAX_VALUE;
        double foodDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Animal animal) || !animal.isAlive() || animal.isBaby()) {
                continue;
            }
            double distance = animal.distanceTo(player);
            if (!forFood() && animal instanceof Sheep candidate && candidate.getColor() == DyeColor.WHITE
                    && !candidate.isSheared()) {
                lastSheepSeen = candidate.blockPosition();
                lastSheepSeenAt = tick;
                if (distance < sheepDistance) {
                    sheep = candidate;
                    sheepDistance = distance;
                }
            }
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString();
            boolean worthIt = starving || (foodNeeded && distance <= FOOD_DETOUR);
            if (worthIt && FoodPlan.ANIMAL_FOOD.containsKey(type) && distance < foodDistance) {
                food = animal;
                foodDistance = distance;
            }
        }
        Animal prey = food != null && (sheep == null || starving || foodDistance < sheepDistance) ? food : sheep;
        if (prey != null) {
            leg = null;
            MeleeCombat.ensureWeaponInHotbar(client);
            if (player.isWithinEntityInteractionRange(prey, 0) && player.hasLineOfSight(prey)) {
                stop.run();
                lastGoto = null;
                lastTargetId = prey.getId();
                lastTargetFood = prey != sheep;
                MeleeCombat.hitAnimal(client, prey);
                return null;
            }
            walkTo(prey.blockPosition(), tick, walk);
            return null;
        }
        if (!forFood() && lastSheepSeen != null && tick - lastSheepSeenAt < SIGHTING_TICKS
                && lastSheepSeen.distSqr(player.blockPosition()) > 36) {
            walkTo(lastSheepSeen, tick, walk);
            return null;
        }
        explore(player, home, tick, walk);
        return null;
    }

    /** Walks the current expedition leg, moving on to the next direction when it is reached or stalls. */
    private void explore(LocalPlayer player, int[] home, long tick, Consumer<String> walk) {
        int[] origin = home != null ? home : new int[] {player.getBlockX(), player.getBlockY(), player.getBlockZ()};
        if (leg == null) {
            leg = HuntSearch.leg(origin, expedition);
            legBest = Double.MAX_VALUE;
            legProgressAt = tick;
            lastGoto = null;
        }
        double remaining = Math.hypot(leg[0] - player.getBlockX(), leg[2] - player.getBlockZ());
        if (remaining < legBest - 1) {
            legBest = remaining;
            legProgressAt = tick;
        }
        if (remaining <= 8 || tick - legProgressAt > LEG_STALL_TICKS) {
            ActionableLog.LOGGER.info("[Actionable] hunt_expedition_done leg={} reached={}", expedition, remaining <= 8);
            expedition++;
            leg = null;
            return;
        }
        if (lastGoto == null || tick - lastGotoAt > 200) {
            lastGoto = new BlockPos(leg[0], player.getBlockY(), leg[2]);
            lastGotoAt = tick;
            ActionableLog.LOGGER.info("[Actionable] hunt_expedition leg={} target={},{}", expedition, leg[0], leg[2]);
            walk.accept(HomeRange.surfaceGoto(leg));
        }
    }

    private ItemEntity nearestLoot(Minecraft client, LocalPlayer player, boolean foodNeeded) {
        ItemEntity loot = null;
        double best = Double.MAX_VALUE;
        for (Entity entity : java.util.Objects.requireNonNull(client.level).entitiesForRendering()) {
            if (!(entity instanceof ItemEntity drop) || !drop.isAlive()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString();
            if (!id.equals(item) && !(foodNeeded && FoodPlan.isFood(id))) {
                continue;
            }
            double distance = drop.distanceTo(player);
            if (distance <= LOOT_RADIUS && distance < best) {
                loot = drop;
                best = distance;
            }
        }
        return loot;
    }

    private void walkTo(BlockPos pos, long tick, Consumer<String> walk) {
        boolean moved = lastGoto == null || lastGoto.distSqr(pos) > 4;
        if (moved || tick - lastGotoAt > REPATH_TICKS) {
            lastGoto = pos;
            lastGotoAt = tick;
            walk.accept("goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
    }
}
