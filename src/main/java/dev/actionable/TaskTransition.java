package dev.actionable;

import java.util.Objects;
import java.util.Optional;

/**
 * The one task-transition boundary (TASK-001, ORCH-002, PD-003). It separates three things that used to
 * be interleaved: invalidating the task that owns the slot, cleaning that task's local work, and
 * installing a new task into the clean slot. It also holds the single pending manual replacement and
 * formats the executor status, including a cancel that was requested but never confirmed (OBS-002).
 *
 * <p>Invalidation advances the {@link TaskControl} generation exactly once per transition; installation
 * never advances it. Everything runs on the Minecraft client thread, like {@link TaskControl}. This class
 * has no Minecraft imports: {@link Cleanup} is implemented by {@code ActionableClient}.
 */
final class TaskTransition {
    static final int MAX_STATUS_COMMAND_LENGTH = 80;
    static final int MAX_PROMPT_LENGTH = 1000;
    private static final int TICKS_PER_SECOND = 20;

    /**
     * Task transitions that current behavior needs. Every kind clears the ended task's escape and
     * retreat overlays; none clears defense, which is task-independent. No kind preserves the ended
     * objective in Milestone 1; only the pending replacement survives a transition.
     */
    enum Kind {
        /** {@code /action} hard replacement (PD-003): cancel forced, the caller installs the replacement. */
        MANUAL_REPLACEMENT(true, true),
        /** Escape or retreat taking over: belief-gated cancel, the caller installs the reflex task. */
        REFLEX_START(false, true),
        /** Completion: keeps the legacy unconditional cancel, then the pending replacement is resolved. */
        TASK_FINISHED(true, false),
        /** Abandonment, timeout, autonomy off, threat cleared, safety reached, session lost: belief-gated. */
        TASK_ENDED(false, false);

        private final boolean forcesCancel;
        private final boolean installsReplacement;

        Kind(boolean forcesCancel, boolean installsReplacement) {
            this.forcesCancel = forcesCancel;
            this.installsReplacement = installsReplacement;
        }

        /** True when the cancel is requested whatever the running belief says. */
        boolean forcesCancel() {
            return forcesCancel;
        }

        /** True when the caller installs a replacement; false when it resolves the pending replacement. */
        boolean installsReplacement() {
            return installsReplacement;
        }
    }

    /** What happened to the external executor. None of these means the executor confirmed anything. */
    enum ExecutorCancel {
        /** Not forced and not believed running: nothing sent. */
        NOT_NEEDED,
        /** One {@code #cancel} was sent; confirmation is never awaited. */
        REQUESTED,
        /** A cancel was needed but no connection exists; the executor state is now unknown. */
        BRIDGE_UNAVAILABLE,
        /** Baritone is not loaded, so {@code #cancel} would only be public chat: nothing sent. */
        NO_EXECUTOR,
        /** The executor step threw; the state was marked unknown where possible. Local cleanup still runs. */
        ATTEMPT_FAILED
    }

    /**
     * What installed the task in the slot. Only AUTONOMOUS tasks end when autonomous goal selection is
     * turned off; a manual task and a running reflex continue.
     */
    enum Source { MANUAL, AUTONOMOUS, REFLEX }

    /** CLEAN accepts one installation; OCCUPIED holds a task; CLEANUP_FAILED holds whatever a failed cleanup left. */
    private enum SlotState { CLEAN, OCCUPIED, CLEANUP_FAILED }

    /** The executor facts the cancel decision needs. */
    record ExecutorView(boolean believedRunning, boolean executorInstalled, boolean bridgeAvailable) {
    }

    /** ActionableClient's side of a task invalidation, called in this order, once each, by {@link #invalidateTask}. */
    interface Cleanup {
        ExecutorView executorView();

        /** Sends one {@code #cancel} and records it as requested. */
        void sendExecutorCancel();

        /** Records that a needed cancel could not be sent. */
        void markExecutorUnknown();

        /** Cancels and clears task-owned local execution: craft, jump placement, deferred mine, mine leash. */
        void clearLocalWork();

        /** Clears the task, its origin, plan, step and retry state, and the escape and retreat overlays. */
        void clearTaskState();
    }

