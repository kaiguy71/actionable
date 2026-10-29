package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WaterSafetyTest {
    @Test
    void submersionTriggersImmediatelyEvenWithFullAir() {
        assertTrue(WaterSafety.rescue(true, true, true, true, false, 300, 300, false));
        assertEquals(0.12, WaterSafety.upwardVelocity(-0.4));
        assertEquals(0.3, WaterSafety.upwardVelocity(0.3));
    }

    @Test
    void rescueContinuesAtSurfaceUntilAirRecoversThenReleases() {
        assertTrue(WaterSafety.rescue(true, true, true, false, false, 60, 300, false));
        assertTrue(WaterSafety.rescue(true, true, true, false, false, 270, 300, true));
        assertFalse(WaterSafety.rescue(true, true, true, false, false, 300, 300, true));
        assertFalse(WaterSafety.rescue(true, true, false, false, false, 100, 300, true));
    }

    @Test
    void respectsExplicitDisableDeathAndUnderwaterBreathing() {
        assertFalse(WaterSafety.rescue(false, true, true, true, false, 0, 300, false));
        assertFalse(WaterSafety.rescue(true, false, true, true, false, 0, 300, false));
        assertFalse(WaterSafety.rescue(true, true, true, true, true, 0, 300, false));
        assertFalse(WaterSafety.rescue(true, true, true, false, false, 300, 300, false));
    }
}
