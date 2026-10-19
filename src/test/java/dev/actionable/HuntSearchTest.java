package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HuntSearchTest {
    private static final int[] HOME = {38, 63, -8};

    @Test
    void legsGoBeyondWhatIsAlreadyLoadedAroundHome() {
        // Animals within about 100 blocks are already visible from home, so searching there finds nothing new.
        int[] first = HuntSearch.leg(HOME, 0);
        assertEquals(HuntSearch.FIRST_LEG, HomeRange.distance(HOME, first[0], first[2]), 1.0);
        assertTrue(HuntSearch.FIRST_LEG > 100);
    }

    @Test
    void legsTurnToTheNeighbouringDirectionThenGoFarther() {
        int[] north = HuntSearch.leg(HOME, 0);
        int[] northEast = HuntSearch.leg(HOME, 1);
        assertEquals(HOME[0], north[0]);
        assertTrue(north[2] < HOME[2], "first leg goes north (-z)");
        assertTrue(northEast[0] > HOME[0] && northEast[2] < HOME[2], "then north-east");
        int[] secondRound = HuntSearch.leg(HOME, 8);
        assertEquals(HuntSearch.FIRST_LEG + HuntSearch.LEG_STEP, HomeRange.distance(HOME, secondRound[0], secondRound[2]), 1.0);
        int[] far = HuntSearch.leg(HOME, 100);
        assertTrue(HomeRange.distance(HOME, far[0], far[2]) <= HuntSearch.MAX_LEG + 1);
    }

    @Test
    void theReportShowsWeaponAndFood() {
        NeedsPlanner.Situation day = new NeedsPlanner.Situation(new ProgressPlanner.Facts(true, false, true, true,
                false, true, false, false), false, false, true);
        String bare = NeedsPlanner.report(Map.of("minecraft:sweet_berries", 3), day);
        assertTrue(bare.contains("weapon=none, food=6 hunger points"), bare);
        String armed = NeedsPlanner.report(Map.of("minecraft:wooden_sword", 1), day);
        assertTrue(armed.contains("weapon=minecraft:wooden_sword"), armed);
    }
}
