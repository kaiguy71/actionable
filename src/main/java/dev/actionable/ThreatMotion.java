package dev.actionable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Infers approach from observed position changes, rather than unsynchronized mob AI/velocity. */
final class ThreatMotion {
    private record Sample(double x, double z, long tick, boolean closing) { }
    private final Map<UUID, Sample> samples = new HashMap<>();

    boolean closing(UUID id, long tick, double x, double z, double playerX, double playerZ) {
        samples.values().removeIf(sample -> tick - sample.tick() > 40);
        Sample last = samples.get(id);
        if (last == null || tick < last.tick()) {
            samples.put(id, new Sample(x, z, tick, false));
            return false;
        }
        long elapsed = tick - last.tick();
        if (elapsed < 5) return last.closing();
        boolean closing = ThreatRules.closing(playerX - x, playerZ - z,
                (x - last.x()) / elapsed, (z - last.z()) / elapsed);
        samples.put(id, new Sample(x, z, tick, closing));
        return closing;
    }

    void clear() { samples.clear(); }
}
