package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProgressPlannerTest {
    private static final String OAK = "minecraft:oak_log";

    @Test
    void emptyInventoryStartsWithAWoodenPickaxe() {
        ProgressPlanner.Milestone next = ProgressPlanner.nextMilestone(Map.of()).orElseThrow();
        assertEquals("minecraft:wooden_pickaxe", next.item());
        assertEquals("craft 1 minecraft:wooden_pickaxe", next.goal());
        assertTrue(next.why().contains("pickaxe"));
    }

    @Test
    void milestonesAdvanceWithInventory() {
        assertEquals("minecraft:stone_pickaxe",
                ProgressPlanner.nextMilestone(Map.of("minecraft:wooden_pickaxe", 1)).orElseThrow().item());
        assertEquals("minecraft:stone_sword", ProgressPlanner.nextMilestone(
                Map.of("minecraft:stone_pickaxe", 1)).orElseThrow().item());
        assertEquals("minecraft:torch", ProgressPlanner.nextMilestone(
                Map.of("minecraft:stone_pickaxe", 1, "minecraft:stone_sword", 1)).orElseThrow().item());
        ProgressPlanner.Milestone iron = ProgressPlanner.nextMilestone(Map.of("minecraft:iron_pickaxe", 1,
                "minecraft:iron_sword", 1, "minecraft:torch", 20, "minecraft:furnace", 1)).orElseThrow();
        assertEquals("mine 12 minecraft:iron_ore", iron.goal());
        assertTrue(ProgressPlanner.nextMilestone(Map.of("minecraft:iron_pickaxe", 1, "minecraft:iron_sword", 1,
                "minecraft:torch", 20, "minecraft:furnace", 1, "minecraft:raw_iron", 12)).isEmpty());
    }

    @Test
    void woodenPickaxeFromNothingIsSixOrderedSteps() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:wooden_pickaxe", 1, Map.of(), false, OAK);
        assertEquals(List.of(
                "mine 3 minecraft:oak_log",
                "craft 12 minecraft:oak_planks",
                "craft 4 minecraft:stick",
                "craft 1 minecraft:crafting_table",
                "place minecraft:crafting_table nearby",
                "craft 1 minecraft:wooden_pickaxe"), steps);
    }

    @Test
    void existingTableAndPlanksShortenThePlan() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:wooden_pickaxe", 1,
                Map.of("minecraft:birch_planks", 5), true, "minecraft:birch_log");
        assertEquals(List.of("craft 4 minecraft:stick", "craft 1 minecraft:wooden_pickaxe"), steps);
    }

    @Test
    void heldTableIsPlacedInsteadOfCrafted() {
        List<String> steps = ProgressPlanner.stepsFor("minecraft:wooden_pickaxe", 1,
                Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2, "minecraft:crafting_table", 1), false, OAK);
        assertEquals(List.of("place minecraft:crafting_table nearby", "craft 1 minecraft:wooden_pickaxe"), steps);
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
                "craft 1 minecraft:stone_pickaxe"), steps);
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
        String briefing = ProgressPlanner.briefing(Map.of(OAK, 2), false, OAK);
        assertTrue(briefing.contains("pickaxe_tier=none"));
        assertTrue(briefing.contains("next_milestone=craft 1 minecraft:wooden_pickaxe (why:"));
        assertTrue(briefing.contains("milestone_plan=[mine 3 minecraft:oak_log"));
        assertTrue(briefing.contains("minecraft:stone (needs minecraft:wooden_pickaxe)"));
        assertTrue(briefing.contains("minecraft:oak_log x2: craft into planks"));
        assertTrue(briefing.contains("craftable_now=[minecraft:oak_planks]"));
        assertTrue(briefing.contains("craftable_catalog="));
    }
}
