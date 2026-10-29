package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NetherProgressTest {
    @Test
    void actualIronStockAdvancesToToolsRatherThanAnotherOreBatch() {
        assertEquals(24, NetherProgress.ironProgress(Map.of("minecraft:iron_ingot", 24)));
        assertEquals(12, NetherProgress.ironProgress(Map.of("minecraft:iron_chestplate", 1, "minecraft:iron_pickaxe", 1,
                "minecraft:shield", 1)));
        assertTrue(ProgressPlanner.satisfied(new ProgressPlanner.Milestone("minecraft:raw_iron", 12, "test"),
                Map.of("minecraft:iron_ingot", 24)));
        assertEquals("craft 1 minecraft:iron_pickaxe", NetherProgress.next(Map.of("minecraft:iron_ingot", 24)).orElseThrow().goal());
    }

    @Test
    void stoneSwordIsNotAnIronSwordButDiamondSwordSatisfiesUpgrade() {
        var ironSword = new ProgressPlanner.Milestone("minecraft:iron_sword", 1, "upgrade");
        assertFalse(ProgressPlanner.satisfied(ironSword, Map.of("minecraft:stone_sword", 1)));
        assertTrue(ProgressPlanner.satisfied(ironSword, Map.of("minecraft:diamond_sword", 1)));
    }

    @Test
    void allPreparationsLeadToEntryAndOnlyActualNetherDimensionCompletesIt() {
        Map<String, Integer> ready = new HashMap<>();
        for (var milestone : NetherProgress.MILESTONES) {
            if (milestone.kind() != ProgressPlanner.Kind.NETHER) ready.put(milestone.item(), milestone.count());
        }
        assertEquals(NetherProgress.ENTER, NetherProgress.next(ready).orElseThrow().goal());
        assertEquals(StepCheck.Verdict.NOT_DONE,
                CompletionCheck.verdict(NetherProgress.ENTER, ready, false, ProgressPlanner.Facts.NONE));
        var inNether = new ProgressPlanner.Facts(false, false, false, false, false, false, false, false, true);
        assertEquals(StepCheck.Verdict.DONE, CompletionCheck.verdict(NetherProgress.ENTER, ready, false, inNether));
        assertTrue(ProgressPlanner.nextMilestone(ready, inNether).isEmpty());
    }

    @Test
    void fullBucketIsNotTreatedAsMissingBucket() {
        assertTrue(ProgressPlanner.satisfied(new ProgressPlanner.Milestone("minecraft:bucket", 1, "water"),
                Map.of("minecraft:water_bucket", 1)));
        assertEquals(3, NetherProgress.ironProgress(Map.of("minecraft:water_bucket", 1)));
        assertEquals(java.util.Optional.of(false), ProgressPlanner.stepSatisfied("fill water bucket",
                Map.of("minecraft:bucket", 1), false));
        assertEquals(java.util.Optional.of(true), ProgressPlanner.stepSatisfied("fill water bucket",
                Map.of("minecraft:water_bucket", 1), false));
    }

    @Test
    void diamondAndObsidianDependenciesAreEnforced() {
        assertEquals(TechTree.Tier.IRON, TechTree.toolFor("minecraft:deepslate_diamond_ore"));
        assertEquals(TechTree.Tier.DIAMOND, TechTree.toolFor("minecraft:obsidian"));
        assertTrue(ProgressPlanner.goalProblem("mine 10 minecraft:obsidian", Map.of("minecraft:iron_pickaxe", 1)).isPresent());
        assertTrue(ProgressPlanner.goalProblem("mine 10 minecraft:obsidian", Map.of("minecraft:diamond_pickaxe", 1)).isEmpty());
        assertEquals(List.of("mine 3 minecraft:diamond_ore", "craft 1 minecraft:diamond_pickaxe"),
                ProgressPlanner.stepsFor("minecraft:diamond_pickaxe", 1,
                        Map.of("minecraft:iron_pickaxe", 1, "minecraft:stick", 2), true, "minecraft:oak_log"));
    }

    @Test
    void ingotsAreSmeltedAndNeverMinedAsItems() {
        var steps = ProgressPlanner.stepsFor("minecraft:iron_pickaxe", 1,
                Map.of("minecraft:stone_pickaxe", 1, "minecraft:raw_iron", 3, "minecraft:coal", 1,
                        "minecraft:stick", 2), true, "minecraft:oak_log");
        assertEquals(List.of(NeedsPlanner.SMELT, "craft 1 minecraft:iron_pickaxe"), steps);
    }

    @Test
    void flintPlansRetainDropObjectiveInsteadOfFinishingAtOneGravel() {
        assertEquals(List.of("mine 1 minecraft:flint", "craft 1 minecraft:flint_and_steel"),
                ProgressPlanner.stepsForGoal("craft 1 minecraft:flint_and_steel", Map.of("minecraft:iron_ingot", 1),
                        true, "minecraft:oak_log"));
        assertEquals(List.of("mine 1 minecraft:flint"), ProgressPlanner.stepsForGoal("mine 1 minecraft:flint",
                Map.of("minecraft:gravel", 20), true, "minecraft:oak_log"));
        assertEquals(java.util.Optional.of(false), ProgressPlanner.stepSatisfied("mine 1 minecraft:flint",
                Map.of("minecraft:gravel", 20), false));
    }
}
