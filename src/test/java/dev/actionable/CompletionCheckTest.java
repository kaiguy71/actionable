package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompletionCheckTest {
    @Test
    void insufficientItemsAndMissingHouseOverrideModelClaims() {
        assertEquals(StepCheck.Verdict.NOT_DONE, CompletionCheck.verdict("mine 20 minecraft:oak_log",
                Map.of("minecraft:oak_log", 7), false, ProgressPlanner.Facts.NONE));
        assertEquals(StepCheck.Verdict.NOT_DONE, CompletionCheck.verdict(ProgressPlanner.PLACE_TABLE_IN_HOUSE,
                Map.of("minecraft:crafting_table", 1), true, ProgressPlanner.Facts.NONE));
        assertFalse(CompletionCheck.mayComplete(false, 0, 2, StepCheck.Verdict.NOT_DONE, StepCheck.Verdict.UNKNOWN));
    }

    @Test
    void taskCannotFinishEarlyOrWithUnmetFinalObjective() {
        assertFalse(CompletionCheck.mayComplete(true, 0, 2, StepCheck.Verdict.DONE, StepCheck.Verdict.DONE));
        assertFalse(CompletionCheck.mayComplete(true, 1, 2, StepCheck.Verdict.DONE, StepCheck.Verdict.NOT_DONE));
        assertTrue(CompletionCheck.mayComplete(true, 1, 2, StepCheck.Verdict.DONE, StepCheck.Verdict.DONE));
        assertTrue(CompletionCheck.mayComplete(false, 0, 2, StepCheck.Verdict.UNKNOWN, StepCheck.Verdict.UNKNOWN));
    }
}
