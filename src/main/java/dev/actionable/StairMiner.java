package dev.actionable;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Digs the staircase mine under the house until the inventory holds enough of an item (cobblestone,
 * raw iron), continuing from wherever the stairs already reach. Mines ore it uncovers beside the
 * stairs, and climbs back into the house when done, at the bottom, on a cave, water or lava, low
 * health or food, or a full inventory.
 */
final class StairMiner {
    private static final int TIMEOUT_TICKS = 6000;
    private static final int WALK_REPEAT_TICKS = 60;

    private final String item;
    private final int target;
    private final int[] home;
    private final long startedAt;
    private BlockBreaker breaking;
    private String finish;
    private long lastWalkAt = -WALK_REPEAT_TICKS;
    private boolean finished;

    StairMiner(String item, int target, int[] home, long tick) {
        this.item = item;
        this.target = target;
        this.home = home;
        this.startedAt = tick;
    }

    /** True when the mine can go no further: bottom reached, or it broke into a cave, water or lava. */
    boolean mineFinished() {
        return finished;
    }

    String describe() {
        return "house mine for " + target + " " + item;
    }

    /** Returns null while working, otherwise the final result once the player is back in the house. */
    String tick(Minecraft client, long tick, Consumer<String> walk, Runnable stop) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null) {
            return "MINE_FAILED_NO_WORLD: player world is unavailable";
        }
        if (finish != null) {
            return climb(player, tick, walk);
        }
        int held = TechTree.held(WorldProbe.inventory(player), item);
        if (held >= target) {
            return stopAndClimb("MINED: now holding " + held + " " + item, player, tick, walk, stop);
        }
        if (player.getHealth() <= 10 || player.getFoodData().getFoodLevel() <= 6) {
            return stopAndClimb("MINE_STOPPED_HEALTH: health or food is low; back to the house", player, tick, walk, stop);
        }
        if (player.getInventory().getFreeSlot() < 0) {
            return stopAndClimb("MINE_STOPPED_INVENTORY_FULL: holding " + held + " " + item, player, tick, walk, stop);
        }
        if (tick - startedAt > TIMEOUT_TICKS) {
            return stopAndClimb("MINE_STOPPED_TIMEOUT: holding " + held + " " + item, player, tick, walk, stop);
        }
        if (breaking != null) {
            if (breaking.tick(client, tick, command -> { }, () -> { }) == null) {
                return null;
            }
            breaking = null;
        }
        StairMine.Probe probe = probe(level);
        int maxStep = StairMine.maxStep(home[1]);
        int depth = StairMine.depth(probe, maxStep);
        if (depth > 0) {
            StairMine.Check check = StairMine.check(probe, depth);
            if (check != StairMine.Check.OK) {
                finished = true;
                return stopAndClimb("MINE_STOPPED_" + check + ": the stairs at step " + depth + " opened onto "
                        + check.name().toLowerCase(java.util.Locale.ROOT) + "; the house mine ends here", player, tick,
                        walk, stop);
            }
        }
        if (depth >= maxStep) {
            finished = true;
            return stopAndClimb("MINE_FINISHED_BOTTOM: the stairs reached y=" + StairMine.BOTTOM_Y, player, tick, walk, stop);
        }
        BlockPos feet = absolute(StairMine.feet(depth));
        if (player.blockPosition().distSqr(feet) > 2) {
            if (tick - lastWalkAt > WALK_REPEAT_TICKS) {
                lastWalkAt = tick;
                walk.accept("goto " + feet.getX() + " " + feet.getY() + " " + feet.getZ());
            }
            return null;
        }
        stop.run();
        for (int[] ore : StairMine.ores(probe, depth)) {
            if (startBreak(client, absolute(ore), tick)) {
                return null;
            }
        }
        for (int[] cell : StairMine.cells(depth + 1)) {
            StairMine.Cell next = probe.at(cell[0], cell[1], cell[2]);
            if (next == StairMine.Cell.WATER || next == StairMine.Cell.LAVA) {
                finished = true;
                return stopAndClimb("MINE_STOPPED_" + next + ": the next step down is " + next.name().toLowerCase(
                        java.util.Locale.ROOT) + "; the house mine ends here", player, tick, walk, stop);
            }
        }
        for (int[] cell : StairMine.cells(depth + 1)) {
            BlockPos pos = absolute(cell);
            if (!level.getBlockState(pos).isAir() && startBreak(client, pos, tick)) {
                return null;
            }
        }
        return null;
    }

    /** Stops mining for good and walks back into the house; the result is reported on arrival. */
    void abort() {
        if (finish == null) {
            finish = "MINE_STOPPED: called back to the house";
        }
    }

    private boolean startBreak(Minecraft client, BlockPos pos, long tick) {
        BlockBreaker.Start start = BlockBreaker.start(client, pos.getX(), pos.getY(), pos.getZ(), tick);
        if (start.breaker() == null) {
            return false;
        }
        breaking = start.breaker();
        return true;
    }

    private String stopAndClimb(String result, LocalPlayer player, long tick, Consumer<String> walk, Runnable stop) {
        stop.run();
        finish = result;
        return climb(player, tick, walk);
    }

    private String climb(LocalPlayer player, long tick, Consumer<String> walk) {
        BlockPos top = absolute(HouseDesign.DOOR_STAND);
        if (HouseDesign.inside(home, player.getBlockX(), player.getBlockY(), player.getBlockZ())) {
            return finish;
        }
        if (tick - lastWalkAt > WALK_REPEAT_TICKS) {
            lastWalkAt = tick;
            walk.accept("goto " + top.getX() + " " + top.getY() + " " + top.getZ());
        }
        return null;
    }

    private BlockPos absolute(int[] relative) {
        return new BlockPos(home[0] + relative[0], home[1] + relative[1], home[2] + relative[2]);
    }

    StairMine.Probe probe(ClientLevel level) {
        return probe(level, home);
    }

    /** Classifies blocks around the house mine for the pure stair rules. */
    static StairMine.Probe probe(ClientLevel level, int[] home) {
        return (x, y, z) -> {
            var state = level.getBlockState(new BlockPos(home[0] + x, home[1] + y, home[2] + z));
            if (state.isAir()) {
                return StairMine.Cell.AIR;
            }
            if (state.is(net.minecraft.world.level.block.Blocks.LAVA)) {
                return StairMine.Cell.LAVA;
            }
            if (!state.getFluidState().isEmpty()) {
                return StairMine.Cell.WATER;
            }
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            return id.endsWith("_ore") ? StairMine.Cell.ORE : StairMine.Cell.SOLID;
        };
    }
}