    /**
     * One bounded transition record. {@code clean} is true only when the required cleanup succeeded;
     * {@code nested} means a re-entrant call that did nothing; {@code executorFailure} is the executor
     * step's exception, if any, which does not by itself prevent a clean slot.
     */
    record Report(Kind kind, String reason, long generation, ExecutorCancel executor, RuntimeException executorFailure,
            boolean nested, boolean clean) {
    }

    /**
     * Required cleanup failed: the slot is CLEANUP_FAILED and nothing can be installed until a later
     * transition cleans it. The cause is the first failure in stage order; later ones are suppressed.
     */
    static final class CleanupFailedException extends RuntimeException {
        private final transient Report report;

        CleanupFailedException(Report report, RuntimeException... inStageOrder) {
            super("TASK_CLEANUP_FAILED: kind=" + report.kind() + " generation=" + report.generation());
            this.report = report;
            aggregate(this, inStageOrder);
        }

        Report report() {
            return report;
        }
    }

    /**
     * Result of {@link #retryCleanup}. {@code attempted} is false, and nothing ran, unless the slot was
     * CLEANUP_FAILED with no transition running. When the retry fails, {@code failure} aggregates this
     * retry's stage failures (first as cause, later suppressed) and the slot stays CLEANUP_FAILED.
     */
    record Recovery(boolean attempted, boolean clean, RuntimeException failure) {
    }

    /** {@code target}, with the first failure in stage order as its cause and later ones suppressed. */
    private static <E extends RuntimeException> E aggregate(E target, RuntimeException... inStageOrder) {
        for (RuntimeException failure : inStageOrder) {
            if (failure == null) {
                continue;
            }
            if (target.getCause() == null) {
                target.initCause(failure);
            } else {
                target.addSuppressed(failure);
            }
        }
        return target;
    }

    /** The one pending manual replacement: a serial, the submitted text and the packed submission origin. */
    record Pending(long serial, String prompt, long packedOrigin) {
        Pending {
            Objects.requireNonNull(prompt, "prompt");
        }
    }

    /** Fresh facts for revalidating a pending replacement. Autonomy is deliberately absent: manual tasks run without it. */
    record PendingContext(boolean enabled, boolean playerPresent, boolean worldPresent, boolean connectionPresent,
            boolean executorInstalled, boolean dangerRequiresEscape) {
    }

    enum Outcome {
        /** Nothing to install: no entry, a superseded serial, or an occupied slot. Any entry is kept. */
        NONE,
        /** Revalidated and taken from the slot; the caller installs it into the clean slot. */
        INSTALL,
        /** Danger still requires the mandatory escape; the entry stays pending. */
        KEEP_FOR_ESCAPE,
        /** Revalidation failed; the entry was removed and nothing is installed. */
        DISCARD
    }

