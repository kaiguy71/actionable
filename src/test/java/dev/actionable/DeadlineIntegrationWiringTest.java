package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Source-level checks for the deadline integration of the Milestone 1 safeguards into the teammate
 * client: one TaskControl owner for the four model stages with a worker preflight and settlement before
 * any stage logic, one task boundary for every task end and replacement, recorded Baritone cancels,
 * generation-checked tactical results, and a bounded model plan. Adapted from Milestone 1's
 * ActionableClientWiringTest, whose remaining checks assume parts of the Milestone 1 client (clean-slot
 * installation, pending replacement, explicit task origin) that this deadline build does not include.
 * These prove production source wiring and ordering, not runtime scheduling or in-game behavior.
 */
class DeadlineIntegrationWiringTest {
    private static final Path CLIENT = Path.of("src/main/java/dev/actionable/ActionableClient.java");
    private static final Path PLANNER = Path.of("src/main/java/dev/actionable/ActionPlanner.java");
    private static final Path TRANSITION = Path.of("src/main/java/dev/actionable/TaskTransition.java");
    private static final Path TACTICS = Path.of("src/main/java/dev/actionable/TacticalAgent.java");
    private static final Path HOTBAR = Path.of("src/main/java/dev/actionable/Hotbar.java");
    private static final String SETTLE_GATE = "TaskControl.Settlement settlement = taskControl.settleIfOwner(token);"
            + " if (!settlement.applicable()) { logDiscardedResult(token, settlement); return; }";
    private static final String SYNCHRONOUS_FAILURE = "} catch (RuntimeException failure) {"
            + " taskControl.settleIfOwner(token); throw failure; }";
    private static final String FAULT_BARRIER = "if (transition.cleanupFailed()) {"
            + " if (enabled && clientTick % 20 == 0) { retryFailedCleanup(client); } return; }";
    /** Paths that keep the task but make in-flight model results stale: defense, shelter and pause. */
    private static final Set<String> REQUEST_ONLY_INVALIDATION = Set.of("tickShelter", "tickDefense", "toggle");

    private record Stage(String method, String stage, String plannerCall) {
    }

    private static final Stage DIRECTOR = new Stage("private void requestGoalSelection(", "DIRECTOR", "planner.chooseGoal(");
    private static final Stage DECOMPOSITION =
            new Stage("private void requestDecomposition(", "DECOMPOSITION", "planner.decompose(");
    private static final Stage JUDGE = new Stage("private void requestStepCheck(", "JUDGE", "planner.checkStep(");
    private static final Stage ACTION = new Stage("private void requestNextPlan(", "ACTION", "planner.plan(");

    @Test
    void legacyOwnershipStateIsRemoved() throws IOException {
        String code = codeOnly(read(CLIENT));
        for (String legacy : List.of("planning", "decomposing", "judging", "choosingGoal", "requestGeneration")) {
            assertFalse(Pattern.compile("\\b" + legacy + "\\b").matcher(code).find(), legacy + " is gone");
        }
        assertFalse(code.contains("PlannerRequests"), "no competing request owner");
        assertTrue(Files.notExists(Path.of("src/main/java/dev/actionable/PlannerRequests.java")));
        assertEquals(1, count(code, "new TaskControl()"), "exactly one TaskControl");
    }

    @Test
    void requestGenerationHasOneSource() throws IOException {
        // TaskControl accessors such as token.generation() or taskControl::generation are allowed; a field,
        // local or increment of the client's own is not.
        String code = codeOnly(read(CLIENT));
        assertFalse(Pattern.compile("(?<![.:\\w])generation\\b").matcher(code).find(),
                "ActionableClient keeps no generation of its own");
        String source = read(CLIENT);
        assertEquals(REQUEST_ONLY_INVALIDATION, methodsContaining(source, "taskControl.invalidateRequests()"),
                "only defense, shelter and pause invalidate requests directly");
        String boundary = body(methods(read(TRANSITION)), "invalidateTask");
        assertEquals(1, count(boundary, "requests.invalidateRequests()"), "one advance per task transition");
    }

