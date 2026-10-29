package dev.actionable;

/** Failed goals wait for new circumstances or a cooldown, rather than instantly restarting. */
final class GoalRetryGuard {
    static final long COOLDOWN_TICKS = 1200;
    private record Failure(String context, long until) { }
    private final java.util.Map<String, Failure> failures = new java.util.HashMap<>();
    private static String key(String goal) {
        return goal.toLowerCase(java.util.Locale.ROOT).replaceAll("\\d+", "").replaceAll("\\s+", " ").strip();
    }
    void failed(String goal, String context, long tick) {
        failures.values().removeIf(f -> f.until() <= tick);
        if (failures.size() >= 64) failures.clear();
        failures.put(key(goal), new Failure(context, tick + COOLDOWN_TICKS));
    }
    boolean available(String goal, String context, long tick) {
        var failure = failures.get(key(goal));
        return failure == null || failure.until() <= tick || !failure.context().equals(context);
    }
    void clear() { failures.clear(); }
}
