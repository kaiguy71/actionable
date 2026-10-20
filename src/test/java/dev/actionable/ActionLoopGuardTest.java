package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActionLoopGuardTest {
    private static final String MINE = "baritone:mine 4 minecraft:oak_log";

    @Test
    void freshEvidenceIsConsideredBeforeTheNextVeto() {
        var guard = new ActionLoopGuard();
        for (int i = 0; i < 3; i++) guard.record(MINE, "started", "same");
        assertTrue(guard.veto(MINE).isPresent());
        guard.observe("inventory changed before next action");
        assertTrue(guard.veto(MINE).isEmpty());
        assertFalse(guard.stalled());
    }

    @Test
    void verifiedBlockChangesAllowRandomDropWorkWithoutAFlintException() {
        var guard = new ActionLoopGuard();
        for (int i = 0; i < 20; i++) {
            assertTrue(guard.veto(MINE).isEmpty());
            guard.record(MINE, "started", "same inventory and position");
            assertTrue(guard.recordWork("observed block removed " + i, "collect rare drop|held=0"));
            assertFalse(guard.stalled());
        }
    }

    @Test
    void workCreditIsBoundedAndDuplicateObservationsDoNotResetGuards() {
        var guard = new ActionLoopGuard();
        for (int i = 0; i < ActionLoopGuard.MAX_VERIFIED_WORK; i++)
            assertTrue(guard.recordWork("block " + i, "same objective"));
        assertFalse(guard.recordWork("block 0", "same objective"));
        assertFalse(guard.recordWork("block 65", "same objective"));
        for (int i = 0; i < 3; i++) guard.record(MINE, "started", "same");
        assertTrue(guard.veto(MINE).isPresent());
        assertFalse(guard.recordWork("block 0", "same objective"));
        assertTrue(guard.veto(MINE).isPresent());
        assertTrue(guard.recordWork("new step work", "new objective"));
        assertTrue(guard.veto(MINE).isEmpty());
    }

    @Test
    void ordinaryMovementDoesNotEraseActualRepeatedFailures() {
        var guard = new ActionLoopGuard();
        guard.record(MINE, "MINE_FAILED_NO_SAFE_SITE", "one");
        guard.record(MINE, "MINE_FAILED_NO_SAFE_SITE", "two");
        guard.observe("three");
        assertTrue(guard.veto(MINE).isPresent());
    }

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
    void differentInteractionTargetsDoNotShareFailures() {
        for (String type : new String[] {"use_block", "break_block", "menu"}) {
            var first = new ActionPlanner.PlannedAction(type, "move", "", 1, 64, 3, "");
            var second = new ActionPlanner.PlannedAction(type, "move", "", 2, 64, 3, "");
            ActionLoopGuard guard = new ActionLoopGuard();
            String signature = ActionLoopGuard.signature(first);
            guard.record(signature, "ACTION_FAILED", "same");
            guard.record(signature, "ACTION_FAILED", "same");
            assertTrue(guard.veto(signature).isPresent(), type);
            assertTrue(guard.veto(ActionLoopGuard.signature(second)).isEmpty(), type);
        }
    }

    @Test
    void craftingDifferentItemsDoesNotShareARepeatSignature() {
        var table = new ActionPlanner.PlannedAction("craft", "1", "minecraft:crafting_table", 0, 0, 0, "");
        var pickaxe = new ActionPlanner.PlannedAction("craft", "1", "minecraft:wooden_pickaxe", 0, 0, 0, "");
        assertFalse(ActionLoopGuard.signature(table).equals(ActionLoopGuard.signature(pickaxe)));
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
