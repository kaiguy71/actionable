package dev.actionable;

/**
 * Where to look for animals when none are loaded. The game already loads everything within roughly
 * 100 blocks of the player, so searching near home finds nothing new: each expedition leg goes far out
 * from home in one compass direction, the next leg in the neighbouring direction, then farther out.
 */
final class HuntSearch {
    static final int FIRST_LEG = 160;
    static final int LEG_STEP = 80;
    static final int MAX_LEG = 320;

    private HuntSearch() {
    }

    static int legDistance(int expedition) {
        return Math.min(MAX_LEG, FIRST_LEG + (expedition / 8) * LEG_STEP);
    }

    static int[] leg(int[] home, int expedition) {
        int distance = legDistance(expedition);
        double angle = Math.toRadians((expedition % 8) * 45);
        return new int[] {home[0] + (int) Math.round(Math.sin(angle) * distance), home[1],
                home[2] - (int) Math.round(Math.cos(angle) * distance)};
    }
}
