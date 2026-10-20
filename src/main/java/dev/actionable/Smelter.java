package dev.actionable;

import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runs the house furnace through whole cycles: open it, take out anything finished, load all of the
 * most useful input (raw ore, raw meat, fish, sand) with enough fuel, close it and wait nearby while it
 * smelts (closed, so the player can still defend itself), then reopen, collect, and load the next
 * input. Finishes when nothing smeltable or no fuel is left.
 */
final class Smelter {
    private static final int OPEN_TIMEOUT_TICKS = 40;
    /** Ten seconds per item, plus a little slack. */
    private static final int TICKS_PER_ITEM = 200;
    private static final int TOTAL_TIMEOUT_TICKS = 12000;

    private enum Phase { OPEN, WAIT }

    private final BlockPos furnace;
    private final long startedAt;
    private Phase phase = Phase.OPEN;
    private long phaseAt;
    private boolean clicked;
    private long readyAt;
    private int collected;
    private int loaded;
    private final StringBuilder made = new StringBuilder();

    private Smelter(BlockPos furnace, long tick) {
        this.furnace = furnace;
        this.startedAt = tick;
        this.phaseAt = tick;
    }

    record Start(Smelter smelter, String result) {
    }

    static Start start(Minecraft client, BlockPos furnace, long tick) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            return new Start(null, "SMELT_FAILED_NO_WORLD: player world is unavailable");
        }
        if (!client.level.getBlockState(furnace).is(net.minecraft.world.level.block.Blocks.FURNACE)) {
            return new Start(null, "SMELT_FAILED_NO_FURNACE: there is no furnace in the house");
        }
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(furnace)) > 20.25) {
            return new Start(null, "SMELT_FAILED_OUT_OF_REACH: walk into the house first");
        }
        return new Start(new Smelter(furnace, tick), null);
    }

    /** Returns null while working, otherwise the final result. */
    String tick(Minecraft client, long tick, java.util.function.IntConsumer loadedItems) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return "SMELT_FAILED_NO_WORLD: player world became unavailable";
        }
        if (tick - startedAt > TOTAL_TIMEOUT_TICKS) {
            close(player);
            return "SMELTED: stopped after ten minutes; collected " + collected + made;
        }
        if (phase == Phase.WAIT) {
            if (tick >= readyAt) {
                phase = Phase.OPEN;
                phaseAt = tick;
                clicked = false;
            }
            return null;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu furnaceMenu)) {
            if (!clicked) {
                clicked = true;
                Vec3 face = Vec3.atCenterOf(furnace).add(0, 0.5, 0);
                player.lookAt(EntityAnchorArgument.Anchor.EYES, face);
                client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(face, Direction.UP, furnace, false));
            } else if (tick - phaseAt > OPEN_TIMEOUT_TICKS) {
                return "SMELT_FAILED_DID_NOT_OPEN: the furnace did not open";
            }
            return null;
        }
        ItemStack output = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        if (!output.isEmpty()) {
            collected += output.getCount();
            made.append("; ").append(output.getCount()).append(' ').append(id(output));
            click(client, menu, AbstractFurnaceMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE);
        }
        Map<String, Integer> inventory = WorldProbe.inventory(player);
        ItemStack input = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
        if (!input.isEmpty()) {
            // Still smelting: top up the fuel if it ran out, then close and wait for the rest.
            if (menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty() && !furnaceMenu.isLit()) {
                var fuel = FurnacePlan.fuelFor(inventory, input.getCount());
                if (fuel.isEmpty()) {
                    close(player);
                    return "SMELT_FAILED_NO_FUEL: " + input.getCount() + " " + id(input) + " still in the furnace;"
                            + " needs coal, charcoal, logs or spare planks; collected " + collected + made;
                }
                put(client, player, menu, fuel.get().item(), fuel.get().count(), AbstractFurnaceMenu.FUEL_SLOT);
            }
            return waitFor(player, tick, input.getCount());
        }
        var load = FurnacePlan.choose(inventory);
        if (load.isPresent() && menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty()
                || load.isPresent() && id(menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem()).equals(load.get().fuel())) {
            put(client, player, menu, load.get().input(), load.get().count(), AbstractFurnaceMenu.INGREDIENT_SLOT);
            put(client, player, menu, load.get().fuel(), load.get().fuelCount(), AbstractFurnaceMenu.FUEL_SLOT);
            loaded += load.get().count();
            loadedItems.accept(load.get().count());
            return waitFor(player, tick, load.get().count());
        }
        close(player);
        if (collected == 0 && loaded == 0) {
            return FurnacePlan.hasSmeltables(inventory)
                    ? "SMELT_FAILED_NO_FUEL: raw items to smelt but no coal, charcoal, logs or spare planks"
                    : "SMELTED: nothing to smelt";
        }
        return "SMELTED: loaded " + loaded + ", collected " + collected + made;
    }

    private String waitFor(LocalPlayer player, long tick, int items) {
        close(player);
        phase = Phase.WAIT;
        readyAt = tick + (long) items * TICKS_PER_ITEM + 20;
        return null;
    }

    /** Moves {@code count} of an item from the inventory into a furnace slot, one at a time. */
    private static void put(Minecraft client, LocalPlayer player, AbstractContainerMenu menu, String item, int count,
            int target) {
        int moved = 0;
        while (moved < count) {
            int source = sourceSlot(player, menu, item);
            if (source < 0) {
                return;
            }
            int inStack = menu.getSlot(source).getItem().getCount();
            click(client, menu, source, 0, ContainerInput.PICKUP);
            int place = Math.min(count - moved, inStack);
            for (int placed = 0; placed < place; placed++) {
                click(client, menu, target, 1, ContainerInput.PICKUP);
            }
            moved += place;
            click(client, menu, source, 0, ContainerInput.PICKUP);
        }
    }

    private static void close(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    private static int sourceSlot(LocalPlayer player, AbstractContainerMenu menu, String item) {
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() && !slot.getItem().isEmpty() && id(slot.getItem()).equals(item)) {
                return slot.index;
            }
        }
        return -1;
    }

    private static void click(Minecraft client, AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        java.util.Objects.requireNonNull(client.gameMode).handleContainerInput(menu.containerId, slot, button, input,
                java.util.Objects.requireNonNull(client.player));
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
