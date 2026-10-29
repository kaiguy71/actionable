package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ActionPlannerTest {
    private static ActionPlanner.PlannedAction action(String json) {
        return ActionPlanner.parsePlan("{\"summary\":\"s\",\"goal\":\"g\",\"complete\":false,\"action\":" + json + "}")
                .action().orElseThrow();
    }

    @Test
    void craftActionCarriesItemAndTotal() {
        ActionPlanner.PlannedAction craft = action("{\"type\":\"craft\",\"item\":\"minecraft:stick\",\"count\":4}");
        assertEquals("craft", craft.type());
        assertEquals("minecraft:stick", craft.block());
        assertEquals("4", craft.command());
    }

    @Test
    void craftDefaultsAndNormalisesTheItem() {
        ActionPlanner.PlannedAction craft = action("{\"type\":\"craft\",\"item\":\"Wooden_Pickaxe\"}");
        assertEquals("minecraft:wooden_pickaxe", craft.block());
        assertEquals("1", craft.command());
    }

    @Test
    void invalidCraftsAreDropped() {
        String plan = "{\"summary\":\"s\",\"goal\":\"g\",\"complete\":false,\"action\":%s}";
        assertTrue(ActionPlanner.parsePlan(String.format(plan,
                "{\"type\":\"craft\",\"item\":\"minecraft:stick\",\"count\":0}")).action().isEmpty());
        assertTrue(ActionPlanner.parsePlan(String.format(plan,
                "{\"type\":\"craft\",\"item\":\"bad item!\",\"count\":1}")).action().isEmpty());
        assertTrue(ActionPlanner.parsePlan(String.format(plan, "{\"type\":\"craft\"}")).action().isEmpty());
    }

    @Test
    void existingActionsStillParse() {
        assertEquals("mine 4 minecraft:oak_log",
                action("{\"type\":\"baritone\",\"command\":\"mine 4 minecraft:oak_log\"}").command());
        assertEquals("wait", action("{\"type\":\"wait\"}").type());
        assertEquals("mine 3 minecraft:stone", action("{\"type\":\"mine\",\"quantity\":3,\"block\":\"minecraft:stone\"}")
                .command());
    }

    @Test
    void directorReturnsGoalAndReason() {
        ActionPlanner.GoalChoice choice = ActionPlanner.parseGoal(
                "{\"goal\":\"craft 1 minecraft:wooden_pickaxe\",\"why\":\"stone needs a pickaxe\"}");
        assertEquals("craft 1 minecraft:wooden_pickaxe", choice.goal());
        assertEquals("stone needs a pickaxe", choice.why());
        assertEquals("", ActionPlanner.parseGoal("{\"goal\":\"explore\"}").why());
        assertThrows(IllegalStateException.class, () -> ActionPlanner.parseGoal("{\"goal\":\"  \"}"));
    }

    @Test
    void planStepsAreValidMineCommands() {
        // Direct execution relies on generated mine steps passing command validation.
        for (String step : ProgressPlanner.stepsFor("minecraft:torch", 16, java.util.Map.of(), false, "minecraft:oak_log")) {
            if (step.startsWith("mine ")) {
                assertEquals(Optional.of(step), ActionPlanner.validateCommand(step));
            }
        }
    }

    @Test
    void schemasAreValidJsonAndAllowCrafting() {
        for (String stage : new String[] {"director", "decomposition", "judge", "action"}) {
            JsonParser.parseString(PlannerSchema.forStage(stage));
        }
        JsonObject action = JsonParser.parseString(PlannerSchema.forStage("action")).getAsJsonObject()
                .getAsJsonObject("properties").getAsJsonObject("action");
        assertTrue(action.getAsJsonObject("properties").getAsJsonObject("type").get("enum").toString()
                .contains("\"craft\""));
        assertTrue(action.getAsJsonObject("properties").has("item"));
        assertTrue(action.getAsJsonObject("properties").has("count"));
        JsonObject director = JsonParser.parseString(PlannerSchema.forStage("director")).getAsJsonObject();
        assertTrue(director.get("required").toString().contains("\"why\""), "the director must explain itself");
    }

    // Request ownership with the planner worker (Milestone 1 Change Set 3, ORCH-001, ORCH-002).
    // These run the real planner executor with a counting backend in place of Ollama or the Claude CLI,
    // and a queue drained by the test thread in place of the Minecraft client thread. The callbacks below
    // mirror ActionableClient's shape (settle first, then apply only an applicable result); that the four
    // production callbacks have this shape is checked by ActionableClientWiringTest, and runtime behavior
    // remains for RV-006 and RV-007.

    private static final long WAIT_SECONDS = 10;
    private final ActionPlanner planner = new ActionPlanner();
    private final TaskControl control = new TaskControl();
    private final BlockingQueue<Runnable> clientThread = new LinkedBlockingQueue<>();
    private final AtomicInteger backendCalls = new AtomicInteger();

    private <T> Supplier<T> backend(T result) {
        return () -> {
            backendCalls.incrementAndGet();
            return result;
        };
    }

    private TaskControl.Request begin(TaskControl.Stage stage) {
        return control.begin(stage).orElseThrow();
    }

    private <T> CompletableFuture<T> submit(TaskControl.Request token, Supplier<T> request) {
        return planner.submit(token.stage().name().toLowerCase(), () -> control.isGenerationCurrent(token), request);
    }

    /** Production callback shape: back to the client thread, settle, then apply only if applicable. */
    private <T> void deliver(CompletableFuture<T> future, TaskControl.Request token, BiConsumer<T, Throwable> apply) {
        future.whenComplete((value, error) -> clientThread.add(() -> {
            if (control.settleIfOwner(token).applicable()) {
                apply.accept(value, error);
            }
        }));
    }

    private void runClientThread() throws InterruptedException {
        Runnable next = clientThread.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(next, "a completion reached the client thread");
        next.run();
    }

    private static void awaitCompletion(CompletableFuture<?> future) {
        future.handle((value, error) -> null).orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join();
    }

    // Deterministic backend seam

    @Test
    void currentQueuedRequestInvokesBackendExactlyOnce() {
        TaskControl.Request token = begin(TaskControl.Stage.ACTION);
        assertEquals("plan", submit(token, backend("plan")).orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join());
        assertEquals(1, backendCalls.get());
    }

    @Test
    void invalidatedQueuedRequestSkipsBackendCall() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> blocker = planner.submit("blocker", () -> true, () -> {
            try {
                return release.await(WAIT_SECONDS, TimeUnit.SECONDS) ? 0 : -1;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
        });
        TaskControl.Request queued = begin(TaskControl.Stage.DECOMPOSITION);
        CompletableFuture<String> stale = submit(queued, backend("steps"));
        control.invalidateRequests();
        release.countDown();
        awaitCompletion(blocker);
        CompletionException skipped = assertThrows(CompletionException.class,
                () -> stale.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join());
        assertInstanceOf(ActionPlanner.StaleRequestException.class, skipped.getCause());
        assertEquals(0, backendCalls.get(), "the model was never called for the stale request");
    }

    @Test
    void beginRejectedRequestInvokesBackendZeroTimes() {
        // Production returns before calling the planner when begin is empty (ActionableClientWiringTest).
        begin(TaskControl.Stage.JUDGE);
        Optional<TaskControl.Request> rejected = control.begin(TaskControl.Stage.ACTION);
        assertTrue(rejected.isEmpty());
        rejected.ifPresent(token -> submit(token, backend("plan")));
        assertEquals(0, backendCalls.get());
    }

    // Stale results never reach stage logic

    private void assertStaleResultIsNotApplied(TaskControl.Stage stage, Supplier<?> request)
            throws InterruptedException {
        TaskControl.Request token = begin(stage);
        CompletableFuture<?> future = submit(token, request);
        AtomicInteger applied = new AtomicInteger();
        deliver(future, token, (value, error) -> applied.incrementAndGet());
        awaitCompletion(future);
        control.invalidateRequests();
        runClientThread();
        assertEquals(0, applied.get(), stage + " result was discarded");
        assertFalse(control.busy());
    }

    @Test
    void staleDirectorResultDoesNotInstallGoal() throws InterruptedException {
        assertStaleResultIsNotApplied(TaskControl.Stage.DIRECTOR,
                backend(ActionPlanner.parseGoal("{\"goal\":\"mine 3 minecraft:oak_log\",\"why\":\"tools\"}")));
        assertEquals(1, backendCalls.get(), "the model answered; only its result was dropped");
    }

    @Test
    void staleDecompositionResultDoesNotInstallPlan() throws InterruptedException {
        assertStaleResultIsNotApplied(TaskControl.Stage.DECOMPOSITION,
                backend(ActionPlanner.parseSteps("{\"steps\":[\"mine 3 minecraft:oak_log\"]}")));
    }

    @Test
    void staleJudgeResultDoesNotAdvanceStep() throws InterruptedException {
        assertStaleResultIsNotApplied(TaskControl.Stage.JUDGE, backend(Boolean.TRUE));
    }

    @Test
    void staleActionResultDoesNotDispatchAction() throws InterruptedException {
        assertStaleResultIsNotApplied(TaskControl.Stage.ACTION, backend(ActionPlanner.parsePlan(
                "{\"summary\":\"s\",\"goal\":\"g\",\"complete\":false,"
                        + "\"action\":{\"type\":\"baritone\",\"command\":\"mine 3 minecraft:oak_log\"}}")));
    }

    // Ownership is released whatever the outcome

    private Throwable deliverAndCapture(TaskControl.Stage stage, Supplier<?> request) throws InterruptedException {
        TaskControl.Request token = begin(stage);
        CompletableFuture<?> future = submit(token, request);
        Throwable[] delivered = new Throwable[1];
        boolean[] busyInsideApply = {true};
        deliver(future, token, (value, error) -> {
            delivered[0] = error;
            busyInsideApply[0] = control.busy();
        });
        awaitCompletion(future);
        runClientThread();
        assertFalse(busyInsideApply[0], "ownership was released before stage logic ran");
        assertFalse(control.busy());
        assertTrue(control.begin(TaskControl.Stage.ACTION).isPresent(), "the next request can begin");
        return delivered[0];
    }

    @Test
    void successfulCallbackReleasesOwnership() throws InterruptedException {
        assertEquals(null, deliverAndCapture(TaskControl.Stage.ACTION, backend("plan")));
    }

    @Test
    void parseFailureReleasesOwnership() throws InterruptedException {
        // Parsing runs on the worker, as before; a malformed reply arrives as the request's failure.
        Throwable error = deliverAndCapture(TaskControl.Stage.ACTION, () -> ActionPlanner.parsePlan("not json"));
        assertNotNull(error, "the parse failure reached the stage's existing error handling");
    }

    @Test
    void requestExceptionReleasesOwnership() throws InterruptedException {
        Throwable error = deliverAndCapture(TaskControl.Stage.DECOMPOSITION, () -> {
            throw new IllegalStateException("Could not get a plan from Ollama: Connection refused");
        });
        assertTrue(ActionPlanner.safeMessage(error).contains("Connection refused"));
    }

    @Test
    void requestTimeoutReleasesOwnership() throws InterruptedException {
        // The transports report timeouts as exceptions: ClaudeCli after 180 s, Ollama via HttpTimeoutException.
        for (RuntimeException timeout : List.of(
                new IllegalStateException("CLAUDE_TIMEOUT: Claude did not respond within 180 seconds"),
                new IllegalStateException("Could not get a plan from Ollama: request timed out",
                        new java.net.http.HttpTimeoutException("request timed out")))) {
            Throwable error = deliverAndCapture(TaskControl.Stage.JUDGE, () -> {
                throw timeout;
            });
            assertNotNull(error);
            control.settleIfOwner(control.owner().orElseThrow());
        }
    }

    @Test
    void synchronousPromptConstructionFailureReleasesOwnership() {
        // Mirrors ActionableClient: begin, then capture inside try; a failure before submission releases
        // the matching owner and propagates. The production try/catch is checked by ActionableClientWiringTest.
        TaskControl.Request token = begin(TaskControl.Stage.DIRECTOR);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            try {
                throw new IllegalStateException("synopsis capture failed");
            } catch (RuntimeException captureFailure) {
                control.settleIfOwner(token);
                throw captureFailure;
            }
        });
        assertEquals("synopsis capture failed", failure.getMessage());
        assertFalse(control.busy());
        assertEquals(0, backendCalls.get());
    }

    // Owner isolation

    @Test
    void oldCallbackCannotClearNewOwner() throws InterruptedException {
        TaskControl.Request old = begin(TaskControl.Stage.ACTION);
        CompletableFuture<String> oldFuture = submit(old, backend("old"));
        AtomicInteger applied = new AtomicInteger();
        deliver(oldFuture, old, (value, error) -> applied.incrementAndGet());
        awaitCompletion(oldFuture);
        control.invalidateRequests();
        TaskControl.Request current = begin(TaskControl.Stage.DECOMPOSITION);
        runClientThread();
        assertEquals(0, applied.get());
        assertEquals(Optional.of(current), control.owner(), "the new request still owns the slot");
    }

    @Test
    void duplicateCallbackDoesNotApplyTwice() throws InterruptedException {
        TaskControl.Request token = begin(TaskControl.Stage.JUDGE);
        CompletableFuture<Boolean> future = submit(token, backend(Boolean.TRUE));
        AtomicInteger applied = new AtomicInteger();
        deliver(future, token, (value, error) -> applied.incrementAndGet());
        deliver(future, token, (value, error) -> applied.incrementAndGet());
        awaitCompletion(future);
        runClientThread();
        runClientThread();
        assertEquals(1, applied.get());
    }

    @Test
    void beginRejectedWhileAnotherStageOwnsSlot() throws InterruptedException {
        TaskControl.Request judge = begin(TaskControl.Stage.JUDGE);
        CompletableFuture<Boolean> inFlight = submit(judge, backend(Boolean.FALSE));
        assertTrue(control.begin(TaskControl.Stage.ACTION).isEmpty(), "action planning waits for the judge");
        deliver(inFlight, judge, (value, error) -> { });
        awaitCompletion(inFlight);
        runClientThread();
    }

    // Scheduling

    @Test
    void busyControllerBlocksAnotherModelStage() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        TaskControl.Request decomposition = begin(TaskControl.Stage.DECOMPOSITION);
        CompletableFuture<Boolean> inFlight = submit(decomposition, () -> {
            try {
                return release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        deliver(inFlight, decomposition, (value, error) -> { });
        assertTrue(control.busy());
        for (TaskControl.Stage other : TaskControl.Stage.values()) {
            assertTrue(control.begin(other).isEmpty(), other + " cannot begin while a request is in flight");
        }
        release.countDown();
        awaitCompletion(inFlight);
        runClientThread();
    }

    @Test
    void settledControllerAllowsNextModelStage() throws InterruptedException {
        TaskControl.Request decomposition = begin(TaskControl.Stage.DECOMPOSITION);
        CompletableFuture<String> future = submit(decomposition, backend("steps"));
        deliver(future, decomposition, (value, error) -> { });
        awaitCompletion(future);
        runClientThread();
        assertTrue(control.begin(TaskControl.Stage.ACTION).isPresent(), "planning can follow decomposition");
    }

    @Test
    void breakBlockCarriesItsTarget() {
        ActionPlanner.PlannedAction action = action("{\"type\":\"break_block\",\"x\":4,\"y\":65,\"z\":-9}");
        assertEquals("break_block", action.type());
        assertEquals(4, action.x());
        assertEquals(65, action.y());
        assertEquals(-9, action.z());
        assertTrue(PlannerSchema.forStage("action").contains("\"break_block\""));
    }

    @Test
    void whiteFlagAndPlaceAliasParse() {
        assertEquals("white_flag", action("{\"type\":\"white_flag\",\"reason\":\"stuck\"}").type());
        assertEquals("stuck", action("{\"type\":\"white_flag\",\"reason\":\"stuck\"}").query());
        assertEquals("place_block", action(
                "{\"type\":\"jump_place\",\"block\":\"minecraft:dirt\",\"x\":1,\"y\":2,\"z\":3}").type());
    }

    @Test
    void redundantCommandsAreRejected() {
        for (String verb : new String[] {"build a.schem", "find stone", "come", "follow bob", "pickup x", "farm"}) {
            assertTrue(ActionPlanner.validateCommand(verb).isEmpty(), verb);
        }
        assertTrue(ActionPlanner.validateCommand("explore").isEmpty(), "explore is replaced by travel");
        assertTrue(ActionPlanner.validateCommand("cancel").isPresent());
    }

    @Test
    void travelNeedsACompassDirection() {
        var travel = ActionPlanner.parseAction(com.google.gson.JsonParser.parseString(
                "{\"type\":\"travel\",\"direction\":\"north\"}").getAsJsonObject());
        assertEquals("travel", travel.orElseThrow().type());
        assertEquals("NORTH", travel.orElseThrow().command());
        assertTrue(ActionPlanner.parseAction(com.google.gson.JsonParser.parseString(
                "{\"type\":\"travel\",\"direction\":\"up\"}").getAsJsonObject()).isEmpty());
    }
}