    @Test
    void everyModelCallUsesGenerationPreflight() throws IOException {
        String client = normalize(read(CLIENT));
        Matcher calls = Pattern.compile("planner\\.(plan|decompose|chooseGoal|checkStep)\\(").matcher(client);
        List<String> found = new ArrayList<>();
        while (calls.find()) {
            String arguments = client.substring(calls.end(), client.indexOf(";", calls.end()));
            assertTrue(arguments.endsWith("() -> taskControl.isGenerationCurrent(token))"),
                    calls.group(1) + " passes the generation preflight");
            found.add(calls.group(1));
        }
        assertEquals(List.of("chooseGoal", "decompose", "checkStep", "plan"), found, "four model calls");

        String planner = read(PLANNER);
        assertEquals(1, count(planner, "supplyAsync("), "one place submits model work");
        String submit = method(planner, "<T> CompletableFuture<T> submit(");
        assertBefore(submit, "if (!stillCurrent.getAsBoolean())", "loggedRequest(stage, request)",
                "the preflight runs on the worker before the model call");
        for (String stage : List.of("action", "decomposition", "director", "judge")) {
            assertEquals(1, count(planner, "return submit(\"" + stage + "\", stillCurrent,"), stage + " uses submit");
        }
        String plannerCode = codeOnly(planner);
        assertFalse(plannerCode.contains("shutdownNow") || plannerCode.contains("cancelPending"),
                "stale work is skipped or discarded, never interrupted with incomplete futures");
    }

    @Test
    void everyStageOwnsBeforeSubmittingAndSettlesFirst() throws IOException {
        for (Stage stage : List.of(DIRECTOR, DECOMPOSITION, JUDGE, ACTION)) {
            String body = method(read(CLIENT), stage.method());
            assertBefore(body, "taskControl.begin(TaskControl.Stage." + stage.stage() + ")", stage.plannerCall(),
                    stage.stage() + " owns the slot before submitting");
            assertTrue(body.contains("rejectBusyRequest(TaskControl.Stage." + stage.stage() + ");"),
                    stage.stage() + " is rejected, not replaced, while another request owns the slot");
            assertTrue(body.contains(SYNCHRONOUS_FAILURE), stage.stage() + " releases ownership on a synchronous failure");
            assertTrue(callback(body).startsWith(SETTLE_GATE), stage.stage() + " settles before any stage logic");
        }
    }

    @Test
    void directorSettlesBeforeInstallingGoal() throws IOException {
        assertSettlesBefore(DIRECTOR, List.of("activeTask = chosen;", "goalReason = why.isBlank()", "noteAttempt(chosen);",
                "beginTask(client, chosen);"));
    }

    @Test
    void actionDispatchOccursOnlyAfterApplicableSettlement() throws IOException {
        String callback = callback(method(read(CLIENT), ACTION.method()));
        assertTrue(callback.startsWith(SETTLE_GATE));
        for (String dispatch : List.of("executeBaritone(", "startMine(", "startCraft(", "placeNearby(", "travel(",
                "MeleeCombat.attack(", "GameKnowledge.lookup(", "BlockBreaker.start(", "MenuActions.useBlock(",
                "MenuActions.menu(", "Screenshots.capture(", "whiteFlag(", "respawn(", "finishTask(", "advanceStep(",
                "remember(lastAction, lastResult);")) {
            assertTrue(callback.indexOf(dispatch) > SETTLE_GATE.length(), dispatch + " follows applicable settlement");
        }
    }

