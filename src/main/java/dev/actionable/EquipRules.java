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

    /** Selects wearable upgrades; preserves worn gold in the Nether and never replaces an elytra. */
    static java.util.List<String> upgrades(java.util.Map<String, Integer> inventory,
            java.util.Map<Integer, String> worn, boolean inNether) {
        var upgrades = new java.util.ArrayList<String>();
        boolean wearingGold = worn.values().stream().anyMatch(item -> item.startsWith("minecraft:golden_"));
        for (int slot = HEAD; slot <= FEET; slot++) {
            String current = worn.getOrDefault(slot, "");
            if (current.equals("minecraft:elytra") || inNether && current.startsWith("minecraft:golden_")) continue;
            if (inNether && !wearingGold && slot == FEET && inventory.getOrDefault("minecraft:golden_boots", 0) > 0) {
                upgrades.add("minecraft:golden_boots");
                continue;
            }
            final int targetSlot = slot;
            inventory.entrySet().stream().filter(e -> e.getValue() > 0 && armorSlot(e.getKey()) == targetSlot
                    && armorRank(e.getKey()) > armorRank(current))
                    .max(java.util.Comparator.comparingInt(e -> armorRank(e.getKey())))
                    .ifPresent(e -> upgrades.add(e.getKey()));
        }
        if (worn.getOrDefault(OFFHAND, "").isEmpty() && inventory.getOrDefault("minecraft:shield", 0) > 0)
            upgrades.add("minecraft:shield");
        return java.util.List.copyOf(upgrades);
    }

    private static int armorRank(String item) {
        if (!item.startsWith("minecraft:")) return 0;
        return switch (item.substring("minecraft:".length()).split("_", 2)[0]) {
            case "netherite" -> 6;
            case "diamond" -> 5;
            case "iron" -> 4;
            case "chainmail", "turtle" -> 3;
            case "golden" -> 2;
            case "leather" -> 1;
            default -> 0;
        };
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
