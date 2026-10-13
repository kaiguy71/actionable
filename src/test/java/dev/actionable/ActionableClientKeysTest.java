package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ActionableClientKeysTest {
    @Test
    void attemptKeyIgnoresQuantityAndSpacingButNotTheGoal() {
        assertEquals(ActionableClient.attemptKey("mine 16 minecraft:oak_log"),
                ActionableClient.attemptKey("Mine  8 minecraft:oak_log"));
        assertNotEquals(
                ActionableClient.attemptKey("craft 1 minecraft:wooden_pickaxe using a placed crafting table"),
                ActionableClient.attemptKey("craft 1 minecraft:stone_pickaxe using a placed crafting table"));
    }
}
