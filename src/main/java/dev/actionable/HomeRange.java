package dev.actionable;

import java.util.Map;

/**
 * Keeps an unequipped player near home. Until the player holds a stone pickaxe and a stone (or
 * better) sword, goals, mining sites and wandering stay within {@link #RADIUS} blocks of home.
 */
final class HomeRange {
    static final int RADIUS = 64;
    /** Extra distance allowed before the player is walked back, so chasing a sheep is not cut short. */
    static final int LEASH_SLACK = 16;

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

    /** True when a point is outside the allowed range; never true once the player is geared. */
    static boolean outside(int[] home, boolean geared, int x, int z, int slack) {
        return home != null && !geared && distance(home, x, z) > RADIUS + slack;
    }

    /** How far from home sheep and food animals may be chased without gear. */
    static final int HUNT_RANGE = 96;
    private static final int[] SEARCH_RINGS = {32, 56, 80};

    /**
     * A point to look around from: walks a ring around home through neighbouring compass points, then
     * the next ring out. Consecutive spots are next to each other, so the search sweeps instead of
     * criss-crossing back over home (the old random bearings went 60 blocks back and forth).
     */
    static int[] searchTarget(int[] home, int attempt, int maxRadius) {
        int rings = 0;
        while (rings < SEARCH_RINGS.length && SEARCH_RINGS[rings] <= maxRadius) {
            rings++;
        }
        int ring = SEARCH_RINGS[(attempt / 8) % Math.max(1, rings)];
        double angle = Math.toRadians((attempt % 8) * 45);
        return new int[] {home[0] + (int) Math.round(Math.sin(angle) * ring), home[1],
                home[2] - (int) Math.round(Math.cos(angle) * ring)};
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
                + (geared ? "; geared: may travel freely"
                : "; stay within " + RADIUS + " blocks of home until you have a stone pickaxe and a stone sword");
    }
}
