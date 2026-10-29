package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GoalRetryGuardTest {
    @Test
    void failedHelmetCannotImmediatelyRestartWithTheSameConditions() {
        var guard = new GoalRetryGuard();
        guard.failed("craft 1 minecraft:iron_helmet", "same inventory and area", 100);
        assertFalse(guard.available("craft 1 minecraft:iron_helmet", "same inventory and area", 101));
        assertFalse(guard.available("CRAFT 2 minecraft:iron_helmet", "same inventory and area", 101));
        assertTrue(guard.available("explore", "same inventory and area", 101));
        assertTrue(guard.available("craft 1 minecraft:iron_helmet", "new inventory or area", 101));
        assertTrue(guard.available("craft 1 minecraft:iron_helmet", "same inventory and area", 1300));
    }
}
