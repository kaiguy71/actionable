package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AggressorMemoryTest {
    @Test
    void actualAttackersRemainThreatsBrieflyAndDoNotLeakAcrossSessions() {
        var memory = new AggressorMemory();
        var attacker = UUID.randomUUID();
        assertFalse(memory.contains(attacker, 0));
        memory.record(attacker, 0);
        assertTrue(memory.contains(attacker, 1));
        assertFalse(memory.contains(attacker, AggressorMemory.MEMORY_MS));
        memory.record(attacker, 6000);
        assertTrue(memory.contains(attacker, 6001));
        memory.clear();
        assertFalse(memory.contains(attacker, 6001));
    }
}
