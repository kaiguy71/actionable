package dev.actionable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where things belong in the hotbar. Baritone only uses tools from the hotbar and the player can only
 * eat or swing what is in hand, so the best pickaxe, axe and shovel go first, food in slot 7 and the
 * best weapon in slot 8. Food and weapons sat in the main inventory and could not be used.
 */
final class InventoryLayout {
    static final int PICKAXE_SLOT = 0;
    static final int AXE_SLOT = 1;
    static final int SHOVEL_SLOT = 2;
    static final int FOOD_SLOT = 7;
    static final int WEAPON_SLOT = 8;

    private InventoryLayout() {
    }

    /** Hotbar slot to the item that should be there, for the items this inventory holds. */
    static Map<Integer, String> plan(Map<String, Integer> inventory) {
        Map<Integer, String> plan = new LinkedHashMap<>();
        best(inventory, "_pickaxe").ifPresent(item -> plan.put(PICKAXE_SLOT, item));
        best(inventory, "_axe").ifPresent(item -> plan.put(AXE_SLOT, item));
        best(inventory, "_shovel").ifPresent(item -> plan.put(SHOVEL_SLOT, item));
        FoodPlan.pick(inventory, 20).or(() -> FoodPlan.pick(inventory, 0))
                .ifPresent(item -> plan.put(FOOD_SLOT, item));
        best(inventory, "_sword").ifPresent(item -> plan.put(WEAPON_SLOT, item));
        return plan;
    }

    /** The highest-tier tool of a kind (by suffix) held. */
    static Optional<String> best(Map<String, Integer> inventory, String suffix) {
        String best = null;
        int bestRank = -1;
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            String item = entry.getKey();
            if (entry.getValue() <= 0 || !item.endsWith(suffix) || (suffix.equals("_axe") && item.endsWith("_pickaxe"))) {
                continue;
            }
            int rank = rank(item);
            if (rank > bestRank) {
                best = item;
                bestRank = rank;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int rank(String item) {
        String path = item.substring(item.indexOf(':') + 1);
        if (path.startsWith("netherite_")) {
            return 6;
        }
        if (path.startsWith("diamond_")) {
            return 5;
        }
        if (path.startsWith("iron_")) {
            return 4;
        }
        if (path.startsWith("stone_") || path.startsWith("copper_")) {
            return 3;
        }
        if (path.startsWith("golden_")) {
            return 2;
        }
        return 1;
    }
}
