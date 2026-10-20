package dev.actionable;

import java.util.Map;

/** Home location and equipment facts; travel has no fixed distance limit. */
final class HomeRange {
    private HomeRange() {
    }

    static boolean geared(Map<String, Integer> inventory) {
        boolean pickaxe = TechTree.pickaxeTier(inventory).compareTo(TechTree.Tier.STONE) >= 0;
        boolean sword = inventory.entrySet().stream().anyMatch(entry -> entry.getValue() > 0
                && entry.getKey().endsWith("_sword") && !entry.getKey().equals("minecraft:wooden_sword")
                && !entry.getKey().equals("minecraft:golden_sword"));
        return pickaxe && sword;
    }

    static double distance(int[] home, int x, int z) {
        double dx = x - home[0];
        double dz = z - home[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Baritone goto for a search spot that names only the column. A fixed height (home's floor level)
     * was buried under any hill, and Baritone tunnelled underground to reach it.
     */
    static String surfaceGoto(int[] target) {
        return "goto " + target[0] + " " + target[2];
    }

    static String describe(int[] home, boolean geared, boolean houseBuilt) {
        if (home == null) {
            return "home=none yet (the first house becomes home)";
        }
        return "home=" + home[0] + "," + home[1] + "," + home[2] + (houseBuilt ? " (house built)" : " (start area)")
                + "; no fixed travel radius";
    }
}
