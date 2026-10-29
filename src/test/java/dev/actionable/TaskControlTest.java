package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.actionable.TaskControl.Request;
import dev.actionable.TaskControl.Settlement;
import dev.actionable.TaskControl.Stage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskControlTest {
    private final TaskControl control = new TaskControl();

    private Request begin(Stage stage) {
        return control.begin(stage).orElseThrow();
    }

    // Basic ownership

    @Test
    void beginWhenIdleCreatesOwner() {
        Request request = begin(Stage.ACTION);
        assertEquals(Optional.of(request), control.owner());
        assertTrue(control.busy());
    }

    @Test
    void beginWhenIdleCapturesCurrentGeneration() {
        control.invalidateRequests();
        control.invalidateRequests();
        long current = control.generation();
        assertEquals(current, begin(Stage.DECOMPOSITION).generation());
    }

    @Test
    void beginAllocatesDistinctRequestIds() {
        Request first = begin(Stage.ACTION);
        control.settleIfOwner(first);
        Request second = begin(Stage.ACTION);
        assertNotEquals(first.id(), second.id());
        assertTrue(second.id() > first.id());
    }

    @Test
    void beginRecordsRequestedStage() {
        for (Stage stage : Stage.values()) {
            Request request = begin(stage);
            assertEquals(stage, request.stage());
            control.settleIfOwner(request);
        }
    }

    // Begin while busy

    @Test
    void beginWhileBusyIsRejected() {
        begin(Stage.ACTION);
        assertEquals(Optional.empty(), control.begin(Stage.JUDGE));
    }

    @Test
    void beginWhileBusyLeavesExistingOwner() {
        Request owner = begin(Stage.ACTION);
        control.begin(Stage.JUDGE);
        assertEquals(Optional.of(owner), control.owner());
        assertEquals(Settlement.APPLY, control.settleIfOwner(owner));
    }

    @Test
    void beginWhileBusyDoesNotChangeGeneration() {
        begin(Stage.ACTION);
        long before = control.generation();
        control.begin(Stage.DECOMPOSITION);
        assertEquals(before, control.generation());
    }

    @Test
    void beginAfterInvalidationCreatesNewOwner() {
        Request first = begin(Stage.ACTION);
        long before = control.generation();
        control.invalidateRequests();
        Request second = begin(Stage.DECOMPOSITION);
        assertEquals(before + 1, second.generation());
        assertEquals(Optional.of(second), control.owner());
        assertEquals(Settlement.DISCARD_NOT_OWNER, control.settleIfOwner(first));
        assertEquals(Optional.of(second), control.owner());
    }

    // Worker preflight

    @Test
    void workerPreflightAcceptsCurrentGeneration() {
        assertTrue(control.isGenerationCurrent(begin(Stage.ACTION)));
    }

    @Test
    void workerPreflightRejectsInvalidatedGeneration() {
        Request stale = begin(Stage.ACTION);
        control.invalidateRequests();
        assertFalse(control.isGenerationCurrent(stale));
        Request current = begin(Stage.ACTION);
        assertTrue(control.isGenerationCurrent(current));
        assertFalse(control.isGenerationCurrent(stale));
    }

    @Test
    void workerPreflightDoesNotClearOwner() {
        Request owner = begin(Stage.JUDGE);
        control.isGenerationCurrent(owner);
        assertEquals(Optional.of(owner), control.owner());
    }

    @Test
    void workerPreflightDoesNotChangeGeneration() {
        Request request = begin(Stage.JUDGE);
        long before = control.generation();
        control.isGenerationCurrent(request);
        assertEquals(before, control.generation());
    }

    // Client settlement

    @Test
    void clientSettlementRequiresCurrentOwnerAndGeneration() {
        Request owner = begin(Stage.ACTION);
        assertTrue(control.settleIfOwner(owner).applicable());

        Request invalidated = begin(Stage.ACTION);
        control.invalidateRequests();
        assertFalse(control.settleIfOwner(invalidated).applicable());

        Request current = begin(Stage.ACTION);
        Request oldGeneration = new Request(current.id(), current.stage(), current.generation() - 1);
        assertFalse(control.settleIfOwner(oldGeneration).applicable());
    }

    @Test
    void settlementClearsMatchingOwnerBeforeLaterBegin() {
        Request owner = begin(Stage.DECOMPOSITION);
        control.settleIfOwner(owner);
        assertFalse(control.busy());
        assertTrue(control.begin(Stage.ACTION).isPresent());
    }

    @Test
    void duplicateSettlementIsNotApplicable() {
        Request owner = begin(Stage.ACTION);
        assertEquals(Settlement.APPLY, control.settleIfOwner(owner));
        assertEquals(Settlement.DISCARD_NOT_OWNER, control.settleIfOwner(owner));
    }

    @Test
    void staleSettlementCannotClearNewOwner() {
        Request stale = begin(Stage.ACTION);
        control.invalidateRequests();
        Request current = begin(Stage.DECOMPOSITION);
        assertEquals(Settlement.DISCARD_NOT_OWNER, control.settleIfOwner(stale));
        assertEquals(Optional.of(current), control.owner());
        assertEquals(Settlement.APPLY, control.settleIfOwner(current));
    }

    @Test
    void unrelatedTokenCannotClearOwner() {
        Request owner = begin(Stage.ACTION);
        Request unrelated = new Request(owner.id() + 100, owner.stage(), owner.generation());
        assertEquals(Settlement.DISCARD_NOT_OWNER, control.settleIfOwner(unrelated));
        assertEquals(Optional.of(owner), control.owner());
    }

    @Test
    void settlementWithMatchingOwnerButStaleGenerationIsNotApplicable() {
        Request owner = begin(Stage.JUDGE);
        Request oldGeneration = new Request(owner.id(), owner.stage(), owner.generation() - 1);
        assertEquals(Settlement.DISCARD_STALE_OWNER, control.settleIfOwner(oldGeneration));
        assertFalse(control.busy());
    }

    @Test
    void settlementDoesNotChangeGeneration() {
        Request request = begin(Stage.ACTION);
        long before = control.generation();
        control.settleIfOwner(request);
        control.settleIfOwner(request);
        assertEquals(before, control.generation());
    }

    // Invalidation

    @Test
    void invalidateAdvancesGenerationExactlyOnce() {
        begin(Stage.ACTION);
        long before = control.generation();
        long returned = control.invalidateRequests();
        assertEquals(before + 1, control.generation());
        assertEquals(before + 1, returned);
    }

    @Test
    void invalidateClearsActiveOwner() {
        begin(Stage.DECOMPOSITION);
        control.invalidateRequests();
        assertFalse(control.busy());
        assertEquals(Optional.empty(), control.owner());
    }

    @Test
    void invalidateWhenIdleStillAdvancesGenerationOnce() {
        long before = control.generation();
        control.invalidateRequests();
        assertEquals(before + 1, control.generation());
    }

    @Test
    void tokenCreatedBeforeInvalidationCannotSettleAsApplicable() {
        Request request = begin(Stage.ACTION);
        control.invalidateRequests();
        assertFalse(control.settleIfOwner(request).applicable());
    }

    @Test
    void multipleInvalidationsAdvanceGenerationOncePerCall() {
        long before = control.generation();
        assertEquals(before + 1, control.invalidateRequests());
        assertEquals(before + 2, control.invalidateRequests());
        assertEquals(before + 3, control.invalidateRequests());
        assertEquals(before + 3, control.generation());
    }

    // Bounded diagnostics

    @Test
    void beginRejectionCounterIncrementsWhenBusy() {
        begin(Stage.ACTION);
        control.begin(Stage.JUDGE);
        assertEquals(1, control.rejectedBeginCount());
    }

    @Test
    void successfulBeginDoesNotIncrementRejectionCounter() {
        Request request = begin(Stage.ACTION);
        control.settleIfOwner(request);
        begin(Stage.JUDGE);
        assertEquals(0, control.rejectedBeginCount());
    }

    // Stages and busy stage

    @Test
    void allPlannedRequestStagesAreRepresentable() {
        // Goal selection, decomposition, judging and action planning.
        assertEquals(List.of(Stage.DIRECTOR, Stage.DECOMPOSITION, Stage.JUDGE, Stage.ACTION),
                Arrays.asList(Stage.values()));
    }

    @Test
    void busyStageReflectsOnlyTheOwner() {
        Request request = begin(Stage.JUDGE);
        assertEquals(Optional.of(Stage.JUDGE), control.busyStage());
        control.settleIfOwner(request);
        assertEquals(Optional.empty(), control.busyStage());
    }

    // Visibility and bounded state. A test cannot prove the memory model, so these check that the
    // generation is the one volatile field and that no field can grow or hold request content.

    @Test
    void generationIsHeldInTheOnlyVolatileField() throws IllegalAccessException {
        List<Field> volatileFields = Arrays.stream(TaskControl.class.getDeclaredFields())
                .filter(field -> Modifier.isVolatile(field.getModifiers()))
                .toList();
        assertEquals(1, volatileFields.size());
        Field field = volatileFields.get(0);
        assertEquals(long.class, field.getType());
        field.setAccessible(true);
        control.invalidateRequests();
        assertEquals(control.generation(), field.getLong(control));
    }

    @Test
    void requestTokenCarriesOnlyIdStageAndGeneration() {
        assertEquals(List.of("id", "stage", "generation"),
                Arrays.stream(Request.class.getRecordComponents()).map(RecordComponent::getName).toList());
    }

    @Test
    void controlHoldsOnlyFixedSizeState() {
        for (Field field : TaskControl.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertTrue(field.getType() == long.class || field.getType() == Request.class,
                        field.getName() + " must be a counter or the single owner slot");
            }
        }
    }
}
