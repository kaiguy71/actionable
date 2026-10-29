package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HouseStructureTest {
    private static final HouseStructure.Cell AIR = new HouseStructure.Cell(true, false, false, true, true);
    private static final HouseStructure.Cell SOLID = new HouseStructure.Cell(true, true, true, true, false);
    private static final HouseStructure.Door CLOSED = new HouseStructure.Door(true, true);
    private static class World implements HouseStructure.Probe {
        final Map<String, HouseStructure.Cell> changes = new HashMap<>();
        void set(int x, int y, int z, HouseStructure.Cell cell) { changes.put(x + "," + y + "," + z, cell); }
        public HouseStructure.Cell at(int x, int y, int z) {
            if (changes.containsKey(x + "," + y + "," + z)) return changes.get(x + "," + y + "," + z);
            boolean floor = HouseDesign.floorCells().stream().anyMatch(p -> p[0] == x && p[1] == y && p[2] == z);
            return floor || y >= 0 && y < HouseDesign.HEIGHT && HouseDesign.solid(x, y, z) ? SOLID : AIR;
        }
        HouseStructure.Result inspect() { return HouseStructure.inspect(this, CLOSED); }
    }

    @Test
    void completeShellFloorAndClosedDoorAreEnclosedWithoutFillingIntentionalStairs() {
        var world = new World();
        var fixture = new HouseStructure.Cell(true, true, true, true, false, true);
        world.set(2, 0, 2, fixture); // Furnace.
        world.set(2, 0, 3, fixture); // Table; interior furniture is not a wall or an error.
        assertEquals(AIR, world.at(3, -1, 2), "planned staircase remains open");
        assertTrue(world.inspect().enclosed());
    }

    @Test
    void exactTenMissingWallsFromClaudeSaveCannotPassDespiteBeingOver85Percent() {
        var world = new World();
        for (int y = 0; y <= 1; y++) {
            world.set(3, y, 0, AIR);
            world.set(4, y, 0, AIR);
            for (int z = 1; z <= 3; z++) world.set(4, y, z, AIR);
        }
        var result = world.inspect();
        assertEquals(10, result.missingShell());
        assertTrue(HouseDesign.completion((x, y, z) -> world.at(x, y, z).barrier()) > 0.85);
        assertFalse(result.shellReady());
        assertFalse(result.enclosed());
    }

    @Test
    void everySingleWallAndRoofCellIsRequired() {
        for (int y = 0; y < HouseDesign.HEIGHT; y++) {
            for (int z = 0; z < HouseDesign.LENGTH; z++) {
                for (int x = 0; x < HouseDesign.WIDTH; x++) {
                    if (!HouseDesign.solid(x, y, z)) continue;
                    var world = new World();
                    world.set(x, y, z, AIR);
                    assertFalse(world.inspect().enclosed(), "gap at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void partialCollisionPlantsLeavesAndFluidsCannotSubstituteForABarrier() {
        for (var nonBarrier : java.util.List.of(new HouseStructure.Cell(true, false, true, true, false),
                new HouseStructure.Cell(true, false, false, true, true),
                new HouseStructure.Cell(true, false, false, false, true))) {
            var world = new World();
            world.set(0, 0, 1, nonBarrier);
            assertFalse(world.inspect().shellReady());
        }
    }

    @Test
    void shellCompletionDoesNotPretendTheFloorDoorOrEntryAreFinished() {
        var world = new World();
        world.set(2, -1, 1, AIR);
        assertTrue(world.inspect().shellReady(), "separate stages avoid a build/floor/door dependency cycle");
        assertFalse(world.inspect().floorReady());
        assertFalse(world.inspect().enclosed());
        world.set(2, -1, 1, SOLID);
        world.set(2, 1, 1, SOLID);
        assertFalse(world.inspect().entryClear());
        assertFalse(world.inspect().enclosed());
    }

    @Test
    void fluidsAnywhereInsideIncludingAboveHeadHeightPreventEnclosure() {
        var world = new World();
        world.set(1, 2, 1, new HouseStructure.Cell(true, false, false, false, true));
        assertFalse(world.inspect().dryInterior());
        assertFalse(world.inspect().enclosed());
    }

    @Test
    void intactShellWithTerrainFillingTheRoomIsNotAnEnclosedUsableHouse() {
        var world = new World();
        world.set(1, 1, 2, SOLID);
        assertTrue(world.inspect().shellReady());
        assertFalse(world.inspect().interiorClear());
        assertFalse(world.inspect().enclosed());
        assertEquals(1, world.inspect().roomRepairs());
        world.set(1, 1, 2, AIR);
        assertEquals(0, world.inspect().roomRepairs(), "clearing terrain must reset the stall watchdog");
    }

    @Test
    void doorwayNeedsACompleteInstalledClosedDoor() {
        var world = new World();
        assertFalse(HouseStructure.inspect(world, new HouseStructure.Door(false, false)).enclosed());
        assertFalse(HouseStructure.inspect(world, new HouseStructure.Door(true, false)).enclosed());
        assertTrue(world.inspect().enclosed());
    }

    @Test
    void unloadedChunksAreUnknownAndDamageAfterCompletionIsObserved() {
        var world = new World();
        assertTrue(world.inspect().enclosed());
        world.set(4, 1, 2, AIR);
        assertFalse(world.inspect().enclosed());
        world.set(4, 1, 2, SOLID);
        assertTrue(world.inspect().enclosed());
        world.set(4, 1, 2, new HouseStructure.Cell(false, false, false, false, false));
        assertFalse(world.inspect().loaded());
        assertEquals("unloaded: structure unknown", world.inspect().reason());
        assertFalse(world.inspect().enclosed());
    }
}
