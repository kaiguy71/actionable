package dev.actionable;

import com.google.gson.Gson;
import java.util.List;

/**
 * Snapshot of the local environment sent to Gemma (FR-1). Plain data only, so it can be built on the game
 * thread and serialized on a worker thread.
 */
record TacticalState(double health, List<String> hotbar, String mob, double distance, String baritone,
        double px, double py, double pz, double mx, double my, double mz,
        boolean mobCharging, boolean mobDrinking, boolean mobShooting,
        boolean hasShield, boolean hasGoldArmor, boolean waterNearby, boolean shriekerNearby, int[] cover) {
    private static final Gson GSON = new Gson();

    String toJson() {
        return GSON.toJson(this);
    }
}
