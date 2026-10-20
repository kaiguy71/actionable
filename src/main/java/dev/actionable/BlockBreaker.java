package dev.actionable;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks one block, such as vines, leaves or dirt in the way. Walks to a dry spot within reach first
 * when needed (the model kept trying to clear blocks from too far away), picks the fastest hotbar
 * tool, then mines it over the following ticks.
 */
final class BlockBreaker {
    private static final int WALK_TIMEOUT_TICKS = 600;
    private static final int BREAK_TIMEOUT_TICKS = 400;

    private final BlockPos target;
    private final String block;
    private final long startedAt;
    private long breakingSince = -1;
    private boolean walking;

    private BlockBreaker(BlockPos target, String block, long tick) {
        this.target = target;
        this.block = block;
        this.startedAt = tick;
    }

    /** Either a breaker to tick, or a final result when the request was refused or already done. */
    record Start(BlockBreaker breaker, String result) {
    }

    static Start start(Minecraft client, int x, int y, int z, long tick) {
        if (client.player == null || client.level == null || client.gameMode == null) {
            return new Start(null, "BREAK_FAILED_NO_WORLD: player world is unavailable");
        }
        BlockPos target = new BlockPos(x, y, z);
        BlockState state = client.level.getBlockState(target);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.isAir()) {
            return new Start(null, "BROKE: nothing to break at " + x + "," + y + "," + z + " (already air)");
        }
        if (state.getDestroySpeed(client.level, target) < 0) {
            return new Start(null, "BREAK_FAILED_UNBREAKABLE: " + id + " cannot be broken");
        }
        return new Start(new BlockBreaker(target, id, tick), null);
    }

    String describe() {
        return block + " at " + target.getX() + "," + target.getY() + "," + target.getZ();
    }

    java.util.Optional<String> workEvidence(Minecraft client, String result) {
        return result.equals("BROKE: " + describe()) && client.level != null
                && client.level.getBlockState(target).isAir()
                ? java.util.Optional.of(describe() + " started=" + startedAt) : java.util.Optional.empty();
    }

    /** Advances by one tick; returns null while working, otherwise the final result. */
    String tick(Minecraft client, long tick, Consumer<String> walk, Runnable stop) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            return "BREAK_FAILED_NO_WORLD: player world became unavailable";
        }
        BlockState current = client.level.getBlockState(target);
        if (current.isAir()) {
            client.gameMode.stopDestroyBlock();
            return "BROKE: " + describe();
        }
        if (current.is(net.minecraft.world.level.block.Blocks.WATER)) {
            // A block was just placed into this water and has not appeared yet; wait for it.
            return tick - startedAt > 100 ? "BROKE: only water at " + describe() : null;
        }
        boolean inReach = MiningPolicy.inReach(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                target.getX(), target.getY(), target.getZ());
        if (!inReach) {
            if (tick - startedAt > WALK_TIMEOUT_TICKS) {
                return "BREAK_FAILED_UNREACHABLE: could not get within reach of " + describe();
            }
            if (!walking) {
                BlockPos stand = WorldProbe.dryStandNear(client.level, target);
                if (stand == null) {
                    return "BREAK_FAILED_UNREACHABLE: no dry spot within reach of " + describe();
                }
                walking = true;
                walk.accept("goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            }
            return null;
        }
        if (walking) {
            walking = false;
            stop.run();
        }
        if (breakingSince < 0) {
            breakingSince = tick;
            selectBestTool(player, client.level.getBlockState(target));
        } else if (tick - breakingSince > BREAK_TIMEOUT_TICKS) {
            client.gameMode.stopDestroyBlock();
            return "BREAK_FAILED_TIMEOUT: " + describe() + " did not break in 20 seconds";
        }
        Vec3 center = Vec3.atCenterOf(target);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(center));
        if (tick == breakingSince) {
            client.gameMode.startDestroyBlock(target, face);
        } else {
            client.gameMode.continueDestroyBlock(target, face);
        }
        return null;
    }

    /** Stops breaking and leaves the block as it is. */
    void cancel(Minecraft client) {
        if (client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
    }

    private static void selectBestTool(LocalPlayer player, BlockState state) {
        int best = player.getInventory().getSelectedSlot();
        float bestSpeed = player.getInventory().getItem(best).getDestroySpeed(state);
        for (int slot = 0; slot < 9; slot++) {
            float speed = player.getInventory().getItem(slot).getDestroySpeed(state);
            if (speed > bestSpeed) {
                best = slot;
                bestSpeed = speed;
            }
        }
        player.getInventory().setSelectedSlot(best);
    }
}
