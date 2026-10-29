package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Source-level checks that the client routes task completion through the completion boundary (deadline
 * correction): evidence before the final review, a failed review held at the boundary, model claims gated by
 * evidence, and the per-task retry state reset on every transition. These prove production source wiring and
 * ordering, not runtime scheduling or in-game behavior.
 */
class CompletionWiringTest {
    private static final Path CLIENT = Path.of("src/main/java/dev/actionable/ActionableClient.java");
    private static final String SETTLE_GATE = "TaskControl.Settlement settlement = taskControl.settleIfOwner(token);"
            + " if (!settlement.applicable()) { logDiscardedResult(token, settlement); return; }";
    private static final String REVIEW_FAILURE = "if (error != null) { if (review) {"
            + " finalReviewFailed(client, TaskCompletion.reviewFailureCode(error), ActionPlanner.safeMessage(error));"
            + " return; }";
    /** Anything that would reopen planning, act in the world, or end the task. */
    private static final List<String> RESUMES = List.of("requestNextPlan(", "requestDecomposition(", "finalCheck = false",
            "taskSteps =", "executeBaritone(", "startMine(", "startCraft(", "placeNearby(", "placeBlock(", "travel(",
            "advanceStep(", "finishTask(", "invalidateTask(", "abandonTask(", "whiteFlag(", "respawn(");

    @Test
    void finalReviewHttpFailureDoesNotResumeGenericActions() throws IOException {
        Throwable http = new CompletionException(new IllegalStateException(
                "Could not get a plan from Ollama: Ollama returned HTTP 400: {\"error\":\"bad request\"}",
                new IllegalStateException("Ollama returned HTTP 400: {\"error\":\"bad request\"}")));
        assertEquals("FINAL_REVIEW_HTTP_FAILED", TaskCompletion.reviewFailureCode(http));
        assertHeldAtBoundaryAfterFailure();
    }

    @Test
    void finalReviewTimeoutDoesNotResumeGenericActions() throws IOException {
        Throwable timeout = new CompletionException(new IllegalStateException(
                "Could not get a plan from Ollama: request timed out", new HttpTimeoutException("request timed out")));
        assertEquals("FINAL_REVIEW_TIMEOUT", TaskCompletion.reviewFailureCode(timeout));
        assertEquals("FINAL_REVIEW_TIMEOUT",
                TaskCompletion.reviewFailureCode(new IllegalStateException("CLAUDE_TIMEOUT: no answer in 120 s")));
        assertEquals("FINAL_REVIEW_TRANSPORT_FAILED", TaskCompletion.reviewFailureCode(new CompletionException(
                new IllegalStateException("Could not get a plan", new ConnectException("Connection refused")))));
        assertEquals("FINAL_REVIEW_PROVIDER_FAILED",
                TaskCompletion.reviewFailureCode(new IllegalStateException("Unknown actionable.model.provider: x")));
        assertHeldAtBoundaryAfterFailure();
    }

    @Test
    void finalReviewParseFailureDoesNotResumeGenericActions() throws IOException {
        Throwable parse = new CompletionException(new com.google.gson.JsonSyntaxException("Expected BEGIN_OBJECT"));
        assertEquals("FINAL_REVIEW_PARSE_FAILED", TaskCompletion.reviewFailureCode(parse));
        assertEquals("FINAL_REVIEW_PARSE_FAILED",
                TaskCompletion.reviewFailureCode(new IllegalStateException("CLAUDE_INVALID_JSON: not an object")));
        // A review that answers with neither completion nor an action is a failed review too.
        String callback = actionCallback();
        assertTrue(callback.contains("if (review) { if (plan.action().isEmpty()) {"
                + " finalReviewFailed(client, \"FINAL_REVIEW_NO_DECISION\", plan.summary()); return; }"));
        assertHeldAtBoundaryAfterFailure();
    }

