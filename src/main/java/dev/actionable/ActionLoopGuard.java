package dev.actionable;

import java.util.Optional;

/**
 * Detects the planner looping: the same action failing again and again, the same action repeated
 * with nothing changing, or a whole step making no progress. Small models were measured doing all
 * three, so the checks live in code and the model is told about them through the action result.
 *
 * <p>Holds no Minecraft types; the client supplies a progress fingerprint (inventory plus a coarse
 * position) so that "nothing changed" can be decided here and tested on its own.
 */
final class ActionLoopGuard {
    /** An action that failed this many times in a row is not allowed a third attempt. */
    static final int MAX_FAILURES = 2;
    /** The same action repeated this many times with an unchanged fingerprint is vetoed. */
    static final int MAX_REPEATS = 3;
    /** Planning rounds with an unchanged fingerprint before the whole task is considered stuck. */
    static final int MAX_STALLED_ROUNDS = 6;
    /** Bounded credit for observed world work without an inventory change, including random drops. */
    static final int MAX_VERIFIED_WORK = 64;

    private String lastSignature = "";
    private String lastProgress = "";
    private int failures;
    private int repeats;
    private int stalledRounds;
    private boolean lateFailure;
    private String workObjective = "";
    private final java.util.Set<String> workEvents = new java.util.HashSet<>();

    void reset() {
        lastSignature = "";
        lastProgress = "";
        lateFailure = false;
        failures = 0;
        repeats = 0;
        stalledRounds = 0;
        workObjective = "";
        workEvents.clear();
    }

    /** Stable text identity for an action, ignoring the model's wording around it. */
    static String signature(ActionPlanner.PlannedAction action) {
        return switch (action.type()) {
            case "use_item" -> "use_item:" + action.block() + ':' + action.command() + '@'
                    + action.x() + ',' + action.y() + ',' + action.z();
            case "place_block", "jump_place", "use_block", "break_block" ->
                    action.type() + ':' + action.block() + '@' + action.x() + ',' + action.y() + ',' + action.z();
            case "craft" -> "craft:" + action.block() + ":" + action.command();
            case "menu" -> "menu:" + action.command() + ":" + action.x() + ":" + action.y();
            case "lookup" -> "lookup:" + action.query().strip().toLowerCase(java.util.Locale.ROOT);
            case "wait" -> "wait";
            default -> action.type() + ':' + action.command().strip().toLowerCase(java.util.Locale.ROOT);
        };
    }

    static boolean isFailure(String result) {
        // BUSY means "try again in a moment" (cursor or container open), not a bad choice of action.
        if (result.contains("_BUSY")) {
            return false;
        }
        return result.contains("_FAILED") || result.startsWith("REPEAT_BLOCKED")
                || result.startsWith("STEP_COMPLETION_REJECTED")
                || result.startsWith("Baritone rejected") || result.startsWith("Unsupported");
    }

    /** Returns a model-facing reason when {@code signature} should not run again, else empty. */
    Optional<String> veto(String signature) {
        if (signature.equals("wait") || !signature.equals(lastSignature)) {
            return Optional.empty();
        }
        if (failures >= MAX_FAILURES) {
            return Optional.of("REPEAT_BLOCKED: '" + signature + "' already failed " + failures
                    + " times in a row. Choose a different action or approach; do not retry it.");
        }
        // Repeated combat hits legitimately change nothing the fingerprint can see.
        if (!signature.startsWith("attack:") && repeats >= MAX_REPEATS) {
            return Optional.of("REPEAT_BLOCKED: '" + signature + "' was issued " + repeats
                    + " times with no observed progress. It is not helping; "
                    + "choose a different action, or return step_complete if the step is satisfied.");
        }
        return Optional.empty();
    }

    /** Records one planning round. {@code progress} is any text that changes when the player advances. */
    void record(String signature, String result, String progress) {
        boolean same = signature.equals(lastSignature);
        boolean progressed = observe(progress);
        // A rejection that arrived after the previous round was recorded must survive this round's
        // optimistic "started" result, or the same rejected command would never reach the limit.
        boolean carriedFailure = same && lateFailure;
        lateFailure = false;
        failures = isFailure(result) ? (same ? failures + 1 : 1) : carriedFailure ? failures : 0;
        repeats = same && !progressed ? repeats + 1 : 1;
        stalledRounds = progressed ? 0 : stalledRounds + 1;
        lastSignature = signature;
        lastProgress = progress;
    }

    /** Check fresh evidence before vetoing, rather than waiting until after an action is blocked. */
    boolean observe(String progress) {
        if (progress.equals(lastProgress)) return false;
        lastProgress = progress;
        repeats = 0;
        stalledRounds = 0;
        return true;
    }

    /** Called only for a verified world change, never for a model's claim or an action being sent. */
    boolean recordWork(String event, String objective) {
        if (!objective.equals(workObjective)) {
            workObjective = objective;
            workEvents.clear();
        }
        if (workEvents.contains(event) || workEvents.size() >= MAX_VERIFIED_WORK) return false;
        workEvents.add(event);
        failures = repeats = stalledRounds = 0;
        lateFailure = false;
        return true;
    }

    /** For failures that arrive after the round was recorded, such as Baritone's chat rejection. */
    void markLastFailed() {
        if (!lastSignature.isEmpty()) {
            failures++;
            lateFailure = true;
        }
    }

    boolean stalled() {
        return stalledRounds >= MAX_STALLED_ROUNDS;
    }

    int stalledRounds() {
        return stalledRounds;
    }
}
