package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ThreatMotionTest {
    @Test
    void stationaryCreeperDoesNotBecomeApproachingBecausePlayerWalksTowardIt() {
        var motion = new ThreatMotion();
        var id = UUID.randomUUID();
        assertFalse(motion.closing(id, 0, 10, 0, 0, 0));
        assertFalse(motion.closing(id, 5, 10, 0, 3, 0));
    }

    @Test
    void repeatedScansDoNotEraseObservedApproach() {
        var motion = new ThreatMotion();
        var id = UUID.randomUUID();
        motion.closing(id, 0, 10, 0, 0, 0);
        assertTrue(motion.closing(id, 5, 9, 0, 0, 0));
        assertTrue(motion.closing(id, 5, 9, 0, 0, 0));
        assertFalse(motion.closing(id, 10, 10, 0, 0, 0));
    }

    @Test
    void oldOrDisconnectedSamplesDoNotInferMotionAcrossMissingObservations() {
        var motion = new ThreatMotion();
        var id = UUID.randomUUID();
        motion.closing(id, 0, 10, 0, 0, 0);
        assertFalse(motion.closing(id, 50, 2, 0, 0, 0));
        motion.clear();
        assertFalse(motion.closing(id, 55, 1, 0, 0, 0));
    }
}
