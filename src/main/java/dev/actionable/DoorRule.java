package dev.actionable;

/**
 * Where a door can hang: a 1-wide, 2-tall opening (empty, or already holding a door) with solid
 * blocks on both sides at both heights and solid ground below. The model placed three doors loose
 * inside the house; this rule is checked before any door is placed.
 */
final class DoorRule {
    static final String RULE = "a door needs a 1-wide, 2-tall opening with solid blocks on both sides at both"
            + " heights and solid ground below, like the house doorway";

    private DoorRule() {
    }

    enum Cell { AIR, DOOR, SOLID, OTHER }

    interface Probe {
        Cell at(int x, int y, int z);
    }

    static boolean fits(Probe probe, int x, int y, int z) {
        boolean open = free(probe.at(x, y, z)) && free(probe.at(x, y + 1, z));
        if (!open || probe.at(x, y - 1, z) != Cell.SOLID) {
            return false;
        }
        return sides(probe, x, y, z, 1, 0) || sides(probe, x, y, z, 0, 1);
    }

    private static boolean free(Cell cell) {
        return cell == Cell.AIR || cell == Cell.DOOR;
    }

    private static boolean sides(Probe probe, int x, int y, int z, int dx, int dz) {
        for (int dy = 0; dy <= 1; dy++) {
            if (probe.at(x - dx, y + dy, z - dz) != Cell.SOLID || probe.at(x + dx, y + dy, z + dz) != Cell.SOLID) {
                return false;
            }
        }
        return true;
    }
}
