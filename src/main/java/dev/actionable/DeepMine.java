package dev.actionable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pure rules for the diamond mine: a staircase down to the diamond layer, then a square spiral of
 * level tunnels, with a periodic scan of the loaded blocks for diamond ore. Coordinates are absolute.
 * No Minecraft types, so the geometry and stop rules are unit tested.
 */
final class DeepMine {
    /** Feet height of the tunnels; diamonds are most common at y=-58 to -54. */
    static final int TUNNEL_Y = -58;
    /** Ticks between scans for diamond ore (two seconds). */
    static final int SCAN_INTERVAL = 40;
    /** Scan reach: horizontal blocks either side, and vertical blocks above and below the player. */
    static final int SCAN_RADIUS = 32;
    static final int SCAN_HEIGHT = 16;
    /** Ore farther than this (straight line) is left until the tunnel brings it closer. */
    static final double APPROACH_DISTANCE = 40;
    /** Level tunnel cells dug in the first leg; later legs grow so each spiral ring covers new ground. */
    static final int FIRST_LEG = 16;
    static final int MAX_LEG = 64;
    /** Cells dug in all before the mine gives up finding diamonds. */
    static final int MAX_DUG = 600;
    /** Times Baritone may be sent after the same ore before it is skipped. */
    static final int ORE_ATTEMPTS = 3;

    /** The four horizontal headings, clockwise from east. */
    private static final int[][] HEADINGS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private DeepMine() {
    }

    /** What a block is, as far as digging and scanning are concerned. */
    enum Cell { AIR, SOLID, DIAMOND, DEEPSLATE_DIAMOND, WATER, LAVA, UNLOADED, UNSAFE }

    interface Reader {
        Cell at(int x, int y, int z);
    }

    enum Check { OK, WATER, LAVA, NO_FLOOR, UNLOADED, UNSAFE }

    record Ore(int x, int y, int z, boolean deepslate) {
        String block() {
            return deepslate ? "minecraft:deepslate_diamond_ore" : "minecraft:diamond_ore";
        }

        double distanceFrom(int px, int py, int pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    static int[] heading(int index) {
        return HEADINGS[Math.floorMod(index, HEADINGS.length)];
    }

    /** Cells in one level leg: 16, 16, 32, 32, 48, 48, 64, 64, then 64 for ever (square spiral). */
    static int legLength(int leg) {
        return Math.min(MAX_LEG, FIRST_LEG * (1 + leg / 2));
    }

    /** Where the player's feet go next: one block ahead, and one lower while still descending. */
    static int[] nextFeet(int[] feet, int heading, boolean descending) {
        int[] step = heading(heading);
        return new int[] {feet[0] + step[0], descending ? feet[1] - 1 : feet[1], feet[2] + step[1]};
    }

    static boolean touchesHome(int[] home, int[] feet, boolean descending) {
        return home != null && cells(feet, descending).stream()
                .anyMatch(cell -> HouseDesign.nearHouse(home, cell[0], cell[1], cell[2]));
    }

    /**
     * The cells cleared to stand at {@code feet}: the stairs are three tall so the player can walk down
     * without bumping their head, the level tunnel two. Top first so nothing falls on the player.
     */
    static List<int[]> cells(int[] feet, boolean descending) {
        List<int[]> cells = new ArrayList<>();
        for (int dy = descending ? 2 : 1; dy >= 0; dy--) {
            cells.add(new int[] {feet[0], feet[1] + dy, feet[2]});
        }
        return cells;
    }

    /**
     * Checks the cell about to be dug and every block touching it. Lava or water beside, above or
     * below a block that is about to go would flow in, so either ends the dig in that direction. A
     * cell without solid ground under it is a drop the player would fall into.
     */
    static Check check(Reader reader, int[] feet, boolean descending) {
        Check result = Check.OK;
        for (int[] cell : cells(feet, descending)) {
            for (int[] offset : NEIGHBOURS) {
                Cell found = reader.at(cell[0] + offset[0], cell[1] + offset[1], cell[2] + offset[2]);
                if (found == Cell.LAVA) {
                    return Check.LAVA;
                }
                if (found == Cell.UNLOADED || found == Cell.UNSAFE) {
                    return found == Cell.UNLOADED ? Check.UNLOADED : Check.UNSAFE;
                }
                if (found == Cell.WATER) {
                    result = Check.WATER;
                }
            }
        }
        if (result == Check.OK) {
            Cell floor = reader.at(feet[0], feet[1] - 1, feet[2]);
            if (floor != Cell.SOLID && floor != Cell.DIAMOND && floor != Cell.DEEPSLATE_DIAMOND) {
                return Check.NO_FLOOR;
            }
        }
        return result;
    }

    private static final int[][] NEIGHBOURS = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}};

    /**
     * The nearest diamond ore in the scan box around the player that has not been given up on.
     * The box is {@link #SCAN_RADIUS} either side and {@link #SCAN_HEIGHT} above and below.
     */
    static Optional<Ore> nearestOre(Reader reader, int px, int py, int pz, Set<List<Integer>> skipped, int minY,
            int maxY) {
        Ore best = null;
        double bestDistance = Double.MAX_VALUE;
        int low = Math.max(minY, py - SCAN_HEIGHT);
        int high = Math.min(maxY - 1, py + SCAN_HEIGHT);
        for (int x = px - SCAN_RADIUS; x <= px + SCAN_RADIUS; x++) {
            for (int z = pz - SCAN_RADIUS; z <= pz + SCAN_RADIUS; z++) {
                for (int y = low; y <= high; y++) {
                    Cell cell = reader.at(x, y, z);
                    if ((cell != Cell.DIAMOND && cell != Cell.DEEPSLATE_DIAMOND) || skipped.contains(key(x, y, z))) {
                        continue;
                    }
                    Ore ore = new Ore(x, y, z, cell == Cell.DEEPSLATE_DIAMOND);
                    double distance = ore.distanceFrom(px, py, pz);
                    if (distance < bestDistance) {
                        best = ore;
                        bestDistance = distance;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    static List<Integer> key(int x, int y, int z) {
        return List.of(x, y, z);
    }

    /** True for the block ids and items a diamond mine is for. */
    static boolean isDiamondTarget(String itemOrBlock) {
        return itemOrBlock.equals("minecraft:diamond") || itemOrBlock.equals("minecraft:diamond_ore")
                || itemOrBlock.equals("minecraft:deepslate_diamond_ore");
    }
}
