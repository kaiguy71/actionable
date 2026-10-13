package dev.actionable;

import java.util.Locale;

/** Decides where an item belongs on the player. Pure text, so it can be tested without the game. */
final class EquipRules {
    /** Wear-slot codes: 0-3 are offsets from {@code InventoryMenu.ARMOR_SLOT_START}, 4 is the shield slot. */
    static final int HEAD = 0;
    static final int CHEST = 1;
    static final int LEGS = 2;
    static final int FEET = 3;
    static final int OFFHAND = 4;
    static final int NOT_ARMOR = -1;

    private EquipRules() {
    }

    /** Wear slot for an item id such as {@code minecraft:iron_chestplate}, or {@link #NOT_ARMOR}. */
    static int armorSlot(String itemId) {
        String path = itemId.toLowerCase(Locale.ROOT);
        path = path.substring(path.indexOf(':') + 1);
        if (path.endsWith("_helmet") || path.equals("turtle_helmet")) {
            return HEAD;
        }
        if (path.endsWith("_chestplate") || path.equals("elytra")) {
            return CHEST;
        }
        if (path.endsWith("_leggings")) {
            return LEGS;
        }
        if (path.endsWith("_boots")) {
            return FEET;
        }
        if (path.equals("shield")) {
            return OFFHAND;
        }
        return NOT_ARMOR;
    }

    static String slotName(int armorSlot) {
        return switch (armorSlot) {
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            case FEET -> "feet";
            case OFFHAND -> "offhand";
            default -> "hand";
        };
    }
}
