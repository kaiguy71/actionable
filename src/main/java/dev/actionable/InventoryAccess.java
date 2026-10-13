package dev.actionable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What the model can see of, and do with, the player's own inventory. Reading is part of every
 * world snapshot; the one write is {@link #equip}, which puts an item the player already carries
 * into the hand or the matching armor/shield slot.
 */
final class InventoryAccess {
    private InventoryAccess() {
    }

    /** Held item, off-hand, worn armor and free space: the parts the item list alone does not show. */
    static String describe(Player player) {
        StringBuilder text = new StringBuilder("held_item=").append(itemName(player.getMainHandItem()))
                .append("; offhand=").append(itemName(player.getOffhandItem()))
                .append("; armor=[head=").append(itemName(player.getItemBySlot(EquipmentSlot.HEAD)))
                .append(",chest=").append(itemName(player.getItemBySlot(EquipmentSlot.CHEST)))
                .append(",legs=").append(itemName(player.getItemBySlot(EquipmentSlot.LEGS)))
                .append(",feet=").append(itemName(player.getItemBySlot(EquipmentSlot.FEET)))
                .append("]; free_inventory_slots=").append(freeSlots(player));
        return text.toString();
    }

    static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** Item id -> total count across the hotbar and main inventory. */
    static java.util.Map<String, Integer> counts(Player player) {
        java.util.Map<String, Integer> totals = new java.util.HashMap<>();
        if (player == null) {
            return totals;
        }
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) {
                totals.merge(itemName(stack), stack.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    /** Total count of {@code item} across the hotbar and main inventory. */
    static int count(Player player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Moves an item the player carries into use. Tools and weapons become the selected hotbar item;
     * armor and shields are swapped into their wear slot. The result string starts with
     * {@code EQUIP_SENT}, {@code EQUIP_ALREADY} or {@code EQUIP_FAILED_*}.
     */
    static String equip(Minecraft client, String itemId) {
        var player = client.player;
        var gameMode = client.gameMode;
        if (player == null || client.level == null || gameMode == null) {
            return "EQUIP_FAILED_NO_WORLD: player world is unavailable";
        }
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return "EQUIP_FAILED_UNKNOWN_ITEM: " + itemId;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (player.containerMenu != player.inventoryMenu) {
            return "EQUIP_FAILED_CONTAINER_OPEN: a container screen is open; wait until it closes";
        }
        int wearSlot = EquipRules.armorSlot(itemId);
        if (wearSlot != EquipRules.NOT_ARMOR && wornItem(player, wearSlot).is(item)) {
            return "EQUIP_ALREADY: " + itemId + " is already worn on the " + EquipRules.slotName(wearSlot);
        }
        if (wearSlot == EquipRules.NOT_ARMOR && player.getMainHandItem().is(item)) {
            return "EQUIP_ALREADY: " + itemId + " is already in hand";
        }

        int hotbarSlot = hotbarSlotOf(player, item);
        int selected = player.getInventory().getSelectedSlot();
        if (hotbarSlot < 0) {
            int source = mainInventorySlotOf(player, item);
            if (source < 0) {
                return "EQUIP_FAILED_NOT_IN_INVENTORY: " + itemId
                        + " is not in the inventory; it cannot be crafted, so gather or find it first";
            }
            gameMode.handleContainerInput(source, selected, player.inventoryMenu.containerId,
                    ContainerInput.SWAP, player);
            hotbarSlot = selected;
        }

        if (wearSlot == EquipRules.NOT_ARMOR) {
            player.getInventory().setSelectedSlot(hotbarSlot);
            return "EQUIP_SENT: holding " + itemId + " from hotbar slot " + hotbarSlot;
        }
        int menuSlot = wearSlot == EquipRules.OFFHAND
                ? InventoryMenu.SHIELD_SLOT
                : InventoryMenu.ARMOR_SLOT_START + wearSlot;
        gameMode.handleContainerInput(menuSlot, hotbarSlot, player.inventoryMenu.containerId,
                ContainerInput.SWAP, player);
        return "EQUIP_SENT: wearing " + itemId + " on the " + EquipRules.slotName(wearSlot);
    }

    private static ItemStack wornItem(Player player, int wearSlot) {
        return switch (wearSlot) {
            case EquipRules.HEAD -> player.getItemBySlot(EquipmentSlot.HEAD);
            case EquipRules.CHEST -> player.getItemBySlot(EquipmentSlot.CHEST);
            case EquipRules.LEGS -> player.getItemBySlot(EquipmentSlot.LEGS);
            case EquipRules.FEET -> player.getItemBySlot(EquipmentSlot.FEET);
            default -> player.getOffhandItem();
        };
    }

    private static int hotbarSlotOf(Player player, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static int mainInventorySlotOf(Player player, Item item) {
        for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.INV_SLOT_END; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static String itemName(ItemStack stack) {
        return stack.isEmpty() ? "none"
                : java.util.Objects.requireNonNull(BuiltInRegistries.ITEM.getKey(stack.getItem())).toString();
    }
}
