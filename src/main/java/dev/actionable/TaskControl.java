package dev.actionable;

import java.util.Objects;
import java.util.Optional;

/**
 * Decides which model request may apply its result (ORCH-001, ORCH-002). One slot holds the request
 * that currently owns model work, and a generation counter makes every earlier request stale when
 * work is invalidated.
 *
 * <p>Threading: the generation is written only on the client thread and is {@code volatile} so the
 * planner worker can read it in {@link #isGenerationCurrent} and skip queued work that is already
 * stale. The owner slot and the counters belong to the client thread; the worker never reads or
 * writes them. A {@link Request} is immutable and reaches the worker through executor submission.
 *
 * <p>Beginning while the slot is owned is rejected rather than replacing the owner, so at most one
 * request can apply a result. A caller that means to replace a request invalidates first. Nothing
 * about a request's content is kept, so the state stays a few fields however long the client runs.
 */
final class TaskControl {
    /** Model stages that can own the slot. Names match ActionPlanner's stage log labels. */
    enum Stage {
        /** Autonomous goal selection. */
        DIRECTOR,
        /** Splitting a task into plan steps. */
        DECOMPOSITION,
        /** Judging whether the current step is already done. */
        JUDGE,
        /** Choosing the next action for the current step. */
        ACTION
    }

    /** One model request: its ID, its stage and the generation it began in. */
    record Request(long id, Stage stage, long generation) {
        Request {
            Objects.requireNonNull(stage, "stage");
        }
    }

    /** How a returned request was settled on the client thread. */
    enum Settlement {
        /** The request still owned the slot in the current generation; its result may be applied. */
        APPLY,
        /** The request owned the slot but began in an older generation; its ownership was released. */
        DISCARD_STALE_OWNER,
        /** The request did not own the slot; nothing changed. */
        DISCARD_NOT_OWNER;

        boolean applicable() {
            return this == APPLY;
        }
    }

    // Tokens compare generations for equality only; wraparound would take 2^63 invalidations.
    private volatile long generation;
    private long nextRequestId;
    private Request owner;
    private long rejectedBegins;

    /**
     * Begins a request for {@code stage} when the slot is free, capturing the current generation.
     * When another request owns the slot, returns empty and leaves the owner and generation as they
     * were.
     */
    Optional<Request> begin(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        if (owner != null) {
            rejectedBegins++;
            return Optional.empty();
        }
        owner = new Request(++nextRequestId, stage, generation);
        return Optional.of(owner);
    }

    /**
     * Worker-side check before model work starts: whether {@code request} began in the current
     * generation. Reads only the generation and changes nothing. A request that passes can still be
     * invalidated before it returns, so {@link #settleIfOwner} remains the gate for applying results.
     */
    boolean isGenerationCurrent(Request request) {
        return request.generation() == generation;
    }

    /**
     * Settles a returned request on the client thread. Releases the slot only when {@code request}
     * owns it, and reports the result applicable only when it also began in the current generation.
     * Never releases another request's ownership and never changes the generation, so call this
     * before acting on the result.
     */
    Settlement settleIfOwner(Request request) {
        if (owner == null || owner.id() != request.id()) {
            return Settlement.DISCARD_NOT_OWNER;
        }
        owner = null;
        return request.generation() == generation ? Settlement.APPLY : Settlement.DISCARD_STALE_OWNER;
    }

    /**
     * Makes every outstanding request stale: advances the generation by one and frees the slot.
     * Cleans up no executor, task or plan state. Returns the new generation.
     */
    long invalidateRequests() {
        long next = generation + 1;
        generation = next;
        owner = null;
        return next;
    }

    /** The request that owns the slot, if any. */
    Optional<Request> owner() {
        return Optional.ofNullable(owner);
    }

    boolean busy() {
        return owner != null;
    }

    /** The stage of the request that owns the slot, if any. */
    Optional<Stage> busyStage() {
        return owner().map(Request::stage);
    }

    long generation() {
        return generation;
    }

    /** Begins rejected because the slot was owned. Diagnostic only; no decision depends on it. */
    long rejectedBeginCount() {
        return rejectedBegins;
    }
}
