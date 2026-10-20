package dev.actionable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Right-clicking blocks and operating whatever menu is open (furnace, chest, barrel, table, inventory). */
final class MenuActions {
    private static final double REACH_SQUARED = 4.5 * 4.5;
    private static final int MAX_LISTED_SLOTS = 60;

    private MenuActions() {
    }

    static String useBlock(Minecraft client, int x, int y, int z) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            return "USE_FAILED_NO_WORLD: no active world";
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (client.level.getBlockState(pos).isAir()) {
            return "USE_FAILED_AIR: no block at " + x + "," + y + "," + z;
        }
        Vec3 target = Vec3.atCenterOf(pos);
        if (player.getEyePosition().distanceToSqr(target) > REACH_SQUARED) {
            return "USE_FAILED_TOO_FAR: " + x + "," + y + "," + z + " is out of reach (4.5 blocks); run "
                    + WorldProbe.gotoBeside(client.level, pos) + " (a free cell beside it; never goto the block's own coordinates) and try again";
        }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(target));
        BlockHitResult hit = new BlockHitResult(target.add(face.getStepX() * 0.5, face.getStepY() * 0.5,
                face.getStepZ() * 0.5), face, pos, false);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        String block = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).getPath();
        return result.consumesAction()
                ? "used " + block + " at " + x + "," + y + "," + z
                        + "; if it opens a menu, use menu op=read next turn (the menu appears after a moment)"
                : "USE_FAILED_NOTHING_HAPPENED: " + block + " did not react to right-click";
    }

    static String menu(Minecraft client, String op, int slot, int toSlot) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return "MENU_FAILED_NO_WORLD: no active world";
        }
        AbstractContainerMenu menu = player.containerMenu;
        switch (op) {
            case "read" -> {
                return read(player, menu);
            }
            case "close" -> {
                boolean open = menu != player.inventoryMenu;
                player.closeContainer();
                return open ? "closed the open menu" : "no menu was open";
            }
            default -> {
            }
        }
        if (slot >= menu.slots.size() || (op.equals("move") && toSlot >= menu.slots.size())) {
            return "MENU_FAILED_BAD_SLOT: this menu has slots 0.." + (menu.slots.size() - 1);
        }
        ItemStack stack = menu.getSlot(slot).getItem();
        if (stack.isEmpty()) {
            return "MENU_FAILED_EMPTY_SLOT: slot " + slot + " is empty; use menu op=read to see slot contents";
        }
        String what = name(stack) + " x" + stack.getCount();
        switch (op) {
            case "quick_move" -> {
                click(client, menu, slot, 0, ContainerInput.QUICK_MOVE);
                return "shift-clicked slot " + slot + " (" + what + ")";
            }
            case "drop" -> {
                click(client, menu, slot, 1, ContainerInput.THROW);
                return "dropped one-stack from slot " + slot + " (" + what + ")";
            }
            case "move" -> {
                click(client, menu, slot, 0, ContainerInput.PICKUP);
                click(client, menu, toSlot, 0, ContainerInput.PICKUP);
                // Anything left on the cursor (swapped item, or a refused move) goes back where it came from.
                if (!menu.getCarried().isEmpty()) {
                    click(client, menu, slot, 0, ContainerInput.PICKUP);
                }
                return "moved " + what + " from slot " + slot + " to slot " + toSlot;
            }
            default -> {
                return "MENU_FAILED_UNKNOWN_OP: " + op;
            }
        }
    }

    /** Lists only occupied slots, split into the container part and the player's own part. */
    private static String read(LocalPlayer player, AbstractContainerMenu menu) {
        StringBuilder out = new StringBuilder(menu == player.inventoryMenu
                ? "open_menu=player_inventory(5=head,6=chest,7=legs,8=feet,9-35=main,36-44=hotbar,45=offhand){"
                : "open_menu=" + menu.getType() + "{");
        int listed = 0;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || listed >= MAX_LISTED_SLOTS) {
                continue;
            }
            String part = slot.container == player.getInventory() ? "inv" : "box";
            out.append(listed++ > 0 ? "," : "").append(slot.index).append(':').append(name(stack))
                    .append(" x").append(stack.getCount()).append('(').append(part).append(')');
        }
        out.append("; total_slots=").append(menu.slots.size());
        if (!menu.getCarried().isEmpty()) {
            out.append("; cursor=").append(name(menu.getCarried())).append(" x").append(menu.getCarried().getCount());
        }
        return out.append('}').toString();
    }

    private static String name(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static void click(Minecraft client, AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        java.util.Objects.requireNonNull(client.gameMode).handleContainerInput(
                menu.containerId, slot, button, input, java.util.Objects.requireNonNull(client.player));
    }
}
