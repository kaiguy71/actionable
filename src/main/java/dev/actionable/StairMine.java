package dev.actionable;

import java.util.ArrayList;
import java.util.List;

/**
 * The staircase mine under the house: it starts at {@link HouseDesign#STAIR_TOP} and goes down one
 * block for every block south, so the player can walk it both ways. Each step is three blocks tall so
 * the player can walk down without bumping its head. Pure geometry and stop rules; coordinates are
 * relative to the house corner.
 */
final class StairMine {
    /** The mine stops here; iron is common from here up, and deeper is lava country. */
    static final int BOTTOM_Y = 16;

    private StairMine() {
    }

    /** What a block is, as far as digging stairs is concerned. */
    enum Cell { AIR, SOLID, ORE, WATER, LAVA }

    /** Reads a block at house-relative coordinates. */
    interface Probe {
        Cell at(int x, int y, int z);
    }

    enum Check { OK, CAVE, WATER, LAVA }

    /** Where the player's feet are on step {@code k} (step 0 is the top, inside the house). */
    static int[] feet(int k) {
        return new int[] {HouseDesign.STAIR_TOP[0], -k, HouseDesign.STAIR_TOP[2] + k};
    }

    /** The three blocks cleared for step {@code k}, top first so nothing falls on the player. */
    static List<int[]> cells(int k) {
        int[] feet = feet(k);
        return List.of(new int[] {feet[0], feet[1] + 2, feet[2]}, new int[] {feet[0], feet[1] + 1, feet[2]}, feet);
    }

    /** The deepest step allowed for a house whose floor is at {@code homeY}. */
    static int maxStep(int homeY) {
        return Math.max(0, homeY - BOTTOM_Y);
    }

    static boolean isStairCell(int x, int y, int z) {
        if (x != HouseDesign.STAIR_TOP[0]) {
            return false;
        }
        int k = z - HouseDesign.STAIR_TOP[2];
        return k >= 1 && y >= -k && y <= -k + 2;
    }

    /** True when a point (house-relative) is on the stairs or in the house mine's tunnel. */
    static boolean onStairs(int x, int y, int z) {
        int k = z - HouseDesign.STAIR_TOP[2];
        return Math.abs(x - HouseDesign.STAIR_TOP[0]) <= 1 && k >= 0 && y >= -k - 1 && y <= -k + 2;
    }

    /** How many steps are already dug, so a mine continues where the last night left off. */
    static int depth(Probe probe, int maxStep) {
        int k = 0;
        while (k < maxStep) {
            int next = k + 1;
            boolean dug = cells(next).stream().allMatch(cell -> probe.at(cell[0], cell[1], cell[2]) == Cell.AIR);
            if (!dug) {
                break;
            }
            k = next;
        }
        return k;
    }

    /**
     * Checks around a freshly dug step: open air beside, ahead or below that is not part of the stairs
     * is a cave, and any water or lava next to it ends the dig. Cells at or above floor level are the
     * house and the surface, so they never count as a cave.
     */
    static Check check(Probe probe, int k) {
        Check result = Check.OK;
        for (int[] cell : neighbours(k)) {
            Cell found = probe.at(cell[0], cell[1], cell[2]);
            if (found == Cell.LAVA) {
                return Check.LAVA;
            }
            if (found == Cell.WATER) {
                result = Check.WATER;
            } else if (found == Cell.AIR && result == Check.OK && cell[1] < 0) {
                result = Check.CAVE;
            }
        }
        return result;
    }

    /** Ore blocks next to step {@code k}, which are mined on the way down. */
    static List<int[]> ores(Probe probe, int k) {
        List<int[]> ores = new ArrayList<>();
        for (int[] cell : neighbours(k)) {
            if (probe.at(cell[0], cell[1], cell[2]) == Cell.ORE) {
                ores.add(cell);
            }
        }
        return ores;
    }

    private static List<int[]> neighbours(int k) {
        int[] feet = feet(k);
        List<int[]> cells = new ArrayList<>();
        for (int dy = 0; dy <= 2; dy++) {
            int y = feet[1] + dy;
            cells.add(new int[] {feet[0] - 1, y, feet[2]});
            cells.add(new int[] {feet[0] + 1, y, feet[2]});
            cells.add(new int[] {feet[0], y, feet[2] + 1});
        }
        cells.add(new int[] {feet[0], feet[1] - 1, feet[2]});
        cells.removeIf(cell -> isStairCell(cell[0], cell[1], cell[2]) || cell[1] >= 0);
        return cells;
    }
}