    @Test
    void schedulerAndHudUseTaskControl() throws IOException {
        String source = read(CLIENT);
        String tick = method(source, "public void onInitializeClient(");
        assertTrue(tick.contains("activeTask == null && !taskControl.busy()"), "goal selection waits for the slot");
        assertTrue(tick.contains("activeTask != null && !taskControl.busy()"), "planning waits for the slot");
        assertTrue(method(source, "private void tickStepCheck(").contains("|| taskControl.busy()"));
        assertTrue(method(source, "private boolean tickDeath(").contains("!taskControl.busy()"));
        assertTrue(method(source, "private void renderDebugSidebar(")
                .contains("taskControl.busyStage().map(ActionableClient::requestStageLabel)"));
    }

    @Test
    void busyStageMapsToExpectedStatusLabel() {
        assertEquals("choosing goal", ActionableClient.requestStageLabel(TaskControl.Stage.DIRECTOR));
        assertEquals("decomposing", ActionableClient.requestStageLabel(TaskControl.Stage.DECOMPOSITION));
        assertEquals("judging", ActionableClient.requestStageLabel(TaskControl.Stage.JUDGE));
        assertEquals("planning", ActionableClient.requestStageLabel(TaskControl.Stage.ACTION));
    }

    @Test
    void manualReplacementInvalidatesOnceBeforeInstalling() throws IOException {
        String submit = method(read(CLIENT), "private void submit(");
        assertEquals(1, count(submit, "invalidateTask("), "one transition per /action");
        assertBefore(submit, "invalidateTask(client, TaskTransition.Kind.MANUAL_REPLACEMENT", "activeTask = prompt;",
                "old work is stale and cleared before the replacement is installed");
        assertTrue(submit.contains(".clean()) {"), "nothing installs over a failed cleanup");
        assertFalse(submit.contains("sendChat("), "the forced cancel is the boundary's, recorded, not raw chat");
        assertFalse(submit.contains("taskControl.invalidateRequests()"), "no second invalidation");
    }

    @Test
    void everyTaskEndingAssignmentFollowsTheBoundary() throws IOException {
        Map<String, String> methods = methods(read(CLIENT));
        for (Map.Entry<String, String> method : methods.entrySet()) {
            String body = method.getValue();
            int cleared = body.indexOf("activeTask = null");
            if (cleared < 0) {
                continue;
            }
            int boundary = body.indexOf("invalidateTask(client, TaskTransition.Kind.");
            assertTrue(boundary >= 0 && boundary < cleared,
                    method.getKey() + " ends the task through the boundary before clearing it");
        }
        // Task-ending paths converted in the deadline pass all route through the boundary.
        for (String ending : List.of("abandonTask", "finishTask", "resetToDefaults", "tickNight", "tickStairs",
                "tickHomeRange", "tickSafety", "requestNextPlan", "startGoal", "submit")) {
            assertTrue(body(methods, ending).contains("invalidateTask(client, TaskTransition.Kind."),
                    ending + " routes through the boundary");
        }
        // A new task identity is assigned only where the slot was just cleaned or was already empty.
        Set<String> installs = new HashSet<>();
        methods.forEach((name, body) -> {
            if (Pattern.compile("activeTask = (?!null)").matcher(body).find()) {
                installs.add(name);
            }
        });
        assertEquals(Set.of("submit", "requestGoalSelection", "startGoal", "tickNight"), installs);
        assertBefore(body(methods, "startGoal"), "invalidateTask(client, TaskTransition.Kind.REFLEX_START",
                "activeTask = goal;", "a code-started task replaces the old one through the boundary");
    }

