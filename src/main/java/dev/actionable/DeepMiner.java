package dev.actionable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Finds diamonds by itself: digs a staircase down to the diamond layer (y=-58), then tunnels in a
 * square spiral, scanning every two seconds for diamond ore in the loaded blocks. When ore is in range
 * it hands the mining to Baritone, and goes back to digging when Baritone has no more to mine. Stops
 * at lava, water, a drop, low health or food, or a full inventory, then walks back to the house.
 */
final class DeepMiner {
    private static final int TIMEOUT_TICKS = 24000;
    private static final int WALK_REPEAT_TICKS = 60;
    /** Baritone needs a moment to pick up a command before "idle" means it has finished. */
    private static final int ORE_GRACE_TICKS = 60;
    private static final int ORE_TIMEOUT_TICKS = 2400;
    private static final int RETURN_TIMEOUT_TICKS = 6000;
    private static final int OVERWORLD_MIN_Y = -64;
    private static final int OVERWORLD_MAX_Y = 320;

    private enum Phase { DIG, ORE, RETURN }

    private final String item;
    private final int target;
    private final int[] home;
    private final long startedAt;
    private final Set<List<Integer>> skipped = new HashSet<>();
    private final Map<List<Integer>, Integer> attempts = new HashMap<>();
    private Phase phase = Phase.DIG;
    private int[] cursor;
    private int heading;
    private int blockedTurns;
    private int leg;
    private int legDug;
    private int totalDug;
    private BlockBreaker breaking;
    private long lastWalkAt = -WALK_REPEAT_TICKS;
    private long lastScanAt = -DeepMine.SCAN_INTERVAL;
    private DeepMine.Ore ore;
    private long oreSince;
    private long returnSince;
    private String finish;
    private boolean entered;

    DeepMiner(String item, int target, int[] home, long tick) {
        this.item = item;
        this.target = target;
        this.home = home;
        this.startedAt = tick;
    }

    String describe() {
        return "diamond mine for " + target + " " + item;
    }

