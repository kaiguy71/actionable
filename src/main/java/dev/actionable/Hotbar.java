package dev.actionable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Hotbar slot policy: tools, weapon and food keep their slots (see {@link InventoryLayout}); blocks and
 * utility items rotate through the free slots 3-6. Moving an item into the hand for placement goes
 * through {@link PlacementPreparation}, which aims at {@link #freeSlot} and verifies the result; the
 * earlier unverified swap helper was removed so placement cannot fall back to whatever is held.
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

    /** An empty slot among 3-6, else the next one in turn; tool, weapon and food slots are left alone. */
    static int freeSlot(net.minecraft.world.entity.player.Player player) {
        for (int slot = FIRST_FREE; slot <= LAST_FREE; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        rotation = (rotation + 1) % (LAST_FREE - FIRST_FREE + 1);
        return FIRST_FREE + rotation;
    }
}
