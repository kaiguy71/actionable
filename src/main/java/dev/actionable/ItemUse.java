package dev.actionable;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Validated ordinary player interactions for filling/emptying buckets and igniting a portal frame. */
final class ItemUse {
    private ItemUse() { }

    static String use(Minecraft client, String item, String faceName, int x, int y, int z) {
        var player = client.player;
        var level = client.level;
        if (player == null || level == null || client.gameMode == null || !player.isAlive())
            return "ITEM_USE_FAILED_NO_WORLD";
        if (!ItemUseRules.valid(item, faceName, x, y, z)) return "ITEM_USE_FAILED_INVALID_REQUEST";
        if (!MeleeCombat.inputAvailable(client)) return "ITEM_USE_BUSY: focus the game or close the menu";
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.hasChunk(x >> 4, z >> 4) || level.isOutsideBuildHeight(pos)) return "ITEM_USE_FAILED_UNLOADED_TARGET";
        Direction face = Direction.valueOf(faceName.toUpperCase(java.util.Locale.ROOT));
        boolean filling = item.equals("minecraft:bucket");
        Vec3 target = filling ? Vec3.atCenterOf(pos) : Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.499, face.getStepY() * 0.499, face.getStepZ() * 0.499);
        if (player.getEyePosition().distanceToSqr(target) > 4.5 * 4.5) return "ITEM_USE_FAILED_TOO_FAR: move within reach";
        var hit = level.clip(new ClipContext(player.getEyePosition(), target,
                ClipContext.Block.OUTLINE, filling ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)
                || !filling && hit.getDirection() != face) return "ITEM_USE_FAILED_OCCLUDED: target face is not visible";
        if (filling) {
            var fluid = level.getFluidState(pos);
            if (!fluid.isSource() || !(fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.LAVA))
                return "ITEM_USE_FAILED_NOT_SOURCE: an empty bucket needs a water or lava source block";
        } else {
            BlockPos destination = pos.relative(face);
            if (!level.hasChunk(destination.getX() >> 4, destination.getZ() >> 4)
                    || level.isOutsideBuildHeight(destination)) return "ITEM_USE_FAILED_UNLOADED_DESTINATION";
            var state = level.getBlockState(destination);
            if (!ItemUseRules.mayPlace(item, level.dimension().equals(Level.NETHER), state.canBeReplaced(),
                    player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(destination)),
                    Math.sqrt(destination.distSqr(player.blockPosition())))) return "ITEM_USE_FAILED_UNSAFE_DESTINATION";
            if (item.equals("minecraft:flint_and_steel") && (!level.getBlockState(pos).is(Blocks.OBSIDIAN)
                    || face != Direction.UP || !state.isAir()))
                return "ITEM_USE_FAILED_PORTAL_BASE: ignite the top of an obsidian base with air above it";
        }
        String equipped = InventoryAccess.equip(client, item);
        if (equipped.startsWith("EQUIP_FAILED")) return "ITEM_USE_FAILED_ITEM: " + equipped;
        if (!player.getMainHandItem().is(BuiltInRegistries.ITEM.getValue(Identifier.parse(item))))
            return "ITEM_USE_BUSY: inventory swap pending; observe before retrying";
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        player.sendChanges();
        var result = item.equals("minecraft:flint_and_steel")
                ? client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
                : client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        return result.consumesAction() ? "ITEM_USE_SENT: " + item + " at " + x + "," + y + "," + z
                + " face=" + faceName + "; verify inventory/fluid/portal changes before claiming success"
                : "ITEM_USE_FAILED_NO_EFFECT: observe target and inventory before retrying";
    }
}