    record Decision(Outcome outcome, Pending pending, String reason) {
        Decision {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum CancelState { REQUESTED, UNKNOWN }

    /** The latest executor cancel only, never a history: bounded command, client tick and state. */
    record CancelRecord(CancelState state, String command, long requestedAtTick) {
        CancelRecord {
            Objects.requireNonNull(state, "state");
            command = boundedCommand(command);
        }
    }

    private final TaskControl requests;
    private SlotState slot = SlotState.CLEAN;
    /** The installed task's source: null when CLEAN; a failed cleanup keeps whatever was installed. */
    private Source installed;
    private boolean transitioning;
    private Pending pending;
    private long nextPendingSerial;

    TaskTransition(TaskControl requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    /**
     * Invalidates the task in the slot exactly once. The request generation advances first, so every
     * outstanding model result is stale before any task field is touched. Then, best effort and in this
     * order: the executor step (at most one cancel), local work, task state. Every stage is attempted
     * even if an earlier one failed. An executor failure leaves the executor state unknown but does not
     * block local replacement; a local-work or task-state failure leaves the slot CLEANUP_FAILED and
     * throws {@link CleanupFailedException}. The pending replacement is never touched, nothing is
     * installed, and a nested call during cleanup returns a {@code nested} report without acting.
     */
    Report invalidateTask(Kind kind, String reason, Cleanup cleanup) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cleanup, "cleanup");
        if (transitioning) {
            return new Report(kind, reason, requests.generation(), ExecutorCancel.NOT_NEEDED, null, true, false);
        }
        transitioning = true;
        try {
            long generation = requests.invalidateRequests();
            ExecutorCancel executor;
            RuntimeException executorFailure = null;
            boolean unknownMarked = false;
            try {
                // A pending replacement turns this end into the second half of a hard replacement.
                executor = cancelDecision(kind.forcesCancel() || pending != null, cleanup.executorView());
                if (executor == ExecutorCancel.REQUESTED) {
                    cleanup.sendExecutorCancel();
                } else if (executor == ExecutorCancel.BRIDGE_UNAVAILABLE) {
                    unknownMarked = true;
                    cleanup.markExecutorUnknown();
                }
            } catch (RuntimeException failure) {
                executor = ExecutorCancel.ATTEMPT_FAILED;
                executorFailure = failure;
                if (!unknownMarked) {
                    try {
                        cleanup.markExecutorUnknown();
                    } catch (RuntimeException unknownFailure) {
                        failure.addSuppressed(unknownFailure);
                    }
                }
            }
            RuntimeException localFailure = attempt(cleanup::clearLocalWork);
            RuntimeException stateFailure = attempt(cleanup::clearTaskState);
            if (localFailure == null && stateFailure == null) {
                slot = SlotState.CLEAN;
                installed = null;
                return new Report(kind, reason, generation, executor, executorFailure, false, true);
            }
            slot = SlotState.CLEANUP_FAILED;
            throw new CleanupFailedException(new Report(kind, reason, generation, executor, executorFailure, false, false),
                    executorFailure, localFailure, stateFailure);
        } finally {
            // Only the guard is released unconditionally; slot cleanliness reflects what cleanup achieved.
            transitioning = false;
        }
    }

    /**
     * Retries only the required cleanup of a CLEANUP_FAILED slot: local work, then task state, each
     * attempted. It is not a transition: the failed transition already invalidated requests and handled
     * the executor, so this neither touches TaskControl nor observes or cancels the executor, and it never
     * reads, consumes or installs the pending replacement. Success makes the slot CLEAN and clears the
     * installed source; failure keeps both. In any other state, or during a transition, it does nothing.
     */
    Recovery retryCleanup(Cleanup cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        if (slot != SlotState.CLEANUP_FAILED || transitioning) {
            return new Recovery(false, false, null);
        }
        transitioning = true;
        try {
            RuntimeException localFailure = attempt(cleanup::clearLocalWork);
            RuntimeException stateFailure = attempt(cleanup::clearTaskState);
            if (localFailure == null && stateFailure == null) {
                slot = SlotState.CLEAN;
                installed = null;
                return new Recovery(true, true, null);
            }
            return new Recovery(true, false,
                    aggregate(new IllegalStateException("TASK_CLEANUP_RETRY_FAILED"), localFailure, stateFailure));
        } finally {
            transitioning = false;
        }
    }

