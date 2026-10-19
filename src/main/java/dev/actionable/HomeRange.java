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
    /** Wandering targets sit this far from home, inside the radius. */
    static final int WANDER_DISTANCE = 40;

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

    /**
     * A point to walk to instead of Baritone's unbounded explore: a different compass bearing each
     * attempt, {@link #WANDER_DISTANCE} blocks from home.
     */
    static int[] wanderTarget(int[] home, int attempt) {
        double angle = Math.toRadians(attempt * 137.5);
        return new int[] {home[0] + (int) Math.round(Math.cos(angle) * WANDER_DISTANCE), home[1],
                home[2] + (int) Math.round(Math.sin(angle) * WANDER_DISTANCE)};
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
