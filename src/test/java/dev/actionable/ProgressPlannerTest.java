package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProgressPlannerTest {
    private static final String OAK = "minecraft:oak_log";

    private static final ProgressPlanner.Facts SETTLED = new ProgressPlanner.Facts(true, true, true, true, false);
    private static final Map<String, Integer> BASIC_TOOLS = Map.of("minecraft:wooden_sword", 1,
            "minecraft:wooden_pickaxe", 1, "minecraft:wooden_axe", 1);

    @Test
    void emptyInventoryStartsWithWood() {
        ProgressPlanner.Milestone next = ProgressPlanner.nextMilestone(Map.of()).orElseThrow();
        assertEquals("mine 20 minecraft:oak_log", next.goal());
        assertTrue(ProgressPlanner.isFoundation(next));
        assertTrue(next.why().contains("house"));
    }

    @Test
    void foundationIsTheChecklistInOrder() {
        assertEquals("build house", ProgressPlanner.nextMilestone(Map.of("minecraft:birch_log", 20)).orElseThrow().goal());
        assertEquals("build house", ProgressPlanner.nextMilestone(Map.of("minecraft:oak_planks", 80)).orElseThrow().goal(),
                "planks count as wood already gathered");
        ProgressPlanner.Facts house = new ProgressPlanner.Facts(true, false, false, false, false);
        assertEquals("place minecraft:crafting_table in house",
                ProgressPlanner.nextMilestone(Map.of(), house).orElseThrow().goal(),
                "a built house means wood is done even with the logs used up");
        ProgressPlanner.Facts table = new ProgressPlanner.Facts(true, false, true, false, false);
        assertEquals("place door in house", ProgressPlanner.nextMilestone(Map.of(), table).orElseThrow().goal());
        ProgressPlanner.Facts door = new ProgressPlanner.Facts(true, false, true, true, false);
        assertEquals("craft 1 minecraft:wooden_sword", ProgressPlanner.nextMilestone(Map.of(), door).orElseThrow().goal());
        assertEquals("craft 1 minecraft:wooden_pickaxe",
                ProgressPlanner.nextMilestone(Map.of("minecraft:wooden_sword", 1), door).orElseThrow().goal());
        assertEquals("craft 1 minecraft:wooden_axe", ProgressPlanner.nextMilestone(
                Map.of("minecraft:wooden_sword", 1, "minecraft:wooden_pickaxe", 1), door).orElseThrow().goal());
        assertEquals("hunt 3 minecraft:white_wool", ProgressPlanner.nextMilestone(BASIC_TOOLS, door).orElseThrow().goal(),
                "sheep are hunted only once a sword is in hand");
        Map<String, Integer> wool = new java.util.HashMap<>(BASIC_TOOLS);
        wool.put("minecraft:white_wool", 3);
        assertEquals("place minecraft:white_bed in house", ProgressPlanner.nextMilestone(wool, door).orElseThrow().goal());
        ProgressPlanner.Milestone next = ProgressPlanner.nextMilestone(BASIC_TOOLS, SETTLED).orElseThrow();
        assertEquals("craft 1 minecraft:stone_pickaxe", next.goal());
        assertTrue(ProgressPlanner.isFoundation(next), "stone tools, food, furnace and coal are run by code too");
    }

    @Test
    void theHouseTableIsNeverPickedUp() {
        Map<String, Integer> wood = Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2);
        assertEquals(List.of("craft 1 minecraft:wooden_pickaxe"),
                ProgressPlanner.stepsFor("minecraft:wooden_pickaxe", 1, wood, ProgressPlanner.TableAccess.HOME_NEARBY, OAK));
        assertEquals(List.of("go to house", "craft 1 minecraft:wooden_pickaxe"),
                ProgressPlanner.stepsFor("minecraft:wooden_pickaxe", 1, wood, ProgressPlanner.TableAccess.HOME_FAR, OAK),
                "away from home, walk back to the house table instead of making another");
    }

    @Test
    void woodIsGatheredBeforeWalkingHomeToCraft() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:wooden_sword", 1, Map.of(),
                ProgressPlanner.TableAccess.HOME_FAR, OAK);
        assertEquals(List.of("mine 1 minecraft:oak_log", "craft 4 minecraft:oak_planks", "craft 4 minecraft:stick",
                "go to house", "craft 1 minecraft:wooden_sword"), steps);
    }

    @Test
    void tableGoesIntoTheHouseReusingAnyTableNearby() {
        assertEquals(List.of("place minecraft:crafting_table in house"), ProgressPlanner.stepsForGoal(
                "place minecraft:crafting_table in house", Map.of("minecraft:crafting_table", 1),
                ProgressPlanner.TableAccess.NONE, OAK));
        assertEquals(List.of("mine 1 minecraft:crafting_table", "place minecraft:crafting_table in house"),
                ProgressPlanner.stepsForGoal("place minecraft:crafting_table in house", Map.of(),
                        ProgressPlanner.TableAccess.ELSEWHERE, OAK));
        assertEquals(List.of("craft 1 minecraft:crafting_table", "place minecraft:crafting_table in house"),
                ProgressPlanner.stepsForGoal("place minecraft:crafting_table in house",
                        Map.of("minecraft:oak_planks", 4), ProgressPlanner.TableAccess.NONE, OAK));
    }

    @Test
    void doorIsCraftedAtTheHouseTableAndHung() {
        assertEquals(List.of("craft 3 minecraft:oak_door", "place door in house"), ProgressPlanner.stepsForGoal(
                "place door in house", Map.of("minecraft:oak_planks", 6), ProgressPlanner.TableAccess.HOME_NEARBY, OAK));
        assertEquals(List.of("place door in house"), ProgressPlanner.stepsForGoal("place door in house",
                Map.of("minecraft:spruce_door", 1), ProgressPlanner.TableAccess.HOME_FAR, OAK));
        assertEquals(List.of("mine 2 minecraft:birch_log", "craft 8 minecraft:birch_planks", "go to house",
                "craft 3 minecraft:birch_door", "place door in house"), ProgressPlanner.stepsForGoal("place door in house",
                Map.of(), ProgressPlanner.TableAccess.HOME_FAR, "minecraft:birch_log"));
    }

    @Test
    void houseStepsAreJudgedFromTheWorld() {
        ProgressPlanner.Facts facts = new ProgressPlanner.Facts(true, false, true, false, true);
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied("place minecraft:crafting_table in house",
                Map.of(), false, facts));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied("place door in house", Map.of(), false, facts));
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied("go to house", Map.of(), false, facts));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied("place minecraft:crafting_table nearby",
                Map.of(), false, facts), "a plain table step still checks for a table within reach");
    }

    @Test
    void stoneAndIronComeOnlyAfterTheFoundation() {
        // A player holding stone tools but no house still gathers wood and builds the house first.
        assertEquals("mine 20 minecraft:oak_log", ProgressPlanner.nextMilestone(
                Map.of("minecraft:stone_pickaxe", 1, "minecraft:stone_sword", 1)).orElseThrow().goal());
    }

    @Test
    void milestonesAdvanceWithInventory() {
        assertEquals("minecraft:stone_pickaxe",
                ProgressPlanner.nextMilestone(BASIC_TOOLS, SETTLED).orElseThrow().item());
        assertEquals("minecraft:stone_sword", ProgressPlanner.nextMilestone(
                Map.of("minecraft:stone_pickaxe", 1, "minecraft:wooden_sword", 1, "minecraft:wooden_axe", 1), SETTLED)
                .orElseThrow().item());
        assertEquals("gather food", ProgressPlanner.nextMilestone(
                Map.of("minecraft:stone_pickaxe", 1, "minecraft:stone_sword", 1, "minecraft:wooden_axe", 1), SETTLED)
                .orElseThrow().goal(), "food comes before coal");
        ProgressPlanner.Facts furnace = new ProgressPlanner.Facts(true, true, true, true, false, true, true, false);
        ProgressPlanner.Milestone iron = ProgressPlanner.nextMilestone(Map.of("minecraft:iron_pickaxe", 1,
                "minecraft:iron_sword", 1, "minecraft:torch", 20, "minecraft:cooked_beef", 3, "minecraft:wooden_axe", 1),
                furnace).orElseThrow();
        assertEquals("mine 12 minecraft:iron_ore", iron.goal());
        assertTrue(ProgressPlanner.nextMilestone(Map.of("minecraft:iron_pickaxe", 1, "minecraft:iron_sword", 1,
                "minecraft:torch", 20, "minecraft:cooked_beef", 3, "minecraft:raw_iron", 12, "minecraft:wooden_axe", 1),
                furnace).isEmpty());
    }

    @Test
    void woodGoalNamesTheNearbyLogType() {
        assertEquals(List.of("mine 20 minecraft:birch_log"),
                ProgressPlanner.stepsForGoal("mine 20 minecraft:oak_log", Map.of(), false, "minecraft:birch_log"));
    }

    @Test
    void housePlanMakesEnoughPlanksThenBuilds() {
        List<String> steps = ProgressPlanner.stepsForGoal("build house", Map.of(OAK, 20), false, OAK);
        assertEquals(List.of("craft 72 minecraft:oak_planks", "build house"), steps);
        assertTrue(ProgressPlanner.HOUSE_PLANKS >= HouseDesign.planksNeeded());
        assertEquals(List.of("mine 18 minecraft:oak_log", "craft 72 minecraft:oak_planks", "build house"),
                ProgressPlanner.stepsForGoal("build house", Map.of(), false, OAK));
    }

    @Test
    void bedPlanHuntsSheepCraftsTheBedAndPlacesIt() {
        List<String> steps = ProgressPlanner.stepsForGoal("place minecraft:white_bed in house",
                Map.of("minecraft:oak_planks", 7), ProgressPlanner.TableAccess.HOME_NEARBY, OAK);
        assertEquals(List.of("hunt 3 minecraft:white_wool", "craft 1 minecraft:white_bed",
                "place minecraft:white_bed in house"), steps);
        assertEquals(List.of("place minecraft:white_bed in house"), ProgressPlanner.stepsForGoal(
                "place minecraft:white_bed in house", Map.of("minecraft:white_bed", 1), false, OAK));
    }

    @Test
    void huntStepsAreJudgedFromInventory() {
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied("hunt 3 minecraft:white_wool",
                Map.of("minecraft:white_wool", 3), false));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied("hunt 3 minecraft:white_wool",
                Map.of("minecraft:white_wool", 2), false));
    }

    @Test
    void houseAndBedStepsAreJudgedFromTheWorld() {
        ProgressPlanner.Facts built = new ProgressPlanner.Facts(true, false);
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied("build house", Map.of(), false, built));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied("place minecraft:white_bed in house",
                Map.of(), false, built));
        assertEquals(Optional.empty(), ProgressPlanner.stepSatisfied("build house", Map.of(), false),
                "without world facts the step is left to the judge");
    }

    @Test
    void woodenPickaxeFromNothingCraftsATableAndTakesItAlong() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:wooden_pickaxe", 1, Map.of(), false, OAK);
        assertEquals(List.of(
                "mine 3 minecraft:oak_log",
                "craft 12 minecraft:oak_planks",
                "craft 4 minecraft:stick",
                "craft 1 minecraft:crafting_table",
                "place minecraft:crafting_table nearby",
                "craft 1 minecraft:wooden_pickaxe",
                "mine 1 minecraft:crafting_table"), steps);
    }

    @Test
    void existingTableAndPlanksShortenThePlan() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:wooden_pickaxe", 1,
                Map.of("minecraft:birch_planks", 5), true, "minecraft:birch_log");
        assertEquals(List.of("craft 4 minecraft:stick", "craft 1 minecraft:wooden_pickaxe",
                "mine 1 minecraft:crafting_table"), steps);
    }

    @Test
    void heldTableIsPlacedInsteadOfCrafted() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:wooden_pickaxe", 1,
                Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2, "minecraft:crafting_table", 1), false, OAK);
        assertEquals(List.of("place minecraft:crafting_table nearby", "craft 1 minecraft:wooden_pickaxe",
                "mine 1 minecraft:crafting_table"), steps);
    }

    @Test
    void stonePickaxeMinesStoneWithTheWoodenOne() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:stone_pickaxe", 1,
                Map.of("minecraft:wooden_pickaxe", 1), true, OAK);
        assertEquals(List.of(
                "mine 3 minecraft:stone",
                "mine 1 minecraft:oak_log",
                "craft 4 minecraft:oak_planks",
                "craft 4 minecraft:stick",
                "craft 1 minecraft:stone_pickaxe",
                "mine 1 minecraft:crafting_table"), steps);
    }

    @Test
    void aTablePlacedElsewhereIsPickedUpInsteadOfCrafted() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:wooden_pickaxe", 1,
                Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2), ProgressPlanner.TableAccess.ELSEWHERE, OAK);
        assertEquals(List.of(
                "mine 1 minecraft:crafting_table",
                "place minecraft:crafting_table nearby",
                "craft 1 minecraft:wooden_pickaxe",
                "mine 1 minecraft:crafting_table"), steps);
        assertTrue(steps.stream().noneMatch(step -> step.startsWith("craft 1 minecraft:crafting_table")));
    }

    @Test
    void noTablePlanIsUnchangedWhenNoTableIsUsed() {
        // Planks and sticks are 2x2 crafts, so no table is placed or picked up.
        assertEquals(List.of("mine 1 minecraft:oak_log", "craft 4 minecraft:oak_planks", "craft 4 minecraft:stick"),
                ProgressPlanner.stepsFor("minecraft:stick", 4, Map.of(), ProgressPlanner.TableAccess.ELSEWHERE, OAK));
    }

    @Test
    void pickingUpTheTableIsJudgedFromInventory() {
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied("mine 1 minecraft:crafting_table",
                Map.of("minecraft:crafting_table", 1), true));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied("mine 1 minecraft:crafting_table",
                Map.of(), true));
    }

    @Test
    void duplicateTablesAreRefused() {
        String goal = "craft 1 minecraft:crafting_table";
        assertTrue(ProgressPlanner.duplicateTable(goal, Map.of("minecraft:crafting_table", 1),
                ProgressPlanner.TableAccess.NONE));
        assertTrue(ProgressPlanner.duplicateTable(goal, Map.of(), ProgressPlanner.TableAccess.ELSEWHERE));
        assertTrue(ProgressPlanner.duplicateTable(goal, Map.of(), ProgressPlanner.TableAccess.NEARBY));
        assertFalse(ProgressPlanner.duplicateTable(goal, Map.of(), ProgressPlanner.TableAccess.NONE));
        assertFalse(ProgressPlanner.duplicateTable("craft 1 minecraft:wooden_pickaxe",
                Map.of("minecraft:crafting_table", 1), ProgressPlanner.TableAccess.NEARBY));
    }

    @Test
    void miningAPlacedTableIsAValidGoal() {
        assertEquals(Optional.empty(), ProgressPlanner.goalProblem("mine 1 minecraft:crafting_table", Map.of()));
        assertTrue(ProgressPlanner.goalProblem("mine 1 minecraft:furnace", Map.of()).orElseThrow()
                .contains("minecraft:wooden_pickaxe"));
    }

    @Test
    void briefingSaysWhereTheTableIsAndToReuseIt() {
        assertTrue(ProgressPlanner.briefing(Map.of(), ProgressPlanner.TableAccess.ELSEWHERE, OAK)
                .contains("crafting_table=placed nearby, see nearest_crafting_table (mine it to pick it up"));
        assertTrue(ProgressPlanner.briefing(Map.of("minecraft:crafting_table", 1), ProgressPlanner.TableAccess.NONE, OAK)
                .contains("crafting_table=in inventory"));
        assertTrue(ProgressPlanner.briefing(Map.of(), ProgressPlanner.TableAccess.NONE, OAK)
                .contains("crafting_table=none"));
    }

    @Test
    void miningStoneWithoutAPickaxeCraftsOneFirst() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:cobblestone", 8, Map.of(), false, OAK);
        int pickaxe = steps.indexOf("craft 1 minecraft:wooden_pickaxe");
        int stone = steps.indexOf("mine 8 minecraft:stone");
        assertTrue(pickaxe >= 0 && stone > pickaxe, steps.toString());
    }

    @Test
    void stepTotalsIncludeWhatIsAlreadyHeld() {
        // 2 logs held: the plan mines up to 3, matching Baritone's "hold this many" semantics.
        List<String> steps = ProgressPlanner.stepsFor("minecraft:oak_planks", 12, Map.of(OAK, 2), false, OAK);
        assertEquals(List.of("mine 3 minecraft:oak_log", "craft 12 minecraft:oak_planks"), steps);
        assertEquals(List.of("mine 12 minecraft:iron_ore"), ProgressPlanner.stepsForGoal("mine 12 minecraft:iron_ore",
                Map.of("minecraft:stone_pickaxe", 1, "minecraft:raw_iron", 4), false, OAK)
                .subList(0, 1));
    }

    @Test
    void torchesUseCoalAndSticksWithoutATable() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:torch", 16,
                Map.of("minecraft:stone_pickaxe", 1), false, OAK);
        assertEquals(List.of(
                "mine 4 minecraft:coal_ore",
                "mine 1 minecraft:oak_log",
                "craft 4 minecraft:oak_planks",
                "craft 4 minecraft:stick",
                "craft 16 minecraft:torch"), steps);
    }

    @Test
    void unknownItemsHaveNoPlan() {
        assertEquals(List.of(), ProgressPlanner.stepsFor("minecraft:beacon", 1, Map.of(), true, OAK));
        assertEquals(List.of(), ProgressPlanner.stepsForGoal("explore to find new resources", Map.of(), true, OAK));
    }

    @Test
    void everyMilestonePlanFitsTheStepLimit() {
        Map<String, Integer> inventory = new HashMap<>();
        for (ProgressPlanner.Milestone milestone : ProgressPlanner.MILESTONES) {
            List<String> steps = ProgressPlanner.stepsForGoal(milestone.goal(), inventory, false, OAK);
            assertTrue(!steps.isEmpty() && steps.size() <= ActionPlanner.MAX_STEPS,
                    milestone.goal() + " -> " + steps);
            inventory.put(milestone.item(), milestone.count());
        }
    }

    @Test
    void stepsAreJudgedFromInventory() {
        Map<String, Integer> inventory = Map.of("minecraft:birch_log", 3, "minecraft:cobblestone", 2);
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied("mine 3 minecraft:oak_log", inventory, false));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied("mine 3 minecraft:stone", inventory, false));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied("craft 4 minecraft:stick", inventory, false));
        assertEquals(Optional.of(true),
                ProgressPlanner.stepSatisfied("place minecraft:crafting_table nearby", inventory, true));
        assertEquals(Optional.of(false),
                ProgressPlanner.stepSatisfied("place minecraft:crafting_table nearby", inventory, false));
        assertEquals(Optional.empty(), ProgressPlanner.stepSatisfied("goto the clearing at 10 70 -5", inventory, false));
    }

    @Test
    void impossibleGoalsAreExplained() {
        assertTrue(ProgressPlanner.goalProblem("mine 24 minecraft:cobblestone", Map.of()).orElseThrow()
                .contains("minecraft:wooden_pickaxe"));
        assertTrue(ProgressPlanner.goalProblem("mine 12 minecraft:iron_ore",
                Map.of("minecraft:wooden_pickaxe", 1)).orElseThrow().contains("minecraft:stone_pickaxe"));
        assertTrue(ProgressPlanner.goalProblem("mine 4 minecraft:oak_planks", Map.of()).orElseThrow()
                .contains("crafted, not mined"));
        assertTrue(ProgressPlanner.goalProblem("craft 1 minecraft:beacon", Map.of()).isPresent());
        assertEquals(Optional.empty(), ProgressPlanner.goalProblem("mine 16 minecraft:oak_log", Map.of()));
        assertEquals(Optional.empty(), ProgressPlanner.goalProblem("craft 1 minecraft:wooden_pickaxe", Map.of()));
        assertEquals(Optional.empty(), ProgressPlanner.goalProblem("explore", Map.of()));
    }

    @Test
    void briefingExplainsWhyAndWhatIsAvailable() {
        String briefing = ProgressPlanner.briefing(Map.of(OAK, 2), ProgressPlanner.TableAccess.NONE, OAK,
                ProgressPlanner.Facts.NONE, HomeRange.describe(new int[] {0, 64, 0}, false, false));
        assertTrue(briefing.contains("pickaxe_tier=none"));
        assertTrue(briefing.contains("next_milestone=mine 20 minecraft:oak_log (why:"));
        assertTrue(briefing.contains("milestone_plan=[mine 20 minecraft:oak_log]"));
        assertTrue(briefing.contains("house_built=false, bed_placed=false"));
        assertTrue(briefing.contains("home=0,64,0 (start area); stay within 64 blocks"));
        assertTrue(briefing.contains("minecraft:stone (needs minecraft:wooden_pickaxe)"));
        assertTrue(briefing.contains("minecraft:oak_log x2: craft into planks"));
        assertTrue(briefing.contains("craftable_now=[minecraft:oak_planks]"));
        assertTrue(briefing.contains("craftable_catalog="));
    }

    @Test
    void modelMineCountsAreRaisedToTheStepTarget() {
        String step = "mine 20 minecraft:oak_log";
        // The log loop: the model kept sending "mine 4", which Baritone finished at once holding 6.
        assertEquals("mine 20 minecraft:oak_log", ProgressPlanner.alignMineQuantity("mine 4 minecraft:oak_log", step));
        assertEquals("mine 20 minecraft:birch_log",
                ProgressPlanner.alignMineQuantity("mine 4 minecraft:birch_log", step), "any wood counts");
        assertEquals("mine 30 minecraft:oak_log", ProgressPlanner.alignMineQuantity("mine 30 minecraft:oak_log", step));
        assertEquals("mine 4 minecraft:stone", ProgressPlanner.alignMineQuantity("mine 4 minecraft:stone", step),
                "a different resource is left alone");
        assertEquals("mine 8 minecraft:cobblestone",
                ProgressPlanner.alignMineQuantity("mine 2 minecraft:cobblestone", "mine 8 minecraft:stone"),
                "cobblestone and stone are the same resource");
        assertEquals("goto 1 2 3", ProgressPlanner.alignMineQuantity("goto 1 2 3", step));
        assertEquals("mine 4 minecraft:oak_log",
                ProgressPlanner.alignMineQuantity("mine 4 minecraft:oak_log", "craft 4 minecraft:stick"));
    }

    @Test
    void aFloodedFloorIsFixedBeforeFurnishing() {
        ProgressPlanner.Facts wet = new ProgressPlanner.Facts(true, false, false, false, false, false);
        assertEquals("lay house floor", ProgressPlanner.nextMilestone(Map.of(), wet).orElseThrow().goal());
        assertEquals(List.of("lay house floor"),
                ProgressPlanner.stepsForGoal("lay house floor", Map.of("minecraft:oak_planks", 20), false, OAK));
        assertEquals(List.of("mine 3 minecraft:oak_log", "craft 12 minecraft:oak_planks", "lay house floor"),
                ProgressPlanner.stepsForGoal("lay house floor", Map.of(), false, OAK));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied("lay house floor", Map.of(), false, wet));
        ProgressPlanner.Facts dry = new ProgressPlanner.Facts(true, false, false, false, false, true);
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied("lay house floor", Map.of(), false, dry));
        assertEquals("place minecraft:crafting_table in house",
                ProgressPlanner.nextMilestone(Map.of(), dry).orElseThrow().goal());
    }

    @Test
    void aHuntWithoutAWeaponCraftsASwordFirst() {
        List<String> steps = ProgressPlanner.stepsForGoal("hunt 3 minecraft:white_wool",
                Map.of("minecraft:oak_planks", 10), ProgressPlanner.TableAccess.HOME_NEARBY, OAK);
        assertEquals(List.of("craft 4 minecraft:stick", "craft 1 minecraft:wooden_sword", "hunt 3 minecraft:white_wool"),
                steps);
        assertEquals(List.of("hunt 3 minecraft:white_wool"), ProgressPlanner.stepsForGoal("hunt 3 minecraft:white_wool",
                Map.of("minecraft:wooden_axe", 1), ProgressPlanner.TableAccess.HOME_NEARBY, OAK), "an axe is a weapon too");
    }

    private static final Map<String, Integer> STONE_KIT = Map.of("minecraft:stone_pickaxe", 1,
            "minecraft:stone_sword", 1, "minecraft:wooden_axe", 1);

    private static ProgressPlanner.Facts house(boolean furnace) {
        return new ProgressPlanner.Facts(true, true, true, true, false, true, furnace, false);
    }

    @Test
    void afterTheBedItIsStoneToolsThenFoodThenFurnaceThenCoal() {
        assertEquals("gather food", ProgressPlanner.nextMilestone(STONE_KIT, house(false)).orElseThrow().goal());
        Map<String, Integer> fed = new java.util.HashMap<>(STONE_KIT);
        fed.put("minecraft:cooked_porkchop", 3);
        assertEquals("place minecraft:furnace in house", ProgressPlanner.nextMilestone(fed, house(false)).orElseThrow().goal());
        assertEquals("craft 16 minecraft:torch", ProgressPlanner.nextMilestone(fed, house(true)).orElseThrow().goal(),
                "furnace and food: now coal");
    }

    @Test
    void rawOreWaitingToBeSmeltedMovesCoalUp() {
        Map<String, Integer> ore = new java.util.HashMap<>(STONE_KIT);
        ore.put("minecraft:raw_iron", 4);
        assertEquals("craft 16 minecraft:torch", ProgressPlanner.nextMilestone(ore, house(false)).orElseThrow().goal());
        assertTrue(ProgressPlanner.coalTooEarly("mine 4 minecraft:coal_ore", ore, house(false)).isEmpty());
    }

    @Test
    void coalGoalsBeforeFoodAndAFurnaceAreRejected() {
        String reason = ProgressPlanner.coalTooEarly("mine 4 minecraft:coal_ore", STONE_KIT, house(false)).orElseThrow();
        assertTrue(reason.contains("a furnace in the house") && reason.contains("food (0/24"), reason);
        assertTrue(ProgressPlanner.coalTooEarly("craft 16 minecraft:torch", STONE_KIT, house(true)).isPresent(),
                "a furnace alone is not enough; food first");
        Map<String, Integer> fed = new java.util.HashMap<>(STONE_KIT);
        fed.put("minecraft:cooked_beef", 3);
        assertTrue(ProgressPlanner.coalTooEarly("mine 4 minecraft:coal_ore", fed, house(true)).isEmpty());
        assertTrue(ProgressPlanner.coalTooEarly("mine 8 minecraft:stone", STONE_KIT, house(false)).isEmpty(),
                "other goals are untouched");
    }

    @Test
    void gatheringFoodHuntsUntilTheStockIsFull() {
        assertEquals(List.of("gather food"), ProgressPlanner.stepsForGoal("gather food", STONE_KIT, false, OAK));
        assertEquals(Optional.of(false), ProgressPlanner.stepSatisfied("gather food",
                Map.of("minecraft:porkchop", 4), false));
        assertEquals(Optional.of(true), ProgressPlanner.stepSatisfied("gather food",
                Map.of("minecraft:porkchop", 8), false));
        assertEquals(List.of("craft 4 minecraft:stick", "craft 1 minecraft:wooden_sword", "gather food"),
                ProgressPlanner.stepsForGoal("gather food", Map.of("minecraft:oak_planks", 6),
                        ProgressPlanner.TableAccess.HOME_NEARBY, OAK), "never hunt bare-handed");
    }
}
