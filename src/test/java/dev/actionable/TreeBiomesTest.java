package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TreeBiomesTest {
    @Test
    void knowsWhichBiomesHaveTrees() {
        assertTrue(TreeBiomes.hasTrees("minecraft:birch_forest"));
        assertTrue(TreeBiomes.logsFor("minecraft:savanna").contains("acacia"));
        assertFalse(TreeBiomes.hasTrees("minecraft:desert"));
        assertFalse(TreeBiomes.hasTrees("minecraft:deep_ocean"));
        assertFalse(TreeBiomes.hasTrees("minecraft:river"));
    }

    @Test
    void treelessBiomePointsToNearbyForest() {
        String advice = TreeBiomes.advice("minecraft:desert",
                "[minecraft:desert(distance=0blocks,vector=0,0,0,angle_from_facing=0.0deg),"
                        + " minecraft:savanna(distance=64blocks,vector=64,0,0,angle_from_facing=10.0deg)]");
        assertTrue(advice.startsWith("wood_here=NONE"));
        assertTrue(advice.contains("savanna") && advice.contains("acacia"));
        assertTrue(TreeBiomes.advice("minecraft:ocean", "[]").contains("travel"));
        assertTrue(TreeBiomes.advice("minecraft:forest", "[]").contains("oak, birch"));
    }
}
