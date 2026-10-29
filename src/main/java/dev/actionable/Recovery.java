package dev.actionable;

/**
 * Decides which failures code can fix without asking the model. The model was slow and unreliable at
 * recovering (it tried "mine 4 minecraft:plank" after a missing table, and placed a table on the
 * player's own feet six times), so known failures are handled here and the model sees only the rest.
 */
final class Recovery {

    private Recovery() {
    }

    /**
     * Failures caused by the world or inventory differing from what the plan assumed. Rebuilding the
     * plan from the current inventory fixes them: it adds the missing tool, table or ingredient.
     */
    static boolean replannable(String result) {
        return result.startsWith("CRAFT_FAILED_NO_TABLE")
                || result.startsWith("CRAFT_FAILED_TABLE_OUT_OF_REACH")
                || result.startsWith("CRAFT_FAILED_TABLE_EXISTS")
                || result.startsWith("CRAFT_FAILED_MISSING_INGREDIENTS")
                || result.startsWith("MINE_FAILED_NO_TOOL")
                || result.startsWith("MINE_FAILED_CRAFTED_ITEM")
                || result.startsWith("BLOCK_PLACE_FAILED_MATERIAL_MISSING")
                || result.startsWith("BUILD_FAILED_NOT_ENOUGH_PLANKS");
    }

    /** Failures about the chosen spot rather than the block, which another nearby spot can avoid. */
    static boolean badPlacementSpot(String result) {
        return result.startsWith("BLOCK_PLACE_FAILED_OUT_OF_REACH")
                || result.startsWith("BLOCK_PLACE_FAILED_PLAYER_IN_WAY")
                || result.startsWith("BLOCK_PLACE_FAILED_TARGET_OCCUPIED")
                || result.startsWith("BLOCK_PLACE_FAILED_NO_HEADROOM")
                || result.startsWith("BLOCK_PLACE_FAILED_NO_SUPPORT")
                || result.startsWith("JUMP_PLACE_FAILED");
    }

    /** Baritone counts as stuck when the player has not moved sideways for this long (5 seconds). */
    static final int SNAG_STALL_TICKS = 100;
    /** Snags cleared at one spot before giving up on clearing there. */
    static final int SNAG_ATTEMPTS = 4;

    /**
     * Soft blocks that trap the player while Baritone walks: vines and similar climbables make the
     * player bob up and down in place, and cobwebs and berry bushes hold them still.
     */
    static boolean isSnag(String block) {
        String path = block.substring(block.indexOf(':') + 1);
        return path.equals("vine") || path.equals("glow_lichen") || path.equals("cobweb")
                || path.equals("sweet_berry_bush") || path.startsWith("cave_vines")
                || path.startsWith("weeping_vines") || path.startsWith("twisting_vines")
                || path.endsWith("_leaves");
    }

    /** What to do with a mine step that code started, each time planning comes round. */
    enum DirectMine { WAIT, RESEND, HAND_OVER }

    /** A running mine that has gathered nothing for this long counts as stuck (90 seconds). */
    static final int DIRECT_STALL_TICKS = 1800;
    /** A walk that has not got closer for this long counts as stuck (30 seconds). */
    static final int WALK_STALL_TICKS = 600;
    /** Seconds in water during a mine (as ticks) before mining is stopped and the player swims to land. */
    static final int WATER_STUCK_TICKS = 160;
    /** Hunts restart after each search walk; about one full ring around home before the model is asked. */
    static final int HUNT_RESENDS = 10;
    /** Re-sends in a row that gather nothing before the model takes over. */
    static final int DIRECT_RESENDS = 2;

    /**
     * Keeps a code-started mine step out of the model's hands while it is working. The model used to
     * be asked every 10 seconds and replaced "mine 20" with "mine 4", which Baritone reads as "hold 4"
     * and finished at once, so wood gathering stalled every few logs.
     */
    static DirectMine directMine(boolean working, long ticksSinceProgress, int resendsWithoutProgress) {
        return directMine(working, ticksSinceProgress, resendsWithoutProgress, DIRECT_RESENDS);
    }

    static DirectMine directMine(boolean working, long ticksSinceProgress, int resendsWithoutProgress, int limit) {
        return directMine(working, ticksSinceProgress, resendsWithoutProgress, limit, DIRECT_STALL_TICKS);
    }

    static DirectMine directMine(boolean working, long ticksSinceProgress, int resendsWithoutProgress, int limit,
            int stallTicks) {
        if (working && ticksSinceProgress < stallTicks) {
            return DirectMine.WAIT;
        }
        return resendsWithoutProgress < limit ? DirectMine.RESEND : DirectMine.HAND_OVER;
    }

    /**
     * Ticks to wait before planning again after an action. Instant failures re-plan after one second;
     * only actions that keep running (Baritone) or a broken model connection wait the full ten.
     */
    static int delayAfter(String actionType, String result) {
        if (result.startsWith("BLOCK_PLACE_DEFERRED")) {
            return 1;
        }
        if (result.contains("_FAILED") || result.startsWith("PLAN_NO_VALID_ACTION")) {
            return 20;
        }
        return switch (actionType) {
            case "attack", "craft", "equip" -> 20;
            case "lookup" -> 1;
            case "place_block", "jump_place" -> 10;
            default -> 200;
        };
    }
}
