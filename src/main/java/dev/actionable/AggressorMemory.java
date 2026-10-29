package dev.actionable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Brief memory of actual damage sources, including neutral mobs whose AI target is not synced. */
final class AggressorMemory {
    static final long MEMORY_MS = 5000;
    private final ConcurrentHashMap<UUID, Long> until = new ConcurrentHashMap<>();

    void record(UUID attacker, long now) {
        until.entrySet().removeIf(entry -> entry.getValue() <= now);
        until.put(attacker, now + MEMORY_MS);
    }

    boolean contains(UUID attacker, long now) {
        return until.getOrDefault(attacker, Long.MIN_VALUE) > now;
    }

    void clear() { until.clear(); }
}
