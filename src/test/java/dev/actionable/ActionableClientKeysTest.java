package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ActionableClientKeysTest {
    @Test
    void houseFurnitureGoalsDoNotCollapseIntoTheSameAchievement() {
        assertNotEquals(ActionableClient.goalKey("build house"),
                ActionableClient.goalKey("place minecraft:crafting_table in house"));
        assertNotEquals(ActionableClient.goalKey("place minecraft:white_bed in house"),
                ActionableClient.goalKey("place door in house"));
        assertNotEquals(ActionableClient.goalKey("mine 4 minecraft:oak_log"),
                ActionableClient.goalKey("mine 20 minecraft:oak_log"));
        assertEquals(ActionableClient.goalKey("mine 8 minecraft:stone"),
                ActionableClient.goalKey("mine 8 minecraft:cobblestone"));
    }

    @Test
    void attemptKeyIgnoresQuantityAndSpacingButNotTheGoal() {
        assertEquals(ActionableClient.attemptKey("mine 16 minecraft:oak_log"),
                ActionableClient.attemptKey("Mine  8 minecraft:oak_log"));
        assertNotEquals(
                ActionableClient.attemptKey("craft 1 minecraft:wooden_pickaxe using a placed crafting table"),
                ActionableClient.attemptKey("craft 1 minecraft:stone_pickaxe using a placed crafting table"));
    }
}
