package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DangerMemoryTest {
    private static ThreatRules.Threat creeper(int id, double x) {
        return new ThreatRules.Threat(id, "minecraft:creeper", x, 72, -50, 7, false, true, false);
    }

    @Test
    void losingSightDoesNotImmediatelyMakeTheSameTreeSafe() {
        var memory = new DangerMemory();
        memory.record(creeper(807, 2), 0);
        assertTrue(memory.contains(2, 77, -52, 60));
        assertFalse(memory.contains(-30, 72, -50, 60));
        assertFalse(memory.contains(2, 90, -52, 60), "do not blacklist another floor indefinitely");
        assertTrue(memory.active(1199));
        assertFalse(memory.active(1200));
        assertFalse(memory.contains(2, 77, -52, 1200));
    }

    @Test
    void newObservationsUpdatePositionAndExtendExpiry() {
        var memory = new DangerMemory();
        memory.record(creeper(807, 2), 0);
        memory.record(creeper(807, 40), 1000);
        assertFalse(memory.contains(2, 72, -50, 1200));
        assertTrue(memory.contains(40, 72, -50, 1200));
        memory.clear();
        assertFalse(memory.active(1201));
    }

    @Test
    void environmentalDamageDoesNotBlacklistAnArbitraryEntityLocation() {
        var memory = new DangerMemory();
        memory.record(creeper(-1, 2), 0);
        assertFalse(memory.active(0));
    }

    @Test
    void selectsAnotherDepositAndRejectsUnsafeStandingCellEvenForASafeBlock() {
        var memory = new DangerMemory();
        memory.record(creeper(807, 2), 0);
        var oldTree = new MiningPolicy.Site(2, 77, -52, 2, 72, -53, 12, true, 0, 5);
        var safeTree = new MiningPolicy.Site(-30, 74, -50, -30, 72, -50, 15, true, 0, 20);
        var unsafeStand = new MiningPolicy.Site(15, 77, -50, 14, 72, -50, 15, true, 0, 15);
        var sites = java.util.List.of(oldTree, safeTree, unsafeStand).stream()
                .filter(site -> memory.safe(site, 60)).toList();
        assertEquals(java.util.List.of(safeTree), sites);
        assertEquals(safeTree, MiningPolicy.choose(sites, true, java.util.List.of()).orElseThrow());
        assertTrue(memory.safe(oldTree, 1200));
    }
}
