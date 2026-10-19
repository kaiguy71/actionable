package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecoveryTest {
    @Test
    void plannerFixableFailuresReplanInCode() {
        assertTrue(Recovery.replannable("CRAFT_FAILED_NO_TABLE: minecraft:stone_sword needs a 3x3 crafting table."));
        assertTrue(Recovery.replannable("CRAFT_FAILED_TABLE_OUT_OF_REACH: crafting table at 1,2,3"));
        assertTrue(Recovery.replannable("CRAFT_FAILED_MISSING_INGREDIENTS: one minecraft:stick needs"));
        assertTrue(Recovery.replannable("MINE_FAILED_NO_TOOL: minecraft:stone drops nothing"));
        assertTrue(Recovery.replannable("BLOCK_PLACE_FAILED_MATERIAL_MISSING: minecraft:crafting_table"));
        assertTrue(Recovery.replannable("BUILD_FAILED_NOT_ENOUGH_PLANKS: the house needs 71 planks"));
    }

    @Test
    void otherResultsGoToTheModel() {
        assertFalse(Recovery.replannable("MINE_FAILED_UNSAFE_SITE: every loaded minecraft:stone"));
        assertFalse(Recovery.replannable("CRAFTED: made 1 craft(s)"));
        assertFalse(Recovery.replannable("Re-planned from inventory after: CRAFT_FAILED_NO_TABLE"),
                "a re-plan note must not trigger another re-plan");
        assertFalse(Recovery.replannable("Started Baritone command"));
    }

    @Test
    void spotProblemsTryAnotherSpot() {
        assertTrue(Recovery.badPlacementSpot("BLOCK_PLACE_FAILED_PLAYER_IN_WAY: target intersects the player"));
        assertTrue(Recovery.badPlacementSpot("BLOCK_PLACE_FAILED_OUT_OF_REACH: move within 4.5 blocks"));
        assertTrue(Recovery.badPlacementSpot("JUMP_PLACE_FAILED_JUMP_TIMEOUT: Player did not clear the target cell"));
        assertFalse(Recovery.badPlacementSpot("BLOCK_PLACE_FAILED_MATERIAL_MISSING: not in inventory"));
        assertFalse(Recovery.badPlacementSpot("BLOCK_PLACE_DEFERRED_TARGET_CLEARED: removed obstruction"));
        assertFalse(Recovery.badPlacementSpot("placement request sent for minecraft:crafting_table"));
    }

    @Test
    void instantFailuresReplanWithinASecond() {
        assertEquals(20, Recovery.delayAfter("place_block", "BLOCK_PLACE_FAILED_NO_SUPPORT: no support"));
        assertEquals(20, Recovery.delayAfter("baritone", "MINE_FAILED_NO_TOOL: x"));
        assertEquals(20, Recovery.delayAfter("", "PLAN_NO_VALID_ACTION"));
        assertEquals(1, Recovery.delayAfter("place_block", "BLOCK_PLACE_DEFERRED_TARGET_CLEARED: x"));
        assertEquals(1, Recovery.delayAfter("lookup", "Local registry matches"));
        assertEquals(10, Recovery.delayAfter("place_block", "placement request sent"));
    }

    @Test
    void runningBaritoneCommandsStillGetTimeToWork() {
        // Baritone completion is detected separately and triggers an immediate re-plan.
        assertEquals(200, Recovery.delayAfter("baritone", "Started Baritone command; baritone_task=running"));
        assertEquals(200, Recovery.delayAfter("wait", "Planner chose to wait"));
    }

    @Test
    void aWorkingMineIsLeftAloneUntilItStalls() {
        assertEquals(Recovery.DirectMine.WAIT, Recovery.directMine(true, 200, 0));
        assertEquals(Recovery.DirectMine.WAIT, Recovery.directMine(true, Recovery.DIRECT_STALL_TICKS - 1, 5),
                "the model is never asked while Baritone is still gathering");
        assertEquals(Recovery.DirectMine.RESEND, Recovery.directMine(true, Recovery.DIRECT_STALL_TICKS, 0));
    }

    @Test
    void aMineThatStoppedShortIsResentBeforeTheModelIsAsked() {
        assertEquals(Recovery.DirectMine.RESEND, Recovery.directMine(false, 0, 0));
        assertEquals(Recovery.DirectMine.RESEND, Recovery.directMine(false, 0, 1));
        assertEquals(Recovery.DirectMine.HAND_OVER, Recovery.directMine(false, 0, Recovery.DIRECT_RESENDS));
    }

    @Test
    void huntingGetsMoreRestartsThanOtherSteps() {
        assertEquals(Recovery.DirectMine.RESEND, Recovery.directMine(false, 0, 4, 5));
        assertEquals(Recovery.DirectMine.HAND_OVER, Recovery.directMine(false, 0, 5, 5));
    }

    @Test
    void vinesAndSimilarCountAsSnags() {
        for (String block : new String[] {"minecraft:vine", "minecraft:glow_lichen", "minecraft:cobweb",
                "minecraft:cave_vines_plant", "minecraft:weeping_vines", "minecraft:oak_leaves",
                "minecraft:sweet_berry_bush"}) {
            assertTrue(Recovery.isSnag(block), block);
        }
        for (String block : new String[] {"minecraft:dirt", "minecraft:oak_log", "minecraft:stone",
                "minecraft:ladder", "minecraft:water"}) {
            assertFalse(Recovery.isSnag(block), block);
        }
    }

    @Test
    void walksAreRetriedSoonerThanGathering() {
        assertEquals(Recovery.DirectMine.WAIT,
                Recovery.directMine(true, Recovery.WALK_STALL_TICKS - 1, 0, 2, Recovery.WALK_STALL_TICKS));
        assertEquals(Recovery.DirectMine.RESEND,
                Recovery.directMine(true, Recovery.WALK_STALL_TICKS, 0, 2, Recovery.WALK_STALL_TICKS));
        assertEquals(Recovery.DirectMine.WAIT, Recovery.directMine(true, Recovery.WALK_STALL_TICKS, 0, 2),
                "gathering keeps the longer 90 second window");
    }
}
