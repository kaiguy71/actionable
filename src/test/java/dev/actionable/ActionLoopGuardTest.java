package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActionLoopGuardTest {
    private static final String MINE = "baritone:mine 4 minecraft:oak_log";

    @Test
    void thirdAttemptAfterTwoFailuresIsBlocked() {
        ActionLoopGuard guard = new ActionLoopGuard();
        guard.record(MINE, "BLOCK_PLACE_FAILED_NO_SUPPORT: x", "a");
        assertTrue(guard.veto(MINE).isEmpty());
        guard.record(MINE, "BLOCK_PLACE_FAILED_NO_SUPPORT: x", "b");
        assertTrue(guard.veto(MINE).orElseThrow().startsWith("REPEAT_BLOCKED"));
    }

    @Test
    void differentActionIsNeverBlockedByEarlierFailures() {
        ActionLoopGuard guard = new ActionLoopGuard();
        guard.record(MINE, "X_FAILED", "a");
        guard.record(MINE, "X_FAILED", "b");
        assertTrue(guard.veto("baritone:explore").isEmpty());
    }

    @Test
    void successResetsFailureCount() {
        ActionLoopGuard guard = new ActionLoopGuard();
        guard.record(MINE, "X_FAILED", "a");
        guard.record(MINE, "Started Baritone command", "b");
        guard.record(MINE, "X_FAILED", "c");
        assertTrue(guard.veto(MINE).isEmpty());
    }

    @Test
    void sameActionWithNoProgressIsBlockedButProgressIsAllowed() {
        ActionLoopGuard guard = new ActionLoopGuard();
        for (int round = 0; round < ActionLoopGuard.MAX_REPEATS; round++) {
            assertTrue(guard.veto(MINE).isEmpty(), "round " + round);
            guard.record(MINE, "left running", "same");
        }
        assertTrue(guard.veto(MINE).isPresent());

        ActionLoopGuard moving = new ActionLoopGuard();
        for (int round = 0; round < 10; round++) {
            assertTrue(moving.veto(MINE).isEmpty());
            moving.record(MINE, "left running", "inventory " + round);
        }
    }

    @Test
    void waitAndAttackAreNotRepeatVetoed() {
        ActionLoopGuard guard = new ActionLoopGuard();
        for (int round = 0; round < 8; round++) {
            guard.record("wait", "waiting", "same");
            guard.record("attack:12", "ATTACK_SENT", "same");
        }
        assertTrue(guard.veto("wait").isEmpty());
        guard.record("attack:12", "ATTACK_SENT", "same");
        guard.record("attack:12", "ATTACK_SENT", "same");
        assertTrue(guard.veto("attack:12").isEmpty());
    }

    @Test
    void stallsAfterSixUnchangedRoundsAndProgressClearsIt() {
        ActionLoopGuard guard = new ActionLoopGuard();
        guard.record("wait", "waiting", "start");
        for (int round = 0; round < ActionLoopGuard.MAX_STALLED_ROUNDS; round++) {
            assertFalse(guard.stalled(), "round " + round);
            guard.record("wait", "waiting", "start");
        }
        assertTrue(guard.stalled());
        guard.record("wait", "waiting", "changed");
        assertFalse(guard.stalled());
    }

    @Test
    void lateBaritoneRejectionCountsAsFailure() {
        ActionLoopGuard guard = new ActionLoopGuard();
        guard.record(MINE, "Started Baritone command", "a");
        guard.markLastFailed();
        guard.record(MINE, "Started Baritone command", "a");
        guard.markLastFailed();
        assertTrue(guard.veto(MINE).isPresent());
    }

    @Test
    void signatureIgnoresCaseAndSpacing() {
        var first = new ActionPlanner.PlannedAction("lookup", "", "", 0, 0, 0, " Crafting Table ");
        var second = new ActionPlanner.PlannedAction("lookup", "", "", 0, 0, 0, "crafting table");
        assertEquals(ActionLoopGuard.signature(first), ActionLoopGuard.signature(second));
        var place = new ActionPlanner.PlannedAction("place_block", "", "minecraft:stone", 1, 2, 3, "");
        assertEquals("place_block:minecraft:stone@1,2,3", ActionLoopGuard.signature(place));
    }

    @Test
    void transientBusyIsNotAFailureButRejectedCompletionIs() {
        assertFalse(ActionLoopGuard.isFailure("CRAFT_FAILED_BUSY: close containers first"));
        assertTrue(ActionLoopGuard.isFailure("STEP_COMPLETION_REJECTED: inventory does not satisfy x"));
        assertTrue(ActionLoopGuard.isFailure("CRAFT_FAILED_NO_RECIPE: x"));
    }

    @Test
    void resetClearsEverything() {
        ActionLoopGuard guard = new ActionLoopGuard();
        guard.record(MINE, "X_FAILED", "a");
        guard.record(MINE, "X_FAILED", "a");
        guard.reset();
        assertTrue(guard.veto(MINE).isEmpty());
        assertFalse(guard.stalled());
    }
}
