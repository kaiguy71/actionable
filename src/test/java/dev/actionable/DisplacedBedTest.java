package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A bed picked back up after being moved is put back, not replaced by hunting sheep for a new one. */
class DisplacedBedTest {
    private static final ProgressPlanner.Facts HOUSE_NO_BED = new ProgressPlanner.Facts(true, false, true, true,
            false, true, false, false);

    @Test
    void anyBedColourCounts() {
        assertTrue(TechTree.isBed("minecraft:white_bed"));
        assertTrue(TechTree.isBed("minecraft:red_bed"));
        assertFalse(TechTree.isBed("minecraft:bedrock"));
        assertFalse(TechTree.isBed("minecraft:white_wool"));
    }

    @Test
    void aBedInHandSkipsTheSheepHuntAndGoesStraightBackIn() {
        Map<String, Integer> kit = Map.of("minecraft:white_bed", 1, "minecraft:wooden_sword", 1,
                "minecraft:wooden_pickaxe", 1, "minecraft:wooden_axe", 1);
        ProgressPlanner.Milestone next = ProgressPlanner.nextMilestone(kit, HOUSE_NO_BED).orElseThrow();
        assertEquals(ProgressPlanner.PLACE_BED, next.goal(), "no wool hunt while holding a bed");
        assertEquals(List.of(ProgressPlanner.PLACE_BED),
                ProgressPlanner.stepsForGoal(ProgressPlanner.PLACE_BED, kit, false, "minecraft:oak_log"));
        assertEquals(List.of(ProgressPlanner.PLACE_BED), ProgressPlanner.stepsForGoal(ProgressPlanner.PLACE_BED,
                Map.of("minecraft:red_bed", 1), false, "minecraft:oak_log"), "a red bed works too");
    }

    @Test
    void atNightAHeldBedIsPutBackSoItCanSleep() {
        NeedsPlanner.Situation night = new NeedsPlanner.Situation(HOUSE_NO_BED, true, false, true);
        assertEquals(ProgressPlanner.PLACE_BED, NeedsPlanner.homeTask(Map.of("minecraft:white_bed", 1,
                "minecraft:wooden_sword", 1, "minecraft:wooden_pickaxe", 1), night).orElseThrow().goal());
    }
}