    @Test
    void cleanupClearsTaskOwnedWorkWithOneCancel() throws IOException {
        String cleanup = cleanupClass(read(CLIENT));
        String local = cleanup.substring(cleanup.indexOf("public void clearLocalWork()"),
                cleanup.indexOf("public void clearTaskState()"));
        for (String cleared : List.of("queuedBaritone = null;", "pendingJumpPlace = null;", "pendingMine = null;",
                "pendingHunt = null;", "eatResume = null;", "planner.attachImage(null);", "pendingStairs = null;",
                "cancelSmelt(client);", "closeContainer()", "cancelBreak(client);", "cancelCraft(client);")) {
            assertTrue(local.contains(cleared), "local cleanup clears " + cleared);
        }
        assertFalse(local.contains("stopBaritone(") || local.contains("sendChat(") || local.contains("cancelHomeWork("),
                "the executor stage sends the transition's only cancel");
        assertTrue(local.lastIndexOf("cancelCraft(client);") > local.lastIndexOf("cancelBreak(client);"),
                "the craft cancel, which can throw, runs last");
        String state = cleanup.substring(cleanup.indexOf("public void clearTaskState()"));
        for (String cleared : List.of("activeTask = null;", "taskSteps = java.util.List.of();", "currentStep = 0;",
                "directStep = null;", "finalCheck = false;")) {
            assertTrue(state.contains(cleared), "task state clears " + cleared);
        }
        assertTrue(cleanup.contains("requestBaritoneCancel(java.util.Objects.requireNonNull(client.getConnection()));"));
        assertTrue(cleanup.contains("new TaskTransition.ExecutorView(believedRunning, baritoneLoaded(),"),
                "no #cancel is sent as public chat when Baritone is not loaded");
    }

    @Test
    void cleanupFailureIsAnExecutionBarrierWithCleanupOnlyRetry() throws IOException {
        String source = read(CLIENT);
        String tick = method(source, "public void onInitializeClient(");
        assertTrue(tick.contains(FAULT_BARRIER), "the tick does no task work while cleanup is faulted");
        assertBefore(tick, FAULT_BARRIER, "flushQueuedBaritone(client);", "no queued command is sent while faulted");
        String retry = body(methods(source), "retryFailedCleanup");
        assertTrue(retry.contains("transition.retryCleanup(new ClientCleanup(client))"));
        for (String forbidden : List.of("invalidateTask(", "taskControl.invalidateRequests()", "requestBaritoneCancel(",
                "sendChat(")) {
            assertFalse(retry.contains(forbidden), "the cleanup-only retry never calls " + forbidden);
        }
    }

    @Test
    void everyCancelRequestIsRecorded() throws IOException {
        String source = read(CLIENT);
        Map<String, String> methods = methods(source);
        assertEquals(Set.of("requestBaritoneCancel"), methodsContaining(source, "sendChat(\"#cancel\")"),
                "one place sends #cancel");
        assertFalse(normalize(source).contains("\"#stop\""), "no unrecorded #stop");
        assertBefore(body(methods, "requestBaritoneCancel"), "connection.sendChat(\"#cancel\");",
                "cancelRecord = TaskTransition.cancelRequested(currentBaritoneCommand, clientTick);",
                "the record keeps the command that was running");
        String stop = body(methods, "stopBaritone");
        assertTrue(stop.contains("requestBaritoneCancel(connection);") && stop.contains("markBaritoneUnknown();"),
                "a stop records requested or unknown");
        String execute = body(methods, "executeBaritone");
        assertBefore(execute, "if (!baritoneLoaded()) {", "connection.sendChat(",
                "nothing reaches chat when Baritone is not loaded");
        assertTrue(execute.contains("requestBaritoneCancel(connection);"), "cancel commands are recorded");
        assertBefore(execute, "connection.sendChat(\"#\" + command);",
                "cancelRecord = TaskTransition.afterCommand(command, previousCommand, clientTick);",
                "each sent command applies the reset rule");
        assertTrue(body(methods, "flushQueuedBaritone").contains("&& baritoneLoaded()"));
        assertTrue(body(methods, "baritoneProgress").contains("TaskTransition.executorStatus(cancelRecord,"),
                "the model and HUD see cancel_requested or unknown, never idle, while a cancel is unconfirmed");
    }

