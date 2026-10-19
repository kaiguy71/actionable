package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class HouseAndHomeTest {
    @Test
    void houseIsAWalledRoofedHutWithADoorway() {
        assertEquals(71, HouseDesign.planksNeeded());
        assertTrue(HouseDesign.solid(0, 0, 0), "corner wall");
        assertTrue(HouseDesign.solid(2, 3, 2), "roof");
        assertFalse(HouseDesign.solid(2, 1, 2), "interior is open");
        assertFalse(HouseDesign.solid(2, 0, 0), "doorway bottom");
        assertFalse(HouseDesign.solid(2, 1, 0), "doorway top");
        assertTrue(HouseDesign.solid(2, 2, 0), "wall above the doorway");
    }

    @Test
    void bedCellsAreInsideTheHouse() {
        int[] origin = {10, 64, 10};
        int[] foot = HouseDesign.BED_FOOT;
        int[] stand = HouseDesign.BED_STAND;
        assertTrue(HouseDesign.inside(origin, origin[0] + foot[0], origin[1] + foot[1], origin[2] + foot[2]));
        assertTrue(HouseDesign.inside(origin, origin[0] + stand[0], origin[1] + stand[1], origin[2] + stand[2]));
        // Placed facing south from the stand cell, the bed head lands one block south of the foot.
        assertEquals(foot[2] + 1 - stand[2], 2);
        assertTrue(HouseDesign.inside(origin, origin[0] + foot[0], origin[1], origin[2] + foot[2] + 1));
        assertFalse(HouseDesign.inside(origin, origin[0], origin[1], origin[2]), "walls are not interior");
    }

    @Test
    void furnitureCellsAreInsideAndDoNotOverlap() {
        int[] origin = {0, 64, 0};
        java.util.Set<String> used = new java.util.HashSet<>();
        int[] bedHead = {HouseDesign.BED_FOOT[0], 0, HouseDesign.BED_FOOT[2] + 1};
        for (int[] cell : new int[][] {HouseDesign.BED_STAND, HouseDesign.BED_FOOT, bedHead, HouseDesign.TABLE_CELL,
                HouseDesign.FURNACE_CELL, HouseDesign.DOOR_STAND, HouseDesign.STAIR_TOP}) {
            assertTrue(HouseDesign.inside(origin, cell[0], 64 + cell[1], cell[2]), java.util.Arrays.toString(cell));
            assertTrue(used.add(java.util.Arrays.toString(cell)), "overlap at " + java.util.Arrays.toString(cell));
        }
        assertFalse(HouseDesign.solid(HouseDesign.DOOR_CELL[0], 0, HouseDesign.DOOR_CELL[2]), "door goes in the doorway");
        for (int[] used2 : new int[][] {HouseDesign.TABLE_CELL, HouseDesign.FURNACE_CELL, HouseDesign.BED_FOOT}) {
            assertTrue(MiningPolicy.inReach(HouseDesign.DOOR_STAND[0], 64, HouseDesign.DOOR_STAND[2], used2[0], 64, used2[2]),
                    "reachable from just inside the door: " + java.util.Arrays.toString(used2));
        }
        for (int[] cell : new int[][] {HouseDesign.BED_FOOT, bedHead, HouseDesign.TABLE_CELL, HouseDesign.FURNACE_CELL}) {
            for (int k = 1; k <= 3; k++) {
                for (int[] stair : StairMine.cells(k)) {
                    assertFalse(java.util.Arrays.equals(stair, cell), "stairs dig through furniture at " + k);
                }
            }
        }
    }

    @Test
    void floorCoversTheInteriorDoorwayAndStepOutside() {
        java.util.List<int[]> floor = HouseDesign.floorCells();
        assertEquals(9, floor.size(), "the staircase column's floor is dug on purpose");
        assertTrue(floor.stream().allMatch(cell -> cell[1] == -1), "floor cells are one below floor level");
        assertTrue(floor.stream().anyMatch(cell -> cell[0] == 2 && cell[2] == -1), "the step outside the door");
        assertTrue(floor.stream().anyMatch(cell -> cell[0] == 2 && cell[2] == 0), "under the doorway");
        assertEquals(20, HouseDesign.dryCells().size());
    }

    @Test
    void theHouseIsNotACave() {
        int[] home = {38, 63, -8};
        // The exact spot from the loop: in the flooded dip under the doorway.
        assertTrue(HouseDesign.nearHouse(home, 40, 62, -8));
        assertTrue(HouseDesign.nearHouse(home, 41, 63, -6), "inside");
        assertTrue(HouseDesign.nearHouse(home, 40, 63, -10), "just outside the door");
        assertFalse(HouseDesign.nearHouse(home, 50, 63, -8));
        assertFalse(HouseDesign.nearHouse(home, 40, 50, -8), "a real cave below the house is still a cave");
        assertFalse(HouseDesign.nearHouse(null, 40, 62, -8));
    }

    @Test
    void completionCountsFilledWallAndRoofCells() {
        assertEquals(1.0, HouseDesign.completion((x, y, z) -> true));
        assertEquals(0.0, HouseDesign.completion((x, y, z) -> false));
        double wallsOnly = HouseDesign.completion((x, y, z) -> y < HouseDesign.HEIGHT - 1);
        assertTrue(wallsOnly < HouseDesign.COMPLETE, "a house without a roof is not done");
    }

    @Test
    void schematicIsAValidSpongeV2File() throws IOException {
        Map<String, Object> root = readNbt(HouseDesign.schematic("minecraft:spruce_planks", 4440));
        assertEquals(2, root.get("Version"));
        assertEquals(4440, root.get("DataVersion"));
        assertEquals((short) 5, root.get("Width"));
        assertEquals((short) 4, root.get("Height"));
        assertEquals((short) 5, root.get("Length"));
        @SuppressWarnings("unchecked")
        Map<String, Object> palette = (Map<String, Object>) root.get("Palette");
        assertEquals(Map.of("minecraft:air", 0, "minecraft:spruce_planks", 1), palette);
        byte[] blocks = (byte[]) root.get("BlockData");
        assertEquals(100, blocks.length);
        int planks = 0;
        for (byte block : blocks) {
            planks += block;
        }
        assertEquals(HouseDesign.planksNeeded(), planks);
        // Sponge order is (y * length + z) * width + x: the doorway at x=2, z=0 is air on the ground.
        assertEquals(0, blocks[2]);
        assertEquals(1, blocks[0]);
        assertArrayEquals(new int[] {0, 0, 0}, (int[]) root.get("Offset"));
    }

    @Test
    void gearMeansAStonePickaxeAndAStoneSword() {
        assertFalse(HomeRange.geared(Map.of()));
        assertFalse(HomeRange.geared(Map.of("minecraft:stone_pickaxe", 1, "minecraft:wooden_sword", 1)));
        assertFalse(HomeRange.geared(Map.of("minecraft:stone_sword", 1, "minecraft:wooden_pickaxe", 1)));
        assertTrue(HomeRange.geared(Map.of("minecraft:stone_pickaxe", 1, "minecraft:stone_sword", 1)));
        assertTrue(HomeRange.geared(Map.of("minecraft:iron_pickaxe", 1, "minecraft:iron_sword", 1)));
    }

    @Test
    void ungearedPlayersStayNearHome() {
        int[] home = {0, 64, 0};
        assertFalse(HomeRange.outside(home, false, 60, 0, 0));
        assertTrue(HomeRange.outside(home, false, 70, 0, 0));
        assertFalse(HomeRange.outside(home, false, 70, 0, HomeRange.LEASH_SLACK), "slack for chasing sheep");
        assertFalse(HomeRange.outside(home, true, 500, 500, 0), "geared players roam freely");
        assertFalse(HomeRange.outside(null, false, 500, 500, 0), "no home yet, no range");
    }

    @Test
    void searchSweepsARingAroundHomeInsteadOfCrissCrossing() {
        int[] home = {100, 70, -50};
        for (int attempt = 0; attempt < 7; attempt++) {
            int[] here = HomeRange.searchTarget(home, attempt, HomeRange.HUNT_RANGE);
            int[] next = HomeRange.searchTarget(home, attempt + 1, HomeRange.HUNT_RANGE);
            double hop = Math.hypot(next[0] - here[0], next[2] - here[2]);
            assertTrue(hop < 30, "neighbouring compass points, not across home: " + hop);
            assertEquals(32, HomeRange.distance(home, here[0], here[2]), 1.0, "first ring");
        }
        assertEquals(56, HomeRange.distance(home, HomeRange.searchTarget(home, 8, 96)[0],
                HomeRange.searchTarget(home, 8, 96)[2]), 1.0, "then the next ring out");
        for (int attempt = 0; attempt < 40; attempt++) {
            int[] near = HomeRange.searchTarget(home, attempt, HomeRange.RADIUS);
            assertFalse(HomeRange.outside(home, false, near[0], near[2], 0), "log and explore searches stay in range");
            int[] far = HomeRange.searchTarget(home, attempt, HomeRange.HUNT_RANGE);
            assertTrue(HomeRange.distance(home, far[0], far[2]) <= HomeRange.HUNT_RANGE);
            assertEquals(home[1], far[1]);
        }
    }

    @Test
    void searchWalksNameOnlyTheColumnSoBaritoneNeverDigsDown() {
        int[] target = HomeRange.searchTarget(new int[] {38, 63, -8}, 0, HomeRange.HUNT_RANGE);
        assertEquals("goto " + target[0] + " " + target[2], HomeRange.surfaceGoto(target));
        assertEquals(3, HomeRange.surfaceGoto(target).split(" ").length, "no height: Baritone picks the ground level");
    }

    @Test
    void describeTellsTheModelTheRule() {
        assertTrue(HomeRange.describe(new int[] {1, 2, 3}, false, true)
                .contains("stay within 64 blocks of home until you have a stone pickaxe and a stone sword"));
        assertTrue(HomeRange.describe(null, false, false).contains("none yet"));
    }

    @Test
    void bedRecipeAndWoolAreKnown() {
        TechTree.Recipe bed = TechTree.recipeFor(TechTree.WHITE_BED).orElseThrow();
        assertEquals(Map.of(TechTree.WHITE_WOOL, 3, TechTree.PLANKS, 3), bed.inputs());
        assertTrue(bed.needsTable());
        assertEquals("minecraft:sheep", TechTree.huntedFrom(TechTree.WHITE_WOOL).orElseThrow());
        assertTrue(TechTree.useOf(TechTree.WHITE_WOOL).orElseThrow().contains("bed"));
    }

    /** Minimal NBT reader for the tags the schematic uses. */
    private static Map<String, Object> readNbt(byte[] gzipped) throws IOException {
        DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gzipped)));
        assertEquals(10, in.readByte());
        assertEquals("Schematic", in.readUTF());
        return readCompound(in);
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> out = new HashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == 0) {
                return out;
            }
            String name = in.readUTF();
            out.put(name, switch (type) {
                case 2 -> in.readShort();
                case 3 -> in.readInt();
                case 7 -> {
                    byte[] data = new byte[in.readInt()];
                    in.readFully(data);
                    yield data;
                }
                case 10 -> readCompound(in);
                case 11 -> {
                    int[] data = new int[in.readInt()];
                    for (int i = 0; i < data.length; i++) {
                        data[i] = in.readInt();
                    }
                    yield data;
                }
                default -> throw new IOException("unexpected tag " + type);
            });
        }
    }
}