    /** A failed review waits at the boundary for an evidence recheck and a bounded retry; nothing else runs. */
    private static void assertHeldAtBoundaryAfterFailure() throws IOException {
        TaskCompletion.Tracker tracker = new TaskCompletion.Tracker();
        tracker.reviewFailed(100);
        assertEquals(TaskCompletion.Decision.HOLD, tracker.decide(TaskCompletion.Verdict.UNKNOWN, false, 101));
        assertEquals(TaskCompletion.Decision.FINISH, tracker.decide(TaskCompletion.Verdict.SATISFIED, false, 101),
                "the recheck still finishes from evidence");

        String callback = actionCallback();
        assertTrue(callback.contains(REVIEW_FAILURE), "a review failure is handled as one, then returns");
        assertBefore(callback, REVIEW_FAILURE, "errorCode = plannerErrorCode(error);",
                "the review branch precedes the generic planning-failure handling");
        assertBefore(callback, "completion.reviewAnswered(); finalCheck = false;", "plan.action().orElseThrow()",
                "only an answered review leaves the boundary before an action runs");
        assertEquals(1, count(callback, "finalCheck = false"), "one way out of the boundary in the callback");

        String failed = method(read(CLIENT), "private void finalReviewFailed(");
        for (String resume : RESUMES) {
            assertFalse(failed.contains(resume), "a failed review must not call " + resume);
        }
        assertTrue(failed.contains("ticksUntilPlan = TaskCompletion.FINAL_REVIEW_RETRY_TICKS;"));

        String plan = method(read(CLIENT), "private void requestNextPlan(");
        assertBefore(plan, "if (finalCheck && !completionReviewDue(client)) { return; }",
                "taskControl.begin(TaskControl.Stage.ACTION)", "the boundary runs before any ACTION request");
        assertBefore(plan, "if (finalCheck && !completionReviewDue(client)) { return; }", "executeStepDirectly(client)",
                "the boundary runs before any step action");
        String due = method(read(CLIENT), "private boolean completionReviewDue(");
        assertBefore(due, "if (!completionSettled(client)) {", "taskAssessment(client)",
                "evidence is read only once task jobs, Baritone, menus and placements have settled");
    }

    @Test
    void staleFinalReviewCannotFinishReplacementTask() throws IOException {
        TaskControl control = new TaskControl();
        TaskControl.Request review = control.begin(TaskControl.Stage.ACTION).orElseThrow();
        // A replacement (/action) invalidates through the task boundary: the generation advances and the slot is
        // freed, so the late review owns nothing and the replacement's own request may begin.
        control.invalidateRequests();
        TaskControl.Request replacement = control.begin(TaskControl.Stage.DECOMPOSITION).orElseThrow();
        assertEquals(TaskControl.Settlement.DISCARD_NOT_OWNER, control.settleIfOwner(review));
        assertEquals(TaskControl.Settlement.APPLY, control.settleIfOwner(replacement),
                "the late review neither applied nor released the replacement's request");

        String callback = actionCallback();
        assertTrue(callback.startsWith(SETTLE_GATE), "the review settles before anything reads it");
        String confirmed = "finishTask(client, connection, \"Final review confirmed the task (\"";
        assertBefore(callback, "if (review != finalCheck) {", confirmed,
                "an answer for a boundary that was left (death) is dropped");
        assertBefore(callback, "TaskCompletion.Assessment confirmed = taskAssessment(client);", confirmed,
                "a review's complete=true is re-checked against evidence before finishing");
        assertBefore(callback, "if (confirmed.verdict() == TaskCompletion.Verdict.NOT_SATISFIED) {", confirmed,
                "evidence that contradicts the review wins");
    }

