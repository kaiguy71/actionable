package dev.actionable;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Reads Baritone's live state through reflection, so the mod needs no compile-time dependency and
 * keeps working (with movement heuristics) if the API shape changes.
 */
final class BaritoneProbe {
    /** What Baritone is doing right now. {@code process} is its HUD name, e.g. "Mine" or "Exploration". */
    record State(boolean active, String process, boolean pathing) {
        String describe() {
            return active ? process + (pathing ? ",pathing" : ",not_pathing") : "idle";
        }
    }

    private static boolean broken;

    private BaritoneProbe() {
    }

    static Optional<State> query() {
        if (broken) {
            return Optional.empty();
        }
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);
            Object baritone = call(provider, "getPrimaryBaritone");
            Object control = call(baritone, "getPathingControlManager");
            Object pathingBehavior = call(baritone, "getPathingBehavior");
            boolean pathing = Boolean.TRUE.equals(call(pathingBehavior, "isPathing"));
            Optional<?> inControl = (Optional<?>) call(control, "mostRecentInControl");
            if (inControl.isEmpty()) {
                return Optional.of(new State(false, "", pathing));
            }
            Object process = inControl.get();
            boolean active = Boolean.TRUE.equals(call(process, "isActive"));
            String name = String.valueOf(call(process, "displayName"));
            return Optional.of(new State(active, name, pathing));
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            broken = true;
            ActionableLog.LOGGER.warn("[Actionable] baritone_api_unavailable {}", ActionableLog.text(e.toString()));
            return Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