    @Test
    void tacticalResultsAreRejectedAfterAGenerationChange() throws IOException {
        String tactics = read(TACTICS);
        String code = codeOnly(tactics);
        assertTrue(tactics.contains("private record Answer(GemmaClient.Outcome outcome, long at, long latencyMs, long generation)"));
        String choose = method(tactics, "private Tactic choose(");
        assertBefore(choose, "if (answer.generation() != generation.getAsLong()) {", "answer.outcome().decision()",
                "a reply from an older generation is never applied");
        String push = method(tactics, "private void push(");
        assertBefore(push, "if (requestGeneration != generation.getAsLong()) {", "gemma.decide(",
                "a stale request is never sent");
        assertFalse(code.contains("invalidateRequests") || code.contains("settleIfOwner"),
                "tactics never change the generation or planner ownership");
        assertTrue(read(CLIENT).contains("new TacticalAgent(taskControl::generation)"));
    }

    @Test
    void placementPreparationIsTheOnlyPlacementSwap() throws IOException {
        String client = read(CLIENT);
        assertFalse(codeOnly(client).contains("Hotbar.bring"), "no unverified placement swap");
        assertFalse(codeOnly(read(HOTBAR)).contains("handleContainerInput"), "Hotbar keeps only its slot policy");
        String torch = method(client, "private void tickTorch(");
        assertBefore(torch, "PlacementPreparation.verify(", "useItemOn(", "the torch is verified in hand before use");
        assertBefore(torch, "if (preparation.status().isFailure()) { return; }", "handleContainerInput(",
                "a failed torch preparation changes nothing");
    }

    @Test
    void modelPlansAreBoundedAtEightSteps() throws IOException {
        assertEquals(8, ActionPlanner.MAX_STEPS);
        StringBuilder json = new StringBuilder("{\"steps\":[");
        for (int step = 1; step <= 12; step++) {
            json.append(step > 1 ? "," : "").append("\"mine ").append(step).append(" minecraft:stone\"");
        }
        assertEquals(8, ActionPlanner.parseSteps(json.append("]}").toString()).size());
        assertTrue(method(read(CLIENT), "private void requestDecomposition(").contains("ActionPlanner.MAX_STEPS"));
    }

    private static void assertSettlesBefore(Stage stage, List<String> stageLogic) throws IOException {
        String callback = callback(method(read(CLIENT), stage.method()));
        assertTrue(callback.startsWith(SETTLE_GATE), stage.stage() + " settles first");
        for (String logic : stageLogic) {
            int index = callback.indexOf(logic);
            assertTrue(index > SETTLE_GATE.length(), logic + " runs only after an applicable settlement");
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static String cleanupClass(String source) {
        String declaration = "private final class ClientCleanup implements TaskTransition.Cleanup {";
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "ClientCleanup not found");
        return normalize(block(source, source.indexOf('{', start)));
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
            // The teammate client has overloads (for example position); their bodies are checked together.
            bodies.merge(declaration.group(1), normalize(block(source, open)).trim(), (a, b) -> a + " " + b);
        }
        return bodies;
    }

    private static String body(Map<String, String> methods, String name) {
        assertTrue(methods.containsKey(name), name + " not found");
        return methods.get(name);
    }

    private static Set<String> methodsContaining(String source, String marker) {
        Set<String> names = new HashSet<>();
        methods(source).forEach((name, body) -> {
            if (body.contains(marker)) {
                names.add(name);
            }
        });
        return names;
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

    private static String codeOnly(String source) {
        return strip(source, false);
    }

    private static String normalize(String text) {
        return strip(text, true).replaceAll("\\s+", " ");
    }

    private static String strip(String source, boolean keepLiterals) {
        StringBuilder code = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int literalStart = i;
                for (i++; i < source.length() && source.charAt(i) != c; i++) {
                    if (source.charAt(i) == '\\') {
                        i++;
                    }
                }
                code.append(keepLiterals ? source.substring(literalStart, Math.min(i + 1, source.length())) : c + "" + c);
            } else if (source.startsWith("//", i)) {
                int newline = source.indexOf('\n', i);
                i = newline < 0 ? source.length() : newline - 1;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 1;
            } else {
                code.append(c);
            }
        }
        return code.toString();
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
