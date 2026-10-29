package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EscapeRoutesTest {
    @Test
    void escapeAvoidsWaterCliffsBlockedCellsAndWalkingTowardTheThreat() {
        var dry = new EscapeRoutes.Site(-8, 64, 0, true, true, true);
        var water = new EscapeRoutes.Site(-16, 64, 0, false, true, true);
        var cliff = new EscapeRoutes.Site(-16, 54, 0, true, true, true);
        var blocked = new EscapeRoutes.Site(-16, 64, 0, true, false, true);
        var unsupported = new EscapeRoutes.Site(-16, 64, 0, true, true, false);
        var toward = new EscapeRoutes.Site(8, 64, 0, true, true, true);
        assertEquals(dry, EscapeRoutes.choose(List.of(dry, water, cliff, blocked, unsupported, toward),
                0, 64, 0, 10, 0).orElseThrow());
        assertTrue(EscapeRoutes.choose(List.of(water, cliff, toward), 0, 64, 0, 10, 0).isEmpty());
    }
}
