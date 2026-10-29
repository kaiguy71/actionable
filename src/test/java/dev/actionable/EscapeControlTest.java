package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EscapeControlTest {
    @Test
    void briefThreatLossDoesNotEndEscapeAndDangerReturningResetsClearTimer() {
        var escape = new EscapeControl();
        assertEquals(ThreatRules.Mode.FLEE, escape.update(ThreatRules.Mode.FLEE, 0));
        assertEquals(ThreatRules.Mode.FLEE, escape.update(ThreatRules.Mode.NONE, 1));
        assertEquals(ThreatRules.Mode.FLEE, escape.update(ThreatRules.Mode.NONE, 40));
        escape.update(ThreatRules.Mode.FLEE, 41);
        escape.update(ThreatRules.Mode.NONE, 42);
        assertEquals(ThreatRules.Mode.FLEE, escape.update(ThreatRules.Mode.NONE, 101));
        assertEquals(ThreatRules.Mode.NONE, escape.update(ThreatRules.Mode.NONE, 102));
    }

    @Test
    void nearbyFightCanReplaceEscapeWithoutWaitingOnAModel() {
        var escape = new EscapeControl();
        escape.update(ThreatRules.Mode.FLEE, 0);
        assertEquals(ThreatRules.Mode.FIGHT, escape.update(ThreatRules.Mode.FIGHT, 1));
        assertEquals(ThreatRules.Mode.NONE, escape.update(ThreatRules.Mode.NONE, 2));
    }

    @Test
    void stoppedOrStalledEscapeIsReissuedInCode() {
        assertTrue(EscapeControl.reroute(20, 0, false, 0));
        assertTrue(EscapeControl.reroute(40, 0, true, 40));
        assertFalse(EscapeControl.reroute(39, 0, true, 39));
        assertFalse(EscapeControl.reroute(5, 0, false, 0));
        assertTrue(EscapeControl.reroute(100, 0, true, 0));
    }
}
