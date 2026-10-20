package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThreatRulesTest {
    private static ThreatRules.Threat mob(String kind, double distance, boolean reachable) {
        return new ThreatRules.Threat(1, kind, distance, 64, 0, distance, reachable, true, false);
    }

    @Test
    void drownedAndOtherInherentHostilesDoNotRequireASynchronizedTarget() {
        for (String kind : List.of("minecraft:drowned", "minecraft:zombie", "minecraft:skeleton",
                "minecraft:slime", "minecraft:ghast", "minecraft:hoglin", "minecraft:shulker")) {
            assertTrue(ThreatRules.hostile(kind, true, false, false), kind);
        }
        assertEquals(ThreatRules.Mode.FIGHT,
                ThreatRules.choose(20, List.of(mob("minecraft:drowned", 2, true))).mode());
        assertEquals(ThreatRules.Mode.FLEE,
                ThreatRules.choose(6, List.of(mob("minecraft:drowned", 2, true))).mode());
    }

    @Test
    void neutralMobsAreNotProvokedButObservedAggressionCounts() {
        for (String kind : List.of("minecraft:enderman", "minecraft:piglin", "minecraft:zombified_piglin",
                "minecraft:spider", "minecraft:cave_spider")) {
            assertFalse(ThreatRules.hostile(kind, true, false, false), kind);
            assertTrue(ThreatRules.hostile(kind, true, false, true), kind);
            assertTrue(ThreatRules.hostile(kind, true, true, false), kind);
        }
        assertFalse(ThreatRules.hostile("minecraft:player", false, false, false));
    }

    @Test
    void creepersBossesCrowdsAndRangedThreatsTriggerCodeEscape() {
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(mob("minecraft:creeper", 3, false))).mode());
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(mob("minecraft:warden", 2, false))).mode());
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(mob("minecraft:player", 2, true))).mode());
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(mob("minecraft:skeleton", 10, false))).mode());
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(mob("minecraft:zombie", 2, true),
                mob("minecraft:drowned", 3, true), mob("minecraft:husk", 4, false))).mode());
        assertEquals(ThreatRules.Mode.NONE, ThreatRules.choose(4, List.of()).mode(),
                "low health alone should allow eating and recovery, not an endless flee");
    }

    @Test
    void collisionCourseProjectilesCountButDepartingOrStationaryOnesDoNot() {
        assertTrue(ThreatRules.approaching(10, 0, 0, 1, 0, 0));
        assertFalse(ThreatRules.approaching(10, 0, 0, -1, 0, 0));
        assertFalse(ThreatRules.approaching(10, 5, 0, 1, 0, 0));
        assertFalse(ThreatRules.approaching(1, 0, 0, 0, 0, 0));
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(
                new ThreatRules.Threat(2, "minecraft:arrow", 4, 64, 0, 4, false, true, true))).mode());
    }

    @Test
    void visibleDistantCreeperDoesNotInterruptMiningEvenAtLowHealth() {
        for (float health : new float[] {20, 4}) {
            var decision = ThreatRules.choose(health, List.of(mob("minecraft:creeper", 16, false)));
            assertEquals(ThreatRules.Mode.NONE, decision.mode());
            assertEquals("minecraft:creeper", decision.threat().kind(), "keep watching without fleeing");
        }
        assertEquals(ThreatRules.Mode.NONE,
                ThreatRules.choose(20, List.of(mob("minecraft:zombie", 14, false))).mode());
    }

    @Test
    void wallsAndHeightSeparateObservationFromImmediateThreat() {
        assertEquals(ThreatRules.Mode.NONE, ThreatRules.choose(20, List.of(
                new ThreatRules.Threat(1, "minecraft:creeper", 2, 64, 0, 2, false, false, false))).mode());
        assertEquals(ThreatRules.Mode.NONE, ThreatRules.choose(20, List.of(
                new ThreatRules.Threat(1, "minecraft:creeper", 0, 70, 0, 6, false, true, false,
                        false, false, false, 6))).mode());
        assertEquals(ThreatRules.Mode.NONE, ThreatRules.choose(20, List.of(
                new ThreatRules.Threat(1, "minecraft:zombie", 0, 70, 0, 6, false, true, false,
                        false, true, false, 6))).mode());
    }

    @Test
    void approachingOrPrimedCreeperTriggersBeforeMeleeRange() {
        assertEquals(ThreatRules.Mode.NONE, ThreatRules.choose(20, List.of(mob("minecraft:creeper", 9, false))).mode());
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(
                new ThreatRules.Threat(1, "minecraft:creeper", 9, 64, 0, 9, false, true, false,
                        false, true, false, 0))).mode());
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(
                new ThreatRules.Threat(1, "minecraft:creeper", 11, 64, 0, 11, false, true, false,
                        false, false, true, 0))).mode());
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(
                new ThreatRules.Threat(1, "minecraft:creeper", 5, 64, 0, 5, false, false, false,
                        false, false, true, 0))).mode(), "a nearby active fuse matters through thin cover");
    }

    @Test
    void recentAttackerStillCountsBehindCoverAndNeverAutoAttacksPlayers() {
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(
                new ThreatRules.Threat(1, "minecraft:player", 12, 64, 0, 12, false, false, false,
                        true, false, false, 0))).mode());
        assertEquals(ThreatRules.Mode.FLEE, ThreatRules.choose(20, List.of(
                new ThreatRules.Threat(1, "minecraft:skeleton", 14, 64, 0, 14, false, false, false,
                        true, false, false, 0))).mode());
    }

    @Test
    void onlyEntityMotionTowardPlayerCountsAsClosing() {
        assertTrue(ThreatRules.closing(10, 0, 0.1, 0));
        assertFalse(ThreatRules.closing(10, 0, -0.1, 0));
        assertFalse(ThreatRules.closing(10, 0, 0, 0.1));
        assertFalse(ThreatRules.closing(10, 0, 0, 0));
    }
}
