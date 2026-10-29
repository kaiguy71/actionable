package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlannerRequestsTest {
    @Test
    void escapeInterruptsDirectorAndItsLateReplyCannotLeaveThePlannerBusy() {
        var requests = new PlannerRequests();
        long director = requests.begin(PlannerRequests.Phase.DIRECTOR);
        assertTrue(requests.choosingGoal);
        requests.invalidate();
        assertFalse(requests.complete(director));
        assertFalse(requests.busy());
        long next = requests.begin(PlannerRequests.Phase.DIRECTOR);
        assertTrue(requests.complete(next));
        assertFalse(requests.busy());
    }

    @Test
    void oldCallbackCannotClearANewerRequest() {
        var requests = new PlannerRequests();
        long old = requests.begin(PlannerRequests.Phase.ACTION);
        requests.invalidate();
        long current = requests.begin(PlannerRequests.Phase.DECOMPOSITION);
        assertFalse(requests.complete(old));
        assertTrue(requests.decomposing);
        assertTrue(requests.complete(current));
        assertFalse(requests.busy());
    }

    @Test
    void everyRequestKindIsReleasedByInterruption() {
        for (var phase : PlannerRequests.Phase.values()) {
            var requests = new PlannerRequests();
            long token = requests.begin(phase);
            assertTrue(requests.busy());
            requests.invalidate();
            assertFalse(requests.busy());
            assertFalse(requests.complete(token));
        }
    }
}