    @Test
    void completionRetryStateClearsOnTransition() throws IOException {
        TaskCompletion.Tracker tracker = new TaskCompletion.Tracker();
        tracker.enter("Final plan step finished: build house");
        for (int attempt = 0; attempt < TaskCompletion.MAX_FINAL_REVIEW_ATTEMPTS; attempt++) {
            tracker.reviewFailed(500);
        }
        tracker.replanned();
        tracker.replanned();
        tracker.reviewPromptPrepared();
        tracker.smelted();
        tracker.expose("FINAL_REVIEW_EXHAUSTED");
        tracker.noteTaskStart("mine 3 more minecraft:oak_log", Map.of("minecraft:oak_log", 4));
        tracker.noteStepStart(0, "mine 2 more minecraft:oak_log", Map.of("minecraft:oak_log", 4));
        assertEquals(TaskCompletion.Decision.HOLD, tracker.decide(TaskCompletion.Verdict.UNKNOWN, false, 10_000));

        tracker.reset();
        assertEquals(0, tracker.reviewFailures());
        assertEquals(0, tracker.replans());
        assertEquals(0, tracker.reviewNotBefore());
        assertEquals("", tracker.reason());
        assertFalse(tracker.reviewPromptReady());
        assertFalse(tracker.hasSmelted());
        assertNull(tracker.taskBaseline());
        assertNull(tracker.stepBaseline(0, "mine 2 more minecraft:oak_log"));
        assertTrue(tracker.expose("FINAL_REVIEW_EXHAUSTED"), "exposure starts again");
        assertEquals(TaskCompletion.Decision.REVIEW, tracker.decide(TaskCompletion.Verdict.UNKNOWN, false, 0));
        assertEquals(TaskCompletion.Decision.REPLAN, tracker.decide(TaskCompletion.Verdict.NOT_SATISFIED, false, 0));

        String source = read(CLIENT);
        // Every task transition (new task, replacement, finish, end, disconnect) runs clearTaskState once.
        String cleanup = normalize(block(source, source.indexOf('{',
                source.indexOf("private final class ClientCleanup implements TaskTransition.Cleanup {"))));
        String state = cleanup.substring(cleanup.indexOf("public void clearTaskState()"));
        assertTrue(state.contains("finalCheck = false; completion.reset();"), "transitions leave the boundary");
        assertTrue(method(source, "private void beginTask(").contains("finalCheck = false; completion.reset();"));
        String death = method(source, "private boolean tickDeath(");
        assertBefore(death, "awaitingRespawn = true;", "finalCheck = false; completion.reset();",
                "death resets the boundary when it is first seen");
        assertEquals(1, count(source, "new TaskCompletion.Tracker()"), "one tracker for the current task");
    }

    @Test
    void onlyTheBoundaryFinishesATask() throws IOException {
        String source = read(CLIENT);
        Map<String, String> methods = methods(source);
        Set<String> finishers = new HashSet<>();
        methods.forEach((name, body) -> {
            if (!name.equals("finishTask") && body.contains("finishTask(")) {
                finishers.add(name);
            }
        });
        assertEquals(Set.of("applyCompletion", "requestNextPlan"), finishers,
                "evidence (applyCompletion) or a re-checked review answer (requestNextPlan) finishes a task");
        String finish = methods.get("finishTask");
        assertFalse(finish.contains("beginFinalCheck("), "finishing no longer starts a review");
        assertTrue(finish.contains("invalidateTask(client, TaskTransition.Kind.TASK_FINISHED, reason);"));

        String advance = methods.get("advanceStep");
        assertTrue(advance.contains("reachCompletionBoundary(client, \"Final plan step finished: \" + finished);"),
                "the last step reaches the boundary; visiting every step is not completion");
        String callback = actionCallback();
        assertTrue(callback.contains("if (!review) { reachCompletionBoundary(client, \"Planner marked the task complete\");"
                + " return; }"), "a model completion claim reaches the boundary");
        String apply = methods.get("applyCompletion");
        assertTrue(apply.contains("case FINISH -> finishTask("));
        assertTrue(apply.contains("case REPLAN -> replanAfterMismatch(client, evidence);"));
        assertTrue(methods.get("replanAfterMismatch").contains("completion.replanned();"), "replans are counted");
    }

    @Test
    void modelStepClaimsAreGatedByEvidence() throws IOException {
        String callback = actionCallback();
        assertBefore(callback, "if (TaskCompletion.claimAccepted(evidence.verdict())) {",
                "advanceStep(client, claimed);", "a step_complete claim needs evidence that does not contradict it");
        assertTrue(callback.contains("rejectStepClaim(client, claimed, evidence); return;"),
                "a contradicted claim neither advances nor runs its action");
        String judge = callback(method(read(CLIENT), "private void requestStepCheck("));
        assertBefore(judge, "TaskCompletion.claimAccepted(stepAssessment(client, objective).verdict())",
                "advanceStep(client, objective);", "the judge's verdict is gated the same way");
        String reject = method(read(CLIENT), "private void rejectStepClaim(");
        assertFalse(reject.contains("advanceStep("), "a rejected claim never advances the step");
        String handOver = method(read(CLIENT), "private boolean continueDirectStep(");
        assertBefore(handOver, "if (decision == Recovery.DirectMine.HAND_OVER) {",
                "if (taskAssessment(client).verdict() == TaskCompletion.Verdict.SATISFIED) {",
                "a step code gave up on finishes the task only on the task's own evidence");
    }

