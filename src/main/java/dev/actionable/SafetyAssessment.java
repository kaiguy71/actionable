package dev.actionable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Judges how safe the player's surroundings are from light, depth, time of day and gear. The model
 * receives the result as text; the client also retreats in code, because the model walked into dark
 * caves bare-handed.
 */
final class SafetyAssessment {
    /** Light at or below this is dark enough for hostile mobs to spawn nearby and hard to see in. */
    static final int DARK_LIGHT = 4;
    /** Blocks below the local surface at which the player counts as underground. */
    static final int UNDERGROUND_DEPTH = 4;
    static final int CAVE_TORCHES = 4;
    static final int LOW_HEALTH = 6;
    static final int LOW_FOOD = 6;

    private SafetyAssessment() {
    }

    enum Level { SAFE, CAUTION, DANGER }

    record Inputs(int light, boolean skyVisible, int depthBelowSurface, boolean night, int health, int food,
            int armor, int hostilesNearby, Map<String, Integer> inventory) {
    }

    record Result(Level level, int light, boolean skyVisible, int depthBelowSurface, boolean night,
            boolean underground, boolean dark, boolean caveReady, List<String> missingForCaves,
            List<String> reasons) {
        /** True when the player should be pulled out of where they are regardless of the task. */
        boolean retreat() {
            return underground && dark && !caveReady;
        }

        /** A spot good enough to remember as somewhere to retreat to. */
        boolean safeSpot() {
            return !underground && (skyVisible || light > 7);
        }

        String describe() {
            return "safety={level=" + level.name().toLowerCase(Locale.ROOT) + ",light=" + light
                    + ",sky_visible=" + skyVisible + ",depth_below_surface=" + depthBelowSurface
                    + ",night=" + night + ",underground=" + underground + ",cave_ready=" + caveReady
                    + ",missing_for_caves=" + missingForCaves + ",reasons=" + reasons + "}";
        }
    }

    static List<String> missingForCaves(Map<String, Integer> inventory) {
        List<String> missing = new ArrayList<>();
        if (TechTree.pickaxeTier(inventory) == TechTree.Tier.NONE) {
            missing.add("pickaxe");
        }
        if (inventory.entrySet().stream().noneMatch(entry -> entry.getValue() > 0 && TechTree.isWeapon(entry.getKey()))) {
            missing.add("weapon");
        }
        if (inventory.getOrDefault("minecraft:torch", 0) < CAVE_TORCHES) {
            missing.add(CAVE_TORCHES + "+ torches");
        }
        return missing;
    }

    static Result assess(Inputs in) {
        boolean underground = !in.skyVisible() && in.depthBelowSurface() >= UNDERGROUND_DEPTH;
        boolean dark = in.light() <= DARK_LIGHT;
        List<String> missing = missingForCaves(in.inventory());
        boolean caveReady = missing.isEmpty();
        boolean hasWeapon = !missing.contains("weapon");
        List<String> reasons = new ArrayList<>();
        Level level = Level.SAFE;

        if (in.health() <= LOW_HEALTH) {
            reasons.add("health " + in.health() + " is low: avoid fights and get somewhere safe");
            level = Level.DANGER;
        }
        if (underground && dark && !caveReady) {
            reasons.add("dark underground without cave gear (missing " + String.join(", ", missing)
                    + "): return to the surface or a lit area");
            level = Level.DANGER;
        } else if (underground && !caveReady) {
            reasons.add("underground without cave gear: stay near light and do not go deeper");
            level = max(level, Level.CAUTION);
        } else if (dark && underground) {
            reasons.add("dark underground: place torches and watch for mobs");
            level = max(level, Level.CAUTION);
        }
        if (in.night() && !underground && !hasWeapon) {
            reasons.add("night on the surface without a weapon: stay near light and avoid open ground");
            level = max(level, Level.CAUTION);
        }
        if (in.hostilesNearby() > 2) {
            reasons.add(in.hostilesNearby() + " hostile mobs nearby");
            level = max(level, Level.CAUTION);
        }
        if (in.food() <= LOW_FOOD) {
            reasons.add("hungry (food " + in.food() + "): health will not regenerate");
            level = max(level, Level.CAUTION);
        }
        if (in.armor() == 0 && underground) {
            reasons.add("no armor underground");
        }
        return new Result(level, in.light(), in.skyVisible(), in.depthBelowSurface(), in.night(), underground,
                dark, caveReady, List.copyOf(missing), List.copyOf(reasons));
    }

    /**
     * Baritone goto that returns the player to safety: the last remembered safe spot when there is one
     * within range, otherwise straight up to the surface above the player.
     */
    static String retreatCommand(int[] lastSafe, int x, int y, int z, int surfaceY) {
        if (lastSafe != null) {
            double dx = lastSafe[0] - x;
            double dy = lastSafe[1] - y;
            double dz = lastSafe[2] - z;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= 96) {
                return "goto " + lastSafe[0] + " " + lastSafe[1] + " " + lastSafe[2];
            }
        }
        return "goto " + x + " " + Math.max(surfaceY, y + 1) + " " + z;
    }

    private static Level max(Level first, Level second) {
        return first.compareTo(second) >= 0 ? first : second;
    }
}