    /** Returns null while working, otherwise the final result (after the walk back to the house). */
    String tick(Minecraft client, long tick, Consumer<String> walk, Runnable stop, BooleanSupplier baritoneBusy) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null) {
            return "MINE_FAILED_NO_WORLD: player world is unavailable";
        }
        if (phase == Phase.RETURN) {
            return climb(player, tick, walk);
        }
        var inventory = WorldProbe.inventory(player);
        int held = TechTree.held(inventory, item);
        if (held >= target) {
            return stopAndReturn("MINED: now holding " + held + " " + item, client, tick, stop);
        }
        if (player.getHealth() <= 10 || player.getFoodData().getFoodLevel() <= 6) {
            return stopAndReturn("MINE_STOPPED_HEALTH: health or food is low; leaving the mine", client, tick, stop);
        }
        if (TechTree.pickaxeTier(inventory).compareTo(TechTree.Tier.IRON) < 0) {
            return stopAndReturn("MINE_STOPPED_NO_TOOL: no pickaxe capable of harvesting diamonds remains",
                    client, tick, stop);
        }
        if (player.getInventory().getFreeSlot() < 0) {
            return stopAndReturn("MINE_STOPPED_INVENTORY_FULL: holding " + held + " " + item, client, tick, stop);
        }
        if (tick - startedAt > TIMEOUT_TICKS) {
            return stopAndReturn("MINE_STOPPED_TIMEOUT: holding " + held + " " + item + " after 20 minutes", client,
                    tick, stop);
        }
        if (!entered) {
            if (home != null && HouseDesign.nearHouse(home, player.getBlockX(), player.getBlockY(), player.getBlockZ())) {
                if (tick - lastWalkAt > WALK_REPEAT_TICKS) {
                    lastWalkAt = tick;
                    walk.accept("goto " + (home[0] + HouseDesign.WIDTH + 3) + " " + (home[2] + 1));
                }
                return null;
            }
            entered = true;
        }
        DeepMine.Reader reader = reader(level);
        if (phase == Phase.ORE) {
            return mineOre(client, tick, reader, walk, stop, baritoneBusy);
        }
        if (tick - lastScanAt >= DeepMine.SCAN_INTERVAL) {
            lastScanAt = tick;
            if (scan(client, tick, reader, walk, stop)) {
                return null;
            }
        }
        return dig(client, tick, reader, walk, stop);
    }

    /** Looks for diamond ore in the loaded blocks; true when Baritone was sent after some. */
    private boolean scan(Minecraft client, long tick, DeepMine.Reader reader, Consumer<String> walk, Runnable stop) {
        LocalPlayer player = java.util.Objects.requireNonNull(client.player);
        Optional<DeepMine.Ore> found = DeepMine.nearestOre(reader, player.getBlockX(), player.getBlockY(),
                player.getBlockZ(), skipped, OVERWORLD_MIN_Y, OVERWORLD_MAX_Y);
        if (found.isEmpty()) {
            return false;
        }
        DeepMine.Ore candidate = found.get();
        double distance = candidate.distanceFrom(player.getBlockX(), player.getBlockY(), player.getBlockZ());
        if (distance > DeepMine.APPROACH_DISTANCE) {
            return false;
        }
        if (breaking != null) {
            breaking.cancel(client);
            breaking = null;
        }
        ore = candidate;
        phase = Phase.ORE;
        oreSince = tick;
        attempts.merge(DeepMine.key(ore.x(), ore.y(), ore.z()), 1, Integer::sum);
        stop.run();
        walk.accept("mine " + target + " " + ore.block());
        ActionableLog.LOGGER.info("[Actionable] deep_mine_ore ore={},{},{} distance={}", ore.x(), ore.y(), ore.z(),
                Math.round(distance));
        return true;
    }

    /** Waits while Baritone mines the ore; returns to digging when it stops or takes too long. */
    private String mineOre(Minecraft client, long tick, DeepMine.Reader reader, Consumer<String> walk, Runnable stop,
            BooleanSupplier baritoneBusy) {
        boolean timedOut = tick - oreSince > ORE_TIMEOUT_TICKS;
        boolean idle = tick - oreSince > ORE_GRACE_TICKS && !baritoneBusy.getAsBoolean();
        if (!timedOut && !idle) {
            return null;
        }
        stop.run();
        List<Integer> key = DeepMine.key(ore.x(), ore.y(), ore.z());
        DeepMine.Cell now = reader.at(ore.x(), ore.y(), ore.z());
        boolean remains = now == DeepMine.Cell.DIAMOND || now == DeepMine.Cell.DEEPSLATE_DIAMOND;
        if (remains && attempts.getOrDefault(key, 0) >= DeepMine.ORE_ATTEMPTS) {
            skipped.add(key);
            ActionableLog.LOGGER.info("[Actionable] deep_mine_ore_skipped ore={},{},{}", ore.x(), ore.y(), ore.z());
        }
        // Dig on from wherever Baritone left the player, and look again at once.
        phase = Phase.DIG;
        cursor = null;
        lastScanAt = tick - DeepMine.SCAN_INTERVAL;
        return null;
    }

    /** Clears the next cell of the staircase or tunnel, one block at a time, and steps into it. */
    private String dig(Minecraft client, long tick, DeepMine.Reader reader, Consumer<String> walk, Runnable stop) {
        LocalPlayer player = java.util.Objects.requireNonNull(client.player);
        ClientLevel level = java.util.Objects.requireNonNull(client.level);
        if (cursor == null) {
            cursor = new int[] {player.getBlockX(), player.getBlockY(), player.getBlockZ()};
        }
        BlockPos here = new BlockPos(cursor[0], cursor[1], cursor[2]);
        if (player.blockPosition().distSqr(here) > 2) {
            if (tick - lastWalkAt > WALK_REPEAT_TICKS) {
                lastWalkAt = tick;
                walk.accept("goto " + here.getX() + " " + here.getY() + " " + here.getZ());
            }
            return null;
        }
        stop.run();
        boolean descending = cursor[1] > DeepMine.TUNNEL_Y;
        int[] next = DeepMine.nextFeet(cursor, heading, descending);
        if (DeepMine.touchesHome(home, next, descending)) {
            return turn(client, tick, "MINE_STOPPED_HOME: protecting the house shell and floor", stop);
        }
        DeepMine.Check check = DeepMine.check(reader, next, descending);
        if (check != DeepMine.Check.OK) {
            return turn(client, tick, "MINE_STOPPED_" + check + ": the " + (descending ? "stairs" : "tunnel")
                    + " ahead opened onto " + check.name().toLowerCase(java.util.Locale.ROOT), stop);
        }
        if (breaking != null) {
            String result = breaking.tick(client, tick, walk, stop);
            if (result == null) {
                return null;
            }
            breaking = null;
            if (!result.startsWith("BROKE:")) {
                return turn(client, tick, "MINE_STOPPED_BREAK_FAILED: " + result, stop);
            }
        }
        for (int[] cell : DeepMine.cells(next, descending)) {
            BlockPos pos = new BlockPos(cell[0], cell[1], cell[2]);
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            BlockBreaker.Start start = BlockBreaker.start(client, pos.getX(), pos.getY(), pos.getZ(), tick);
            if (start.breaker() == null) {
                return turn(client, tick, "MINE_STOPPED_UNBREAKABLE: " + start.result(), stop);
            }
            breaking = start.breaker();
            return null;
        }
        cursor = next;
        blockedTurns = 0;
        totalDug++;
        if (!descending && ++legDug >= DeepMine.legLength(leg)) {
            leg++;
            legDug = 0;
            heading = (heading + 1) % 4;
        }
        if (totalDug > DeepMine.MAX_DUG) {
            return stopAndReturn("MINE_FAILED_NO_DIAMONDS: dug " + totalDug + " blocks without finding diamond ore",
                    client, tick, stop);
        }
        return null;
    }

    /** Tries another heading after a hazard; ends the mine when all four are blocked in a row. */
    private String turn(Minecraft client, long tick, String reason, Runnable stop) {
        cancel(client);
        ActionableLog.LOGGER.info("[Actionable] deep_mine_turn heading={} reason={}", heading, ActionableLog.text(reason));
        heading = (heading + 1) % 4;
        legDug = 0;
        if (++blockedTurns >= 4) {
            return stopAndReturn(reason + "; every direction is blocked here", client, tick, stop);
        }
        return null;
    }

    private String stopAndReturn(String result, Minecraft client, long tick, Runnable stop) {
        stop.run();
        if (breaking != null) {
            breaking.cancel(client);
            breaking = null;
        }
        finish = result;
        phase = Phase.RETURN;
        returnSince = tick;
        ActionableLog.LOGGER.info("[Actionable] deep_mine_done {}", ActionableLog.text(result));
        // The next tick issues the real return route; never update its timer with a no-op callback.
        return home == null ? finish : null;
    }

    private String climb(LocalPlayer player, long tick, Consumer<String> walk) {
        if (home == null || HouseDesign.inside(home, player.getBlockX(), player.getBlockY(), player.getBlockZ())) {
            return finish;
        }
        if (tick - returnSince > RETURN_TIMEOUT_TICKS) {
            return finish + " (could not get back to the house)";
        }
        if (tick - lastWalkAt > WALK_REPEAT_TICKS) {
            lastWalkAt = tick;
            int[] stand = HouseDesign.DOOR_STAND;
            walk.accept("goto " + (home[0] + stand[0]) + " " + (home[1] + stand[1]) + " " + (home[2] + stand[2]));
        }
        return null;
    }

    /** Release held block-breaking input when a task, world or survival routine interrupts the mine. */
    void cancel(Minecraft client) {
        if (breaking != null) {
            breaking.cancel(client);
            breaking = null;
        }
    }

    /** Reads only loaded blocks for the pure mine rules. A scan reads about 140,000 blocks. */
    static DeepMine.Reader reader(ClientLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            if (y < OVERWORLD_MIN_Y || y >= OVERWORLD_MAX_Y || !level.hasChunk(x >> 4, z >> 4)) {
                return DeepMine.Cell.UNLOADED;
            }
            var state = level.getBlockState(pos.set(x, y, z));
            var block = state.getBlock();
            if (block == Blocks.DEEPSLATE_DIAMOND_ORE) {
                return DeepMine.Cell.DEEPSLATE_DIAMOND;
            }
            if (block == Blocks.DIAMOND_ORE) {
                return DeepMine.Cell.DIAMOND;
            }
            if (state.isAir()) {
                return DeepMine.Cell.AIR;
            }
            if (state.is(Blocks.LAVA)) {
                return DeepMine.Cell.LAVA;
            }
            if (!state.getFluidState().isEmpty()) {
                return DeepMine.Cell.WATER;
            }
            if (block instanceof net.minecraft.world.level.block.FallingBlock || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
                return DeepMine.Cell.UNSAFE;
            }
            return state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)
                    ? DeepMine.Cell.SOLID : DeepMine.Cell.AIR;
        };
    }
}