    @Test
    void minePathFailureIsRecordedAndDoesNotFinishTheStep() throws IOException {
        String tick = method(read(CLIENT), "public void onInitializeClient(");
        int start = tick.indexOf("if (TaskCompletion.mineNoPath(text)) {");
        assertTrue(start >= 0, "Baritone's no-path report is recognised");
        String handler = block(tick, tick.indexOf('{', start));
        assertTrue(handler.contains("errorCode = \"MINE_FAILED_NO_PATH\";"));
        assertTrue(handler.contains("baritoneCommandRunning = false;"), "the mine is no longer believed running");
        for (String completion : List.of("advanceStep(", "finishTask(", "reachCompletionBoundary(", "stepJudged = true")) {
            assertFalse(handler.contains(completion), "a path failure must not " + completion);
        }
    }

    @Test
    void placementIsTimedAfterVerificationAndBeforeTheRequest() throws IOException {
        String place = method(read(CLIENT), "private String placeBlock(");
        assertBefore(place, "PlacementPreparation.verify(", "lastPlacementTick = clientTick;",
                "only a verified hand can send a request");
        assertBefore(place, "lastPlacementTick = clientTick;", "for (BlockHitResult supportHit : supportHits) {",
                "the tick is recorded before any request can be sent");
        assertTrue(method(read(CLIENT), "private boolean completionSettled(")
                .contains("TaskCompletion.placementSettled(clientTick, lastPlacementTick)"));
    }

    private static String actionCallback() throws IOException {
        return callback(method(read(CLIENT), "private void requestNextPlan("));
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static String callback(String body) {
        int completion = body.indexOf("future.whenComplete((");
        assertTrue(completion >= 0, "the stage attaches a completion callback");
        int execute = body.indexOf("client.execute(() -> {", completion);
        assertTrue(execute >= 0, "the completion returns to the client thread");
        return block(body, body.indexOf('{', execute)).trim();
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " not found");
        return normalize(block(source, source.indexOf('{', start)));
    }

    private static Map<String, String> methods(String source) {
        Matcher declaration = Pattern.compile(
                "(?m)^    (?=\\w)(?:(?:public|private|protected|static|final)\\s+)*(?:<[^>]+>\\s+)?[\\w.<>\\[\\]?, ]+\\s+(\\w+)\\s*\\(")
                .matcher(source);
        Map<String, String> bodies = new LinkedHashMap<>();
        while (declaration.find()) {
            int open = source.indexOf('{', declaration.end());
            bodies.merge(declaration.group(1), normalize(block(source, open)).trim(), (a, b) -> a + " " + b);
        }
        return bodies;
    }

    private static int count(String text, String marker) {
        return text.split(Pattern.quote(marker), -1).length - 1;
    }

    private static void assertBefore(String text, String first, String second, String message) {
        int a = text.indexOf(first);
        int b = text.indexOf(second);
        assertTrue(a >= 0, "missing: " + first);
        assertTrue(b >= 0, "missing: " + second);
        assertTrue(a < b, message);
    }

    private static String normalize(String text) {
        StringBuilder code = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int literalStart = i;
                for (i++; i < text.length() && text.charAt(i) != c; i++) {
                    if (text.charAt(i) == '\\') {
                        i++;
                    }
                }
                code.append(text, literalStart, Math.min(i + 1, text.length()));
            } else if (text.startsWith("//", i)) {
                int newline = text.indexOf('\n', i);
                i = newline < 0 ? text.length() : newline - 1;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 1;
            } else {
                code.append(c);
            }
        }
        return code.toString().replaceAll("\\s+", " ");
    }

    private static String block(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < text.length() && text.charAt(i) != c; i++) {
                    if (text.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (text.startsWith("//", i)) {
                int newline = text.indexOf('\n', i);
                if (newline < 0) {
                    break;
                }
                i = newline;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                i = end + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(open + 1, i);
            }
        }
        throw new AssertionError("unbalanced braces after index " + open);
    }
}