    private static RuntimeException attempt(Runnable stage) {
        try {
            stage.run();
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    static ExecutorCancel cancelDecision(boolean forced, ExecutorView view) {
        if (!view.executorInstalled()) {
            return ExecutorCancel.NO_EXECUTOR;
        }
        if (!forced && !view.believedRunning()) {
            return ExecutorCancel.NOT_NEEDED;
        }
        return view.bridgeAvailable() ? ExecutorCancel.REQUESTED : ExecutorCancel.BRIDGE_UNAVAILABLE;
    }

    /**
     * Claims a CLEAN slot for a task from {@code source}. Never invalidates; false, claiming nothing,
     * if the slot is occupied, faulted by a failed cleanup, or mid-transition.
     */
    boolean markInstalled(Source source) {
        if (slot != SlotState.CLEAN || transitioning) {
            return false;
        }
        slot = SlotState.OCCUPIED;
        installed = Objects.requireNonNull(source, "source");
        return true;
    }

    boolean isSlotClean() {
        return slot == SlotState.CLEAN && !transitioning;
    }

    /** True after a required cleanup stage failed, until a later transition cleans the slot. */
    boolean cleanupFailed() {
        return slot == SlotState.CLEANUP_FAILED;
    }

    Optional<Source> installedSource() {
        return Optional.ofNullable(installed);
    }

    /** Turning autonomous goal selection off ends only a task the director installed. */
    boolean autonomyDisableEndsInstalledTask() {
        return installed == Source.AUTONOMOUS;
    }

    /** Stores the one pending replacement, newest wins; returns the entry it superseded so the caller can log it. */
    Optional<Pending> storePending(String prompt, long packedOrigin) {
        Pending superseded = pending;
        pending = new Pending(++nextPendingSerial, prompt, packedOrigin);
        return Optional.ofNullable(superseded);
    }

    Optional<Pending> pending() {
        return Optional.ofNullable(pending);
    }

    /**
     * Revalidates the pending entry with {@code serial} once the slot is clean. INSTALL and DISCARD take
     * the entry out of the slot; KEEP_FOR_ESCAPE and NONE leave it. Nothing here installs or invalidates.
     */
    Decision resolvePending(long serial, PendingContext context) {
        Pending current = pending;
        if (current == null) {
            return new Decision(Outcome.NONE, null, "none pending");
        }
        if (current.serial() != serial) {
            return new Decision(Outcome.NONE, null, "superseded");
        }
        if (cleanupFailed()) {
            return new Decision(Outcome.NONE, null, "cleanup failed");
        }
        if (!isSlotClean()) {
            return new Decision(Outcome.NONE, null, "task slot occupied");
        }
        String failure = promptProblem(current.prompt()).isPresent() ? "invalid task text"
                : !context.enabled() ? "Actionable disabled"
                : !context.playerPresent() ? "player unavailable"
                : !context.worldPresent() ? "world unavailable"
                : !context.connectionPresent() ? "no game connection"
                : !context.executorInstalled() ? "Baritone unavailable"
                : null;
        if (failure != null) {
            pending = null;
            return new Decision(Outcome.DISCARD, current, failure);
        }
        if (context.dangerRequiresEscape()) {
            return new Decision(Outcome.KEEP_FOR_ESCAPE, current, "danger still requires escape");
        }
        pending = null;
        return new Decision(Outcome.INSTALL, current, "revalidated");
    }

    /** The {@code /action} text checks, shared by submission and pending revalidation. */
    static Optional<String> promptProblem(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return Optional.of("Usage: /action <task>");
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            return Optional.of("Keep the task under 1,000 characters.");
        }
        return Optional.empty();
    }

    static CancelRecord cancelRequested(String command, long tick) {
        return new CancelRecord(CancelState.REQUESTED, command, tick);
    }

    static CancelRecord cancelUnknown(String command, long tick) {
        return new CancelRecord(CancelState.UNKNOWN, command, tick);
    }

    /**
     * The record after a Baritone command is sent. Reset rule: a sent {@code cancel} replaces the record
     * with a request for the previous command; any other sent command clears it, because Baritone runs the
     * new command in place of the old one and the running belief describes it again.
     */
    static CancelRecord afterCommand(String command, String previousCommand, long tick) {
        return "cancel".equals(command) ? cancelRequested(previousCommand, tick) : null;
    }

    /**
     * Model and HUD executor status. Precedence: unknown, then cancel requested, then believed running,
     * then idle, so a cleared running belief never reads as idle while a cancel is unconfirmed.
     */
    static String executorStatus(CancelRecord record, boolean believedRunning, String runningStatus, long nowTick) {
        if (record != null && record.state() == CancelState.UNKNOWN) {
            return "baritone_task=unknown{command=" + record.command() + "}";
        }
        if (record != null) {
            return "baritone_task=cancel_requested{command=" + record.command() + ",seconds_since_request="
                    + Math.max(0, nowTick - record.requestedAtTick()) / TICKS_PER_SECOND + "}";
        }
        return believedRunning ? runningStatus : "baritone_task=idle";
    }

    /** Control characters removed, whitespace collapsed, at most 80 characters, "unknown" when blank. */
    static String boundedCommand(String command) {
        if (command == null) {
            return "unknown";
        }
        StringBuilder clean = new StringBuilder(Math.min(command.length(), MAX_STATUS_COMMAND_LENGTH * 2));
        command.codePoints().map(c -> Character.isISOControl(c) ? ' ' : c).forEach(clean::appendCodePoint);
        String text = clean.toString().replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return "unknown";
        }
        return text.length() <= MAX_STATUS_COMMAND_LENGTH ? text
                : text.substring(0, MAX_STATUS_COMMAND_LENGTH - 3) + "...";
    }
}
