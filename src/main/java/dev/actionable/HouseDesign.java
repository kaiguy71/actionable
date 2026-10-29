package dev.actionable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * The starter house: a 5x5 plank hut, three blocks of wall under a flat roof, with a two-high doorway
 * in the north wall (z=0). Baritone builds it from a Sponge schematic generated here, so no schematic
 * file has to be shipped. Coordinates are relative to the house's north-west bottom corner.
 */
final class HouseDesign {
    static final int WIDTH = 5;
    static final int HEIGHT = 4;
    static final int LENGTH = 5;
    /** The schematic starts one level below the house so the floor is built with it. */
    static final int SCHEMATIC_HEIGHT = HEIGHT + 1;
    static final String SCHEMATIC_FILE = "actionable_house.schem";
    /*
     * Interior layout, looking down (x 1-3, z 1-3; doorway at x=2 in the north wall z=0):
     *            x=1         x=2       x=3
     *   z=1   bed stand    entry     stair top
     *   z=2   bed foot     furnace   stair
     *   z=3   bed head     table     stair
     * The entry is where the player stands to use the table, furnace and bed; the east column is the
     * staircase mine, which runs south under the wall and down.
     */
    /** Where the player stands to place the bed, which then faces south so its head is at z=3. */
    static final int[] BED_STAND = {1, 0, 1};
    static final int[] BED_FOOT = {1, 0, 2};
    /** Lower half of the door, in the doorway. */
    static final int[] DOOR_CELL = {2, 0, 0};
    /** Just inside the doorway: the door is placed from here, and the table, furnace and bed are used from here. */
    static final int[] DOOR_STAND = {2, 0, 1};
    static final int[] TABLE_CELL = {2, 0, 3};
    static final int[] FURNACE_CELL = {2, 0, 2};
    /** Top of the staircase mine, at floor level. */
    static final int[] STAIR_TOP = {3, 0, 1};

    private HouseDesign() {
    }

    /**
     * Cells one below the floor level that must be solid so the player can walk in: the interior, the
     * doorway and the step just outside it. Holes or water there get a plank.
     */
    static java.util.List<int[]> floorCells() {
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        cells.add(new int[] {DOOR_CELL[0], -1, DOOR_CELL[2] - 1});
        cells.add(new int[] {DOOR_CELL[0], -1, DOOR_CELL[2]});
        for (int z = 1; z < LENGTH - 1; z++) {
            for (int x = 1; x < WIDTH - 1; x++) {
                // The staircase digs through the floor of its own column; those holes are meant to be there.
                if (!StairMine.isStairCell(x, -1, z)) {
                    cells.add(new int[] {x, -1, z});
                }
            }
        }
        return cells;
    }

    /** Interior airspace and doorway that must not hold fluid. Furniture remains allowed. */
    static java.util.List<int[]> dryCells() {
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        for (int y = 0; y < HEIGHT - 1; y++) {
            if (y <= 1) cells.add(new int[] {DOOR_CELL[0], y, DOOR_CELL[2]});
            for (int z = 1; z < LENGTH - 1; z++) {
                for (int x = 1; x < WIDTH - 1; x++) {
                    cells.add(new int[] {x, y, z});
                }
            }
        }
        return cells;
    }

    /**
     * True for any point in or right around the house, from just below the floor to the roof. The roof
     * blocks the sky, so without this the cave-safety check thought the player was underground at home.
     */
    static boolean nearHouse(int[] origin, int x, int y, int z) {
        if (origin == null) {
            return false;
        }
        int dx = x - origin[0];
        int dy = y - origin[1];
        int dz = z - origin[2];
        return dx >= -1 && dx <= WIDTH && dz >= -2 && dz <= LENGTH && dy >= -2 && dy <= HEIGHT;
    }

    /** True for cells that must hold a plank; every other cell in the box must be air. */
    static boolean solid(int x, int y, int z) {
        if (y == HEIGHT - 1) {
            return true;
        }
        boolean wall = x == 0 || x == WIDTH - 1 || z == 0 || z == LENGTH - 1;
        boolean doorway = x == WIDTH / 2 && z == 0 && y <= 1;
        return wall && !doorway;
    }

    /** True for the floor planks the schematic lays one level below the house (y = -1). */
    static boolean floorPlank(int x, int z) {
        return x >= 0 && x < WIDTH && z >= 0 && z < LENGTH
                && floorCells().stream().anyMatch(cell -> cell[0] == x && cell[2] == z);
    }

    static int planksNeeded() {
        int count = 0;
        for (int z = 0; z < LENGTH; z++) {
            for (int x = 0; x < WIDTH; x++) {
                if (floorPlank(x, z)) {
                    count++;
                }
            }
        }
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < LENGTH; z++) {
                for (int x = 0; x < WIDTH; x++) {
                    if (solid(x, y, z)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Checks a solid-block test at relative coordinates. */
    interface Probe {
        boolean solidAt(int x, int y, int z);
    }

    /** Progress display only; structural completion is checked separately by HouseStructure. */
    static double completion(Probe probe) {
        int filled = 0;
        int total = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < LENGTH; z++) {
                for (int x = 0; x < WIDTH; x++) {
                    if (solid(x, y, z)) {
                        total++;
                        if (probe.solidAt(x, y, z)) {
                            filled++;
                        }
                    }
                }
            }
        }
        return (double) filled / total;
    }

    /** True when a point is inside the house's walls (interior cells only). */
    static boolean inside(int[] origin, int x, int y, int z) {
        int dx = x - origin[0];
        int dy = y - origin[1];
        int dz = z - origin[2];
        return dx >= 1 && dx <= WIDTH - 2 && dz >= 1 && dz <= LENGTH - 2 && dy >= 0 && dy < HEIGHT - 1;
    }

    /**
     * Gzipped Sponge schematic (version 2) of the house built from {@code planks}. Baritone 1.20
     * accepts versions 1 and 2 with the fields at the root compound.
     */
    static byte[] schematic(String planks, int dataVersion) {
        byte[] blocks = new byte[WIDTH * SCHEMATIC_HEIGHT * LENGTH];
        for (int row = 0; row < SCHEMATIC_HEIGHT; row++) {
            int y = row - 1;
            for (int z = 0; z < LENGTH; z++) {
                for (int x = 0; x < WIDTH; x++) {
                    // Palette 0 is air, 1 is planks; both fit in a single varint byte.
                    boolean plank = y < 0 ? floorPlank(x, z) : solid(x, y, z);
                    blocks[(row * LENGTH + z) * WIDTH + x] = (byte) (plank ? 1 : 0);
                }
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            name(out, "Schematic");
            intTag(out, "Version", 2);
            intTag(out, "DataVersion", dataVersion);
            shortTag(out, "Width", WIDTH);
            shortTag(out, "Height", SCHEMATIC_HEIGHT);
            shortTag(out, "Length", LENGTH);
            intTag(out, "PaletteMax", 2);
            out.writeByte(10);
            name(out, "Palette");
            intTag(out, "minecraft:air", 0);
            intTag(out, planks, 1);
            out.writeByte(0);
            out.writeByte(7);
            name(out, "BlockData");
            out.writeInt(blocks.length);
            out.write(blocks);
            out.writeByte(11);
            name(out, "Offset");
            out.writeInt(3);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeByte(0);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    private static void name(DataOutputStream out, String name) throws IOException {
        byte[] utf = name.getBytes(StandardCharsets.UTF_8);
        out.writeShort(utf.length);
        out.write(utf);
    }

    private static void intTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        name(out, name);
        out.writeInt(value);
    }

    private static void shortTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(2);
        name(out, name);
        out.writeShort(value);
    }
}
