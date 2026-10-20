package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HomeJobsTest {
    /** A simple world: solid stone everywhere except what a test digs or fills. */
    private static final class World implements StairMine.Probe {
        private final Map<String, StairMine.Cell> cells = new HashMap<>();

        void set(int x, int y, int z, StairMine.Cell cell) {
            cells.put(x + "," + y + "," + z, cell);
        }

        void dig(int k) {
            for (int[] cell : StairMine.cells(k)) {
                set(cell[0], cell[1], cell[2], StairMine.Cell.AIR);
            }
        }

        @Override
        public StairMine.Cell at(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, y >= 0 ? StairMine.Cell.AIR : StairMine.Cell.SOLID);
        }
    }

    @Test
    void stairsGoDownOneBlockPerBlockSouth() {
        assertEquals(List.of(3, -1, 2), List.of(StairMine.feet(1)[0], StairMine.feet(1)[1], StairMine.feet(1)[2]));
        assertEquals(List.of(3, -5, 6), List.of(StairMine.feet(5)[0], StairMine.feet(5)[1], StairMine.feet(5)[2]));
        List<int[]> step = StairMine.cells(4);
        assertEquals(3, step.size());
        assertEquals(-2, step.get(0)[1], "top cell first so nothing falls on the player");
        assertEquals(-4, step.get(2)[1]);
        assertEquals(121, StairMine.maxStep(63), "a house at y=63 digs down to just above bedrock");
    }

    @Test
    void depthContinuesWhereTheLastNightStopped() {
        World world = new World();
        assertEquals(0, StairMine.depth(world, 47));
        for (int k = 1; k <= 6; k++) {
            world.dig(k);
        }
        assertEquals(6, StairMine.depth(world, 47));
        assertEquals(4, StairMine.depth(world, 4), "never past the bottom");
    }

    @Test
    void solidRockAroundTheStairsIsSafe() {
        World world = new World();
        for (int k = 1; k <= 5; k++) {
            world.dig(k);
        }
        assertEquals(StairMine.Check.OK, StairMine.check(world, 5));
        assertEquals(StairMine.Check.OK, StairMine.check(world, 1), "the house above the first step is not a cave");
    }

    @Test
    void breakingIntoACaveWaterOrLavaStopsTheDig() {
        World cave = new World();
        cave.dig(1);
        cave.dig(2);
        cave.dig(3);
        cave.set(4, -3, 4, StairMine.Cell.AIR);
        assertEquals(StairMine.Check.CAVE, StairMine.check(cave, 3));
        World wet = new World();
        wet.dig(1);
        wet.dig(2);
        wet.dig(3);
        wet.set(2, -2, 4, StairMine.Cell.WATER);
        assertEquals(StairMine.Check.WATER, StairMine.check(wet, 3));
        wet.set(3, -4, 4, StairMine.Cell.LAVA);
        assertEquals(StairMine.Check.LAVA, StairMine.check(wet, 3), "lava outranks water");
    }

    @Test
    void oreBesideTheStairsIsMinedOnTheWay() {
        World world = new World();
        world.dig(1);
        world.dig(2);
        world.dig(3);
        world.set(2, -3, 4, StairMine.Cell.ORE);
        assertEquals(1, StairMine.ores(world, 3).size());
        assertTrue(StairMine.isStairCell(3, -1, 2));
        assertFalse(StairMine.isStairCell(3, -1, 1), "the floor at the top stays");
        assertTrue(StairMine.onStairs(3, -10, 11));
        assertFalse(StairMine.onStairs(3, -30, 11));
    }

    @Test
    void furnaceUsesCoalFirstAndNeverTheBedPlanks() {
        Optional<FurnacePlan.Load> iron = FurnacePlan.choose(Map.of("minecraft:raw_iron", 5, "minecraft:coal", 2,
                "minecraft:oak_planks", 20));
        assertEquals(new FurnacePlan.Load("minecraft:raw_iron", 5, "minecraft:coal", 1), iron.orElseThrow());
        Optional<FurnacePlan.Load> planks = FurnacePlan.choose(Map.of("minecraft:mutton", 3, "minecraft:oak_planks", 12));
        assertEquals(new FurnacePlan.Load("minecraft:mutton", 3, "minecraft:oak_planks", 2), planks.orElseThrow());
        assertTrue(FurnacePlan.choose(Map.of("minecraft:mutton", 3, "minecraft:oak_planks", 10)).isEmpty(),
                "the last planks are kept for a bed and door");
        assertEquals(new FurnacePlan.Load("minecraft:raw_iron", 3, "minecraft:oak_log", 2),
                FurnacePlan.choose(Map.of("minecraft:raw_iron", 3, "minecraft:oak_log", 2)).orElseThrow());
        assertEquals(new FurnacePlan.Load("minecraft:raw_iron", 1, "minecraft:oak_log", 1),
                FurnacePlan.choose(Map.of("minecraft:raw_iron", 5, "minecraft:oak_log", 1)).orElseThrow(),
                "only as many items as the fuel can smelt");
        assertTrue(FurnacePlan.choose(Map.of("minecraft:oak_log", 5)).isEmpty(), "nothing to smelt");
        assertEquals("minecraft:iron_ingot", FurnacePlan.resultOf("minecraft:raw_iron").orElseThrow());
    }

    private static NeedsPlanner.Situation night(boolean door, boolean table, boolean furnace, boolean mineFinished) {
        return new NeedsPlanner.Situation(new ProgressPlanner.Facts(true, false, table, door, true, true, furnace,
                mineFinished), true, false, true);
    }

    private static String job(Map<String, Integer> inventory, NeedsPlanner.Situation situation) {
        return NeedsPlanner.homeTask(inventory, situation).map(NeedsPlanner.Task::goal).orElse("wait");
    }

    @Test
    void anOpenDoorwayIsFixedFirstIfTheMaterialsAreHome() {
        assertEquals("place door in house",
                job(Map.of("minecraft:oak_planks", 12), night(false, true, false, false)));
        assertEquals("place door in house",
                job(Map.of("minecraft:oak_door", 1), night(false, true, false, false)));
    }

    @Test
    void withNothingElseToDoItMinesCobblestoneAtHome() {
        Map<String, Integer> kit = Map.of("minecraft:wooden_pickaxe", 1, "minecraft:wooden_sword", 1);
        assertEquals(NeedsPlanner.MINE_COBBLE, job(kit, night(true, true, false, false)));
    }

    @Test
    void cobblestoneTurnsIntoStoneToolsThenAFurnaceThenIron() {
        Map<String, Integer> inventory = new HashMap<>(Map.of("minecraft:wooden_pickaxe", 1,
                "minecraft:wooden_sword", 1, "minecraft:cobblestone", 24, "minecraft:stick", 4));
        assertEquals("craft 1 minecraft:stone_pickaxe", job(inventory, night(true, true, false, false)));
        inventory.put("minecraft:stone_pickaxe", 1);
        assertEquals("craft 1 minecraft:stone_sword", job(inventory, night(true, true, false, false)));
        inventory.put("minecraft:stone_sword", 1);
        assertEquals(NeedsPlanner.PLACE_FURNACE, job(inventory, night(true, true, false, false)));
        assertEquals(NeedsPlanner.MINE_IRON, job(inventory, night(true, true, true, false)),
                "with cobblestone, a stone pickaxe and a furnace: dig deeper for iron");
        inventory.put("minecraft:raw_iron", 3);
        inventory.put("minecraft:coal", 1);
        assertEquals(NeedsPlanner.SMELT, job(inventory, night(true, true, true, false)));
    }

    @Test
    void jobsThatNeedTheOutsideAreSkippedAtNight() {
        // No planks for a door and no table: those need trees, so the night goes to the house mine instead.
        Map<String, Integer> kit = Map.of("minecraft:wooden_pickaxe", 1, "minecraft:wooden_sword", 1);
        assertEquals(NeedsPlanner.MINE_COBBLE, job(kit, night(false, false, false, false)));
        assertEquals("wait", job(Map.of(), night(false, false, false, false)), "no tools, no materials: wait inside");
        assertEquals("wait", job(kit, night(true, true, false, true)), "the mine is finished");
        assertFalse(NeedsPlanner.homeStep("mine 3 minecraft:oak_log"));
        assertFalse(NeedsPlanner.homeStep("hunt 3 minecraft:white_wool"));
        assertTrue(NeedsPlanner.homeStep("craft 1 minecraft:stone_pickaxe"));
        assertTrue(NeedsPlanner.homeStep(NeedsPlanner.MINE_COBBLE));
    }

    @Test
    void woolAlreadyInHandMeansMakeTheBedTonight() {
        Map<String, Integer> inventory = Map.of("minecraft:white_wool", 3, "minecraft:oak_planks", 6,
                "minecraft:wooden_pickaxe", 1, "minecraft:wooden_sword", 1);
        assertEquals("place minecraft:white_bed in house", job(inventory, night(true, true, false, false)));
    }

    @Test
    void theReportSaysWhyItCannotSleepAndWhatItIsDoing() {
        Map<String, Integer> kit = Map.of("minecraft:wooden_pickaxe", 1, "minecraft:wooden_sword", 1,
                "minecraft:white_wool", 1);
        String report = NeedsPlanner.report(kit, night(true, true, false, false));
        assertTrue(report.contains("no bed (1/3 white wool), so you cannot sleep"), report);
        assertTrue(report.contains("bed: 2 more white wool"), report);
        assertTrue(report.contains("Best home job now: " + NeedsPlanner.MINE_COBBLE), report);
    }

    @Test
    void houseMineStepsAreJudgedFromInventoryOrAFinishedMine() {
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied(NeedsPlanner.MINE_COBBLE,
                Map.of("minecraft:cobblestone", 24), false, ProgressPlanner.Facts.NONE));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied(NeedsPlanner.MINE_COBBLE,
                Map.of("minecraft:cobblestone", 5), false, ProgressPlanner.Facts.NONE));
        ProgressPlanner.Facts done = new ProgressPlanner.Facts(true, false, true, true, true, true, false, true);
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied(NeedsPlanner.MINE_IRON, Map.of(), false, done));
        assertEquals(List.of(NeedsPlanner.MINE_IRON),
                ProgressPlanner.stepsForGoal(NeedsPlanner.MINE_IRON, Map.of(), false, "minecraft:oak_log"));
        assertEquals(List.of("craft 1 minecraft:furnace", NeedsPlanner.PLACE_FURNACE),
                ProgressPlanner.stepsForGoal(NeedsPlanner.PLACE_FURNACE, Map.of("minecraft:cobblestone", 8),
                        ProgressPlanner.TableAccess.HOME_NEARBY, "minecraft:oak_log"));
    }

    private static DoorRule.Probe world(java.util.Map<String, DoorRule.Cell> cells) {
        return (x, y, z) -> cells.getOrDefault(x + "," + y + "," + z, y < 0 ? DoorRule.Cell.SOLID : DoorRule.Cell.AIR);
    }

    @Test
    void aDoorNeedsAOneByTwoOpeningBetweenSolidSides() {
        // A doorway in a wall running along x: solid at x-1 and x+1 at both heights.
        java.util.Map<String, DoorRule.Cell> wall = new HashMap<>();
        for (int y = 0; y <= 2; y++) {
            wall.put("1," + y + ",0", DoorRule.Cell.SOLID);
            wall.put("3," + y + ",0", DoorRule.Cell.SOLID);
        }
        wall.put("2,2,0", DoorRule.Cell.SOLID);
        assertTrue(DoorRule.fits(world(wall), 2, 0, 0));
        wall.put("2,0,0", DoorRule.Cell.DOOR);
        wall.put("2,1,0", DoorRule.Cell.DOOR);
        assertTrue(DoorRule.fits(world(wall), 2, 0, 0), "an existing door's spot still fits");
    }

    @Test
    void openFloorAndBlockedDoorwaysDoNotFitADoor() {
        assertFalse(DoorRule.fits(world(Map.of()), 2, 0, 2), "the middle of a room has no sides");
        java.util.Map<String, DoorRule.Cell> blocked = new HashMap<>();
        for (int y = 0; y <= 1; y++) {
            blocked.put("1," + y + ",0", DoorRule.Cell.SOLID);
            blocked.put("3," + y + ",0", DoorRule.Cell.SOLID);
        }
        blocked.put("2,1,0", DoorRule.Cell.SOLID);
        assertFalse(DoorRule.fits(world(blocked), 2, 0, 0), "a plank fills the top of the doorway");
        java.util.Map<String, DoorRule.Cell> oneSide = new HashMap<>();
        oneSide.put("1,0,0", DoorRule.Cell.SOLID);
        oneSide.put("1,1,0", DoorRule.Cell.SOLID);
        assertFalse(DoorRule.fits(world(oneSide), 2, 0, 0), "solid on one side only");
        java.util.Map<String, DoorRule.Cell> floating = new HashMap<>();
        floating.put("2,-1,0", DoorRule.Cell.AIR);
        for (int y = 0; y <= 1; y++) {
            floating.put("1," + y + ",0", DoorRule.Cell.SOLID);
            floating.put("3," + y + ",0", DoorRule.Cell.SOLID);
        }
        assertFalse(DoorRule.fits(world(floating), 2, 0, 0), "no ground under it");
    }

    @Test
    void misplacedDoorsAreRehungWithoutCraftingMore() {
        NeedsPlanner.Situation strays = new NeedsPlanner.Situation(new ProgressPlanner.Facts(true, false, true, false,
                true, true, false, false), true, false, true, true);
        assertEquals("place door in house", job(Map.of(), strays), "no planks or doors in hand, but doors lie around");
    }

    @Test
    void fuelTopUpCoversWhatIsLeftInTheFurnace() {
        assertEquals(new FurnacePlan.Fuel("minecraft:coal", 1), FurnacePlan.fuelFor(Map.of("minecraft:coal", 5), 6).orElseThrow());
        assertEquals(new FurnacePlan.Fuel("minecraft:oak_log", 4), FurnacePlan.fuelFor(Map.of("minecraft:oak_log", 9), 6).orElseThrow());
        assertEquals(new FurnacePlan.Fuel("minecraft:oak_planks", 2),
                FurnacePlan.fuelFor(Map.of("minecraft:oak_planks", 12), 6).orElseThrow(), "only planks beyond the 10 kept");
        assertTrue(FurnacePlan.fuelFor(Map.of("minecraft:oak_planks", 10), 6).isEmpty());
        assertTrue(FurnacePlan.hasSmeltables(Map.of("minecraft:mutton", 1)));
        assertFalse(FurnacePlan.hasSmeltables(Map.of("minecraft:cooked_mutton", 1)));
    }

    @Test
    void theReportSaysWhatTheFurnaceIsWaitingFor() {
        NeedsPlanner.Situation withFurnace = new NeedsPlanner.Situation(new ProgressPlanner.Facts(true, true, true, true,
                true, true, true, false), false, false, true);
        assertTrue(NeedsPlanner.report(Map.of("minecraft:mutton", 5, "minecraft:oak_log", 4), withFurnace)
                .contains("smelt: raw items ready to cook/smelt in the house furnace"));
        assertTrue(NeedsPlanner.report(Map.of("minecraft:mutton", 5), withFurnace)
                .contains("smelt: raw items waiting; needs fuel"));
    }
}
