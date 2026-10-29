package dev.actionable;

/** Focus policy for automated combat; background operation is an explicit test opt-in. */
final class InteractionPolicy {
    private InteractionPolicy() { }

    static boolean mayAttack(boolean background, boolean focused, boolean mouseGrabbed,
            boolean screenOpen, boolean paused) {
        return !screenOpen && !paused && (background || (focused && mouseGrabbed));
    }
}
