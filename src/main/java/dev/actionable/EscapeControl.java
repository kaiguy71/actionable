package dev.actionable;

/** An escape remains code-owned until three continuous seconds without a threat. */
final class EscapeControl {
    static final int CLEAR_TICKS = 60;
    private boolean active;
    private long clearSince = -1;

    ThreatRules.Mode update(ThreatRules.Mode observed, long tick) {
        if (observed == ThreatRules.Mode.FIGHT) {
            reset();
            return observed;
        }
        if (observed == ThreatRules.Mode.FLEE) {
            active = true;
            clearSince = -1;
            return observed;
        }
        if (!active) return ThreatRules.Mode.NONE;
        if (clearSince < 0) clearSince = tick;
        if (tick - clearSince < CLEAR_TICKS) return ThreatRules.Mode.FLEE;
        reset();
        return ThreatRules.Mode.NONE;
    }

    static boolean reroute(long tick, long lastRoute, boolean working, long ticksWithoutMovement) {
        return tick - lastRoute >= 20 && (!working || ticksWithoutMovement >= 40 || tick - lastRoute >= 100);
    }

    void reset() { active = false; clearSince = -1; }
}
