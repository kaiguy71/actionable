package dev.actionable;

/** Emergency breathing takes precedence over every task, including an active Baritone process. */
final class WaterSafety {
    private WaterSafety() { }

    static boolean rescue(boolean enabled, boolean alive, boolean inWater, boolean submerged,
            boolean canBreathe, int air, int maxAir, boolean rescuing) {
        return enabled && alive && inWater && !canBreathe
                && (submerged || air < maxAir / 2 || (rescuing && air < maxAir));
    }

    static double upwardVelocity(double current) {
        return Math.max(0.12, current);
    }
}
