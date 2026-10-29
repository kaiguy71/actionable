package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.actionable.DeepMine.Cell;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeepMineTest {
    /** A world of solid rock with a few blocks changed. */
    private static DeepMine.Reader world(Map<List<Integer>, Cell> changes) {
        return (x, y, z) -> changes.getOrDefault(List.of(x, y, z), Cell.SOLID);
    }

    private static Map<List<Integer>, Cell> with(int x, int y, int z, Cell cell) {
        Map<List<Integer>, Cell> map = new HashMap<>();
        map.put(List.of(x, y, z), cell);
        return map;
    }

    @Test
    void stairsStepDownOnePerBlockAndTunnelsStayLevel() {
        assertArrayEquals(new int[] {1, 63, 0}, DeepMine.nextFeet(new int[] {0, 64, 0}, 0, true));
        assertArrayEquals(new int[] {0, 63, 1}, DeepMine.nextFeet(new int[] {0, 64, 0}, 1, true));
        assertArrayEquals(new int[] {-1, -58, 0}, DeepMine.nextFeet(new int[] {0, -58, 0}, 2, false));
        assertArrayEquals(new int[] {0, -58, -1}, DeepMine.nextFeet(new int[] {0, -58, 0}, 3, false));
        assertArrayEquals(new int[] {1, 0}, DeepMine.heading(4), "headings wrap around");
        assertArrayEquals(new int[] {0, -1}, DeepMine.heading(-1));
    }

    @Test
    void stairsClearThreeCellsTopFirstAndTunnelsTwo() {
        List<int[]> stairs = DeepMine.cells(new int[] {5, 40, 5}, true);
        assertEquals(3, stairs.size());
        assertArrayEquals(new int[] {5, 42, 5}, stairs.get(0));
        assertArrayEquals(new int[] {5, 40, 5}, stairs.get(2));
        List<int[]> tunnel = DeepMine.cells(new int[] {5, -58, 5}, false);
        assertEquals(2, tunnel.size());
        assertArrayEquals(new int[] {5, -57, 5}, tunnel.get(0));
    }

    @Test
    void solidRockIsSafeToDig() {
        assertEquals(DeepMine.Check.OK, DeepMine.check(world(Map.of()), new int[] {0, 30, 0}, true));
        assertEquals(DeepMine.Check.OK, DeepMine.check(world(Map.of()), new int[] {0, -58, 0}, false));
    }

    @Test
    void lavaOrWaterTouchingTheDigEndsIt() {
        // Lava above the roof, beside a wall and as the floor itself each flow into the dug cell.
        assertEquals(DeepMine.Check.LAVA, DeepMine.check(world(with(0, -56, 0, Cell.LAVA)), new int[] {0, -58, 0}, false));
        assertEquals(DeepMine.Check.LAVA, DeepMine.check(world(with(1, -58, 0, Cell.LAVA)), new int[] {0, -58, 0}, false));
        assertEquals(DeepMine.Check.LAVA, DeepMine.check(world(with(0, -59, 0, Cell.LAVA)), new int[] {0, -58, 0}, false));
        // Lava two blocks down, behind a solid floor block, cannot reach the tunnel.
        assertEquals(DeepMine.Check.OK, DeepMine.check(world(with(0, -60, 0, Cell.LAVA)), new int[] {0, -58, 0}, false));
        assertEquals(DeepMine.Check.WATER, DeepMine.check(world(with(0, -58, 1, Cell.WATER)), new int[] {0, -58, 0}, false));
        Map<List<Integer>, Cell> both = with(0, -58, 1, Cell.WATER);
        both.put(List.of(0, -57, -1), Cell.LAVA);
        assertEquals(DeepMine.Check.LAVA, DeepMine.check(world(both), new int[] {0, -58, 0}, false), "lava wins");
    }

    @Test
    void aDropAheadIsNotWalkedInto() {
        assertEquals(DeepMine.Check.NO_FLOOR, DeepMine.check(world(with(0, -59, 0, Cell.AIR)), new int[] {0, -58, 0}, false));
        // A cave to the side is fine: only the floor under the next cell matters.
        assertEquals(DeepMine.Check.OK, DeepMine.check(world(with(1, -58, 0, Cell.AIR)), new int[] {0, -58, 0}, false));
    }

    @Test
    void scanFindsTheNearestOreInRange() {
        Map<List<Integer>, Cell> ores = with(10, -58, 0, Cell.DEEPSLATE_DIAMOND);
        ores.put(List.of(3, -56, 4), Cell.DIAMOND);
        DeepMine.Ore found = DeepMine.nearestOre(world(ores), 0, -58, 0, Set.of(), -64, 320).orElseThrow();
        assertEquals(3, found.x());
        assertEquals("minecraft:diamond_ore", found.block());
        assertEquals("minecraft:deepslate_diamond_ore", new DeepMine.Ore(1, 2, 3, true).block());
    }

    @Test
    void scanIgnoresOreOutOfRangeAndOreGivenUpOn() {
        assertTrue(DeepMine.nearestOre(world(with(DeepMine.SCAN_RADIUS + 1, -58, 0, Cell.DIAMOND)), 0, -58, 0, Set.of(),
                -64, 320).isEmpty(), "too far sideways");
        assertTrue(DeepMine.nearestOre(world(with(0, -58 + DeepMine.SCAN_HEIGHT + 1, 0, Cell.DIAMOND)), 0, -58, 0,
                Set.of(), -64, 320).isEmpty(), "too far up");
        Set<List<Integer>> skipped = new HashSet<>();
        skipped.add(DeepMine.key(5, -58, 5));
        Map<List<Integer>, Cell> ores = with(5, -58, 5, Cell.DIAMOND);
        assertTrue(DeepMine.nearestOre(world(ores), 0, -58, 0, skipped, -64, 320).isEmpty());
        ores.put(List.of(20, -58, 0), Cell.DIAMOND);
        assertEquals(20, DeepMine.nearestOre(world(ores), 0, -58, 0, skipped, -64, 320).orElseThrow().x());
    }

    @Test
    void scanStaysInsideTheWorldHeight() {
        assertTrue(DeepMine.nearestOre(world(with(0, -70, 0, Cell.DIAMOND)), 0, -62, 0, Set.of(), -64, 320).isEmpty(),
                "below the bottom of the world");
    }

    @Test
    void legsGrowSoTheSpiralCoversNewGround() {
        assertEquals(16, DeepMine.legLength(0));
        assertEquals(16, DeepMine.legLength(1));
        assertEquals(32, DeepMine.legLength(2));
        assertEquals(32, DeepMine.legLength(3));
        assertEquals(DeepMine.MAX_LEG, DeepMine.legLength(100));
        assertFalse(DeepMine.legLength(5) < DeepMine.legLength(4));
    }

    @Test
    void mineNeverDigsTheHouseOrItsFloor() {
        int[] home = {100, 70, 100};
        assertTrue(DeepMine.touchesHome(home, new int[] {104, 69, 101}, true));
        assertTrue(DeepMine.touchesHome(home, new int[] {102, 70, 100}, false));
        assertFalse(DeepMine.touchesHome(home, new int[] {108, 69, 101}, true));
        assertFalse(DeepMine.touchesHome(null, new int[] {0, -58, 0}, false));
    }

    @Test
    void unloadedTerrainAndUnsafeNeighboursAreNeverDugInto() {
        assertEquals(DeepMine.Check.UNLOADED, DeepMine.check(world(with(1, -58, 0, Cell.UNLOADED)),
                new int[] {0, -58, 0}, false));
        assertEquals(DeepMine.Check.UNSAFE, DeepMine.check(world(with(0, -56, 0, Cell.UNSAFE)),
                new int[] {0, -58, 0}, false), "falling blocks above the tunnel are unsafe");
        assertEquals(DeepMine.Check.UNSAFE, DeepMine.check(world(with(0, -59, 0, Cell.UNSAFE)),
                new int[] {0, -58, 0}, false), "damaging ground is unsafe");
    }

    @Test
    void oreScanNeverReadsAboveTheExclusiveWorldCeiling() {
        DeepMine.Reader reader = (x, y, z) -> {
            assertTrue(y >= -64 && y < 320, "outside world height: " + y);
            return Cell.UNLOADED;
        };
        assertTrue(DeepMine.nearestOre(reader, 0, 319, 0, Set.of(), -64, 320).isEmpty());
        assertTrue(DeepMine.nearestOre(reader, 0, -64, 0, Set.of(), -64, 320).isEmpty());
    }

    @Test
    void diamondTargetsAreRecognised() {
        assertTrue(DeepMine.isDiamondTarget("minecraft:diamond"));
        assertTrue(DeepMine.isDiamondTarget("minecraft:deepslate_diamond_ore"));
        assertFalse(DeepMine.isDiamondTarget("minecraft:iron_ore"));
        assertFalse(DeepMine.isDiamondTarget("minecraft:diamond_pickaxe"));
    }
}
