package dev.actionable;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Muscle memory for the model: any action that needs an item in hand gets it here, without a turn
 * spent on moving it. Tools, weapon and food keep their slots (see {@link InventoryLayout}); blocks and
 * utility items rotate through the free slots 3-6.
 */
final class Hotbar {
    private static final int FIRST_FREE = 3;
    private static final int LAST_FREE = 6;
    private static int rotation;

    private Hotbar() {
    }

    /** Hotbar slot (0-8) holding the item, or -1 when it is not on the hotbar. */
    static int find(net.minecraft.world.entity.player.Player player, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Makes sure the item is on the hotbar, swapping it in from the main inventory if needed, and returns
     * its hotbar slot, or -1 when the player does not hold it (or another menu is open).
     */
    static int bring(Minecraft client, Item item) {
        var player = client.player;
        var gameMode = client.gameMode;
        if (player == null || gameMode == null) {
            return -1;
        }
        int slot = find(player, item);
        if (slot >= 0 || player.containerMenu != player.inventoryMenu) {
            return slot;
        }
        for (int source = InventoryMenu.INV_SLOT_START; source < InventoryMenu.INV_SLOT_END; source++) {
            ItemStack stack = player.getInventory().getItem(source);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int destination = freeSlot(player);
            gameMode.handleContainerInput(player.inventoryMenu.containerId, source, destination,
                    ContainerInput.SWAP, player);
            return destination;
        }
        return -1;
    }

    /** An empty slot among 3-6, else the next one in turn; tool, weapon and food slots are left alone. */
    private static int freeSlot(net.minecraft.world.entity.player.Player player) {
        for (int slot = FIRST_FREE; slot <= LAST_FREE; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        rotation = (rotation + 1) % (LAST_FREE - FIRST_FREE + 1);
        return FIRST_FREE + rotation;
    }
}
