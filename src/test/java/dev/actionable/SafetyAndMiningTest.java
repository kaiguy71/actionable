package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SafetyAndMiningTest {
    private static final Map<String, Integer> CAVE_GEAR = Map.of("minecraft:stone_pickaxe", 1,
            "minecraft:stone_sword", 1, "minecraft:torch", 8);

    private static SafetyAssessment.Result assess(int light, boolean sky, int depth, boolean night,
            Map<String, Integer> inventory) {
        return SafetyAssessment.assess(new SafetyAssessment.Inputs(light, sky, depth, night, 20, 20, 0, 0, inventory));
    }

    @Test
    void darkCaveWithoutGearIsDangerAndRetreats() {
        SafetyAssessment.Result result = assess(0, false, 20, false, Map.of());
        assertEquals(SafetyAssessment.Level.DANGER, result.level());
        assertTrue(result.retreat());
        assertEquals(List.of("pickaxe", "weapon"), result.missingForCaves());
        assertTrue(result.describe().contains("level=danger"));
        assertTrue(result.describe().contains("cave_ready=false"));
    }

    @Test
    void darkCaveWithGearIsOnlyCaution() {
        SafetyAssessment.Result result = assess(0, false, 20, false, CAVE_GEAR);
        assertEquals(SafetyAssessment.Level.CAUTION, result.level());
        assertFalse(result.retreat());
        assertTrue(result.caveReady());
    }

    @Test
    void daylightSurfaceIsSafe() {
        SafetyAssessment.Result result = assess(15, true, 0, false, Map.of());
        assertEquals(SafetyAssessment.Level.SAFE, result.level());
        assertFalse(result.retreat());
        assertTrue(result.safeSpot());
    }

    @Test
    void treeCanopyOrShallowPitIsNotUnderground() {
        assertFalse(assess(3, false, 2, false, Map.of()).retreat());
        assertFalse(assess(3, false, 2, false, Map.of()).underground());
    }

    @Test
    void nightOnTheSurfaceWithoutAWeaponIsCaution() {
        assertEquals(SafetyAssessment.Level.CAUTION, assess(4, true, 0, true, Map.of()).level());
        assertEquals(SafetyAssessment.Level.SAFE, assess(4, true, 0, true, Map.of("minecraft:wooden_sword", 1)).level());
        assertFalse(assess(4, true, 0, true, Map.of()).retreat(), "night surface never forces a retreat");
    }

    @Test
    void litUndergroundWithoutGearIsCautionNotRetreat() {
        SafetyAssessment.Result result = assess(12, false, 10, false, Map.of());
        assertEquals(SafetyAssessment.Level.CAUTION, result.level());
        assertFalse(result.retreat());
    }

    @Test
    void lowHealthAndHungerAreReported() {
        SafetyAssessment.Result result = SafetyAssessment.assess(
                new SafetyAssessment.Inputs(15, true, 0, false, 5, 4, 0, 0, Map.of()));
        assertEquals(SafetyAssessment.Level.DANGER, result.level());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("health 5")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("hungry")));
    }

    @Test
    void retreatGoesToTheLastSafeSpotOrStraightUp() {
        assertEquals("goto 10 70 -4", SafetyAssessment.retreatCommand(new int[] {10, 70, -4}, 0, 30, 0, 68));
        assertEquals("goto 0 68 0", SafetyAssessment.retreatCommand(null, 0, 30, 0, 68));
        assertEquals("goto 0 68 0", SafetyAssessment.retreatCommand(new int[] {500, 70, 500}, 0, 30, 0, 68),
                "a far-away safe spot is ignored");
    }

    private static MiningPolicy.Site site(int x, int y, int z, int light, boolean sky, int depth, double distance) {
        return new MiningPolicy.Site(x, y, z, x, y + 1, z, light, sky, depth, distance);
    }

    @Test
    void unequippedPlayerOnlyMinesExposedLitShallowBlocks() {
        MiningPolicy.Site cave = site(0, 40, 0, 0, false, 25, 5);
        MiningPolicy.Site outcrop = site(20, 70, 0, 15, true, 0, 20);
        assertFalse(MiningPolicy.safe(cave, false));
        assertTrue(MiningPolicy.safe(outcrop, false));
        assertEquals(Optional.of(outcrop), MiningPolicy.choose(List.of(cave, outcrop), false, List.of()));
    }

    @Test
    void caveGearAllowsTheNearestDeposit() {
        MiningPolicy.Site cave = site(0, 40, 0, 0, false, 25, 5);
        MiningPolicy.Site outcrop = site(20, 70, 0, 15, true, 0, 20);
        assertEquals(Optional.of(cave), MiningPolicy.choose(List.of(cave, outcrop), true, List.of()));
    }

    @Test
    void litButDeepIsStillUnsafeWithoutGear() {
        assertFalse(MiningPolicy.safe(site(0, 40, 0, 14, false, 20, 5), false));
        assertTrue(MiningPolicy.safe(site(0, 64, 0, 14, false, 4, 5), false));
    }

    @Test
    void retreatedAreasAreAvoided() {
        MiningPolicy.Site near = site(100, 70, 100, 15, true, 0, 4);
        MiningPolicy.Site far = site(0, 70, 0, 15, true, 0, 30);
        assertEquals(Optional.of(far),
                MiningPolicy.choose(List.of(near, far), true, List.of(new int[] {105, 70, 100})));
    }

    @Test
    void noAcceptableSiteExplainsWhy() {
        String reason = MiningPolicy.rejection("minecraft:stone", List.of(site(0, 40, 0, 0, false, 25, 5)),
                List.of("pickaxe", "weapon"));
        assertTrue(reason.startsWith("MINE_FAILED_UNSAFE_SITE"));
        assertTrue(reason.contains("missing pickaxe, weapon"));
        assertTrue(MiningPolicy.choose(List.of(site(0, 40, 0, 0, false, 25, 5)), false, List.of()).isEmpty());
    }

    @Test
    void distantSurfaceDepositsRemainEligibleWithoutGear() {
        MiningPolicy.Site distant = site(160, 70, 0, 15, true, 0, 160);
        assertEquals(distant, MiningPolicy.choose(List.of(distant), false, List.of()).orElseThrow());
    }

    @Test
    void treesAtNightAreStillMinable() {
        // Trunk side under a dark canopy at night: no sky, low light, but at surface level.
        assertTrue(MiningPolicy.safe(site(0, 70, 0, 3, false, 0, 6), false));
        assertTrue(MiningPolicy.safe(site(0, 70, 0, 3, false, 1, 6), false));
        assertFalse(MiningPolicy.safe(site(0, 60, 0, 3, false, 5, 6), false), "a dark pit is still unsafe");
    }

    @Test
    void reachMatchesSurvivalReach() {
        // Standing next to a trunk reaches its lowest four or five logs, not the canopy top.
        assertTrue(MiningPolicy.inReach(0, 64, 0, 1, 64, 0));
        assertTrue(MiningPolicy.inReach(0, 64, 0, 1, 68, 0));
        assertFalse(MiningPolicy.inReach(0, 64, 0, 1, 70, 0));
        assertFalse(MiningPolicy.inReach(0, 64, 0, 6, 64, 0));
        assertTrue(MiningPolicy.inReach(0, 64, 0, 3, 63, 2));
    }

    @Test
    void shelterUnderARoofAtNightIsNotARetreat() {
        // What the client now feeds in at home: depth 0, so the dark roofed house is not "underground".
        SafetyAssessment.Result home = assess(0, false, 0, true, Map.of());
        assertFalse(home.retreat());
        assertFalse(home.underground());
    }

    @Test
    void coalCanBeMinedWithAPickaxeAndWeaponBeforeHavingTorches() {
        // The old rule wanted 4 torches for caves, and torches need coal from caves: a loop with no way out.
        Map<String, Integer> kit = Map.of("minecraft:stone_pickaxe", 1, "minecraft:stone_sword", 1);
        SafetyAssessment.Result cave = assess(0, false, 12, false, kit);
        assertTrue(cave.caveReady());
        assertFalse(cave.retreat());
        assertTrue(cave.reasons().stream().anyMatch(reason -> reason.contains("torches would help")));
        assertTrue(MiningPolicy.safe(site(0, 50, 0, 0, false, 12, 5), cave.caveReady()));
    }
}
