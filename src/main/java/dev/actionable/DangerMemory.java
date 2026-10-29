package dev.actionable;

import java.util.HashMap;
import java.util.Map;

/** Recent threat locations excluded from resource selection after an escape. Client-thread owned. */
final class DangerMemory {
    static final long MEMORY_TICKS = 1200;
    static final double RADIUS = 12;
    private record Area(double x, double y, double z, long until) { }
    private final Map<Integer, Area> areas = new HashMap<>();

    void record(ThreatRules.Threat threat, long tick) {
        prune(tick);
        if (threat.entityId() < 0) return;
        if (areas.size() >= 64 && !areas.containsKey(threat.entityId())) {
            areas.entrySet().stream().min(java.util.Comparator.comparingLong(e -> e.getValue().until()))
                    .ifPresent(e -> areas.remove(e.getKey()));
        }
        areas.put(threat.entityId(), new Area(threat.x(), threat.y(), threat.z(), tick + MEMORY_TICKS));
    }

    boolean contains(double x, double y, double z, long tick) {
        prune(tick);
        return areas.values().stream().anyMatch(a -> Math.abs(y - a.y()) <= 8
                && (x - a.x()) * (x - a.x()) + (z - a.z()) * (z - a.z()) <= RADIUS * RADIUS);
    }

    boolean active(long tick) { prune(tick); return !areas.isEmpty(); }
    boolean safe(MiningPolicy.Site site, long tick) {
        return !contains(site.x(), site.y(), site.z(), tick)
                && !contains(site.standX(), site.standY(), site.standZ(), tick);
    }
    void clear() { areas.clear(); }
    private void prune(long tick) { areas.values().removeIf(a -> a.until() <= tick); }
}
