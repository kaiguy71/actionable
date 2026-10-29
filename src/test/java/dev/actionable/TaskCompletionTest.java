package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.actionable.TaskCompletion.Assessment;
import dev.actionable.TaskCompletion.Decision;
import dev.actionable.TaskCompletion.Verdict;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Task completion from observable evidence and the bounded completion boundary (deadline correction). Pure:
 * observations are built by hand, so these prove the predicates and the policy, not in-game behavior.
 */
class TaskCompletionTest {
    private static final long TICK = 1_000;

    /** A hand-built observation: nothing held, no house, overworld origin, no placement yet. */
    private static final class World {
        long tick = TICK;
        Map<String, Integer> inventory = Map.of();
        ProgressPlanner.Facts facts = ProgressPlanner.Facts.NONE;
        boolean homeLoaded;
        Double house;
        boolean tableNearby;
        int x;
        int y = 64;
        int z;
        String dimension = "minecraft:overworld";
        final Map<String, String> blocks = new HashMap<>();
        long lastPlacement = TaskCompletion.NO_PLACEMENT;
        boolean smelted;

        TaskCompletion.Observation observe() {
            return new TaskCompletion.Observation(tick, inventory, facts, homeLoaded, house, tableNearby, x, y, z,
                    dimension, (bx, by, bz) -> blocks.get(bx + "," + by + "," + bz), lastPlacement, smelted);
        }

        Assessment assess(String objective) {
            return TaskCompletion.assess(objective, observe(), null);
        }
    }

    private static ProgressPlanner.Facts facts(boolean tableInHouse, boolean doorPlaced) {
        return new ProgressPlanner.Facts(false, false, tableInHouse, doorPlaced, false, false, false, false);
    }

    /** The boundary's decision for a user or director task with nothing spent yet. */
    private static Decision fresh(Verdict verdict) {
        return TaskCompletion.atBoundary(verdict, false, false, 0, 0, 0, TICK);
    }

    @Test
    void craftedTargetInventoryFinishesWithoutFinalReview() {
        World world = new World();
        world.inventory = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 2);
        for (String task : List.of("craft 1 minecraft:stone_pickaxe", "craft stone pickaxe", "Craft a stone pickaxe")) {
            Assessment evidence = world.assess(task);
            assertEquals(Verdict.SATISFIED, evidence.verdict(), task);
            assertEquals(Decision.FINISH, fresh(evidence.verdict()), task + " finishes without a model request");
        }
        // The plan's extra table pickup cannot hold the task open, and does not decide it either.
        assertEquals(Verdict.NOT_SATISFIED, world.assess("mine 1 minecraft:crafting_table").verdict());
        assertEquals(Verdict.SATISFIED, world.assess("craft stone pickaxe").verdict());

        world.inventory = Map.of("minecraft:cobblestone", 3);
        assertEquals(Verdict.NOT_SATISFIED, world.assess("craft stone pickaxe").verdict(), "not crafted yet");
        assertNotEquals(Decision.REVIEW, fresh(Verdict.NOT_SATISFIED), "a known miss is replanned, not reviewed");
    }

    @Test
    void completedHouseFinishesWithoutFinalReview() {
        World world = new World();
        world.homeLoaded = true;
        world.house = 0.90;
        Assessment built = world.assess("build house");
        assertEquals(Verdict.SATISFIED, built.verdict());
        assertEquals(Decision.FINISH, fresh(built.verdict()));
        assertEquals(Verdict.SATISFIED, world.assess("Build a house").verdict(), "free-form wording");

        // The same probe and inclusive threshold as the house_built log line.
        world.house = HouseDesign.COMPLETE;
        assertEquals(Verdict.SATISFIED, world.assess("build house").verdict());
        world.house = HouseDesign.COMPLETE - 0.01;
        assertEquals(Verdict.NOT_SATISFIED, world.assess("build house").verdict());

        // Baritone going idle on the schematic (the houseBuilt latch) is not the structure.
        world.facts = new ProgressPlanner.Facts(true, false);
        world.house = 0.50;
        assertEquals(Verdict.NOT_SATISFIED, world.assess("build house").verdict());

        world.homeLoaded = false;
        world.house = null;
        assertEquals(Verdict.UNKNOWN, world.assess("build house").verdict(), "an unloaded house is not false");
    }

    @Test
    void tableObservedInHouseFinishesWithoutFinalReview() {
        World world = new World();
        world.homeLoaded = true;
        world.facts = facts(true, false);
        for (String task : List.of(ProgressPlanner.PLACE_TABLE_IN_HOUSE, "place table in house",
                "Place a crafting table in the house")) {
            Assessment evidence = world.assess(task);
            assertEquals(Verdict.SATISFIED, evidence.verdict(), task);
            assertEquals(Decision.FINISH, fresh(evidence.verdict()), task);
        }
        // Only the house's table cell counts: a table elsewhere (tableNearby) does not.
        world.facts = facts(false, false);
        world.tableNearby = true;
        assertEquals(Verdict.NOT_SATISFIED, world.assess("place table in house").verdict());
    }

    @Test
    void doorObservedAtHouseEntranceFinishesWithoutFinalReview() {
        World world = new World();
        world.homeLoaded = true;
        world.facts = facts(false, true);
        for (String task : List.of(ProgressPlanner.PLACE_DOOR, "place a door in the house")) {
            Assessment evidence = world.assess(task);
            assertEquals(Verdict.SATISFIED, evidence.verdict(), task);
            assertEquals(Decision.FINISH, fresh(evidence.verdict()), task);
        }
        world.facts = facts(false, false);
        assertEquals(Verdict.NOT_SATISFIED, world.assess(ProgressPlanner.PLACE_DOOR).verdict(),
                "no door, or a stray door outside the doorway");
    }

    @Test
    void placementRequestAloneDoesNotFinishTask() {
        World world = new World();
        world.homeLoaded = true;
        world.facts = facts(true, false);
        world.blocks.put("1,64,2", "minecraft:torch");
        // The request was sent this tick: even a client-side copy of the block is not evidence yet.
        world.lastPlacement = TICK;
        for (String task : List.of(ProgressPlanner.PLACE_TABLE_IN_HOUSE, "place minecraft:torch at 1 64 2",
                "place minecraft:crafting_table")) {
            Assessment evidence = world.assess(task);
            assertEquals(Verdict.UNKNOWN, evidence.verdict(), task);
            assertTrue(evidence.evidence().contains("later observation"), evidence.evidence());
            assertNotEquals(Decision.FINISH, fresh(evidence.verdict()), task);
        }
        world.tick = TICK + TaskCompletion.PLACEMENT_SETTLE_TICKS - 1;
        assertEquals(Verdict.UNKNOWN, world.assess("place minecraft:torch at 1 64 2").verdict());
        assertFalse(TaskCompletion.placementSettled(world.tick, world.lastPlacement));
        assertTrue(PlacementPreparation.placementIssued("minecraft:crafting_table", "(0,70,3)")
                .startsWith("placement request sent"), "the request wording is kept");
    }

    @Test
    void placementObservationFinishesTask() {
        World world = new World();
        world.homeLoaded = true;
        world.facts = facts(true, false);
        world.lastPlacement = TICK - TaskCompletion.PLACEMENT_SETTLE_TICKS;
        world.blocks.put("1,64,2", "minecraft:torch");
        assertTrue(TaskCompletion.placementSettled(world.tick, world.lastPlacement));
        for (String task : List.of(ProgressPlanner.PLACE_TABLE_IN_HOUSE, "place minecraft:torch at 1 64 2")) {
            Assessment evidence = world.assess(task);
            assertEquals(Verdict.SATISFIED, evidence.verdict(), task);
            assertEquals(Decision.FINISH, fresh(evidence.verdict()), task);
        }
        // The server reverted the block: the later observation says so.
        world.blocks.put("1,64,2", "minecraft:air");
        assertEquals(Verdict.NOT_SATISFIED, world.assess("place minecraft:torch at 1 64 2").verdict());
        world.blocks.clear();
        assertEquals(Verdict.UNKNOWN, world.assess("place minecraft:torch at 1 64 2").verdict(), "target unloaded");
    }

    @Test
    void baritonePathFailureDoesNotCompleteMineStep() {
        assertTrue(TaskCompletion.mineNoPath(
                "[Baritone] Unable to find any path to minecraft:crafting_table, canceling mine"));
        assertFalse(TaskCompletion.mineNoPath("[Baritone] Unable to find any path to minecraft:stone,"
                + " blacklisting presumably unreachable closest instance..."), "Baritone keeps mining other targets");
        assertFalse(TaskCompletion.mineNoPath("[Baritone] ok canceled"));

        World world = new World();
        world.inventory = Map.of("minecraft:stone_pickaxe", 1);
        Assessment pickup = world.assess("mine 1 minecraft:crafting_table");
        assertEquals(Verdict.NOT_SATISFIED, pickup.verdict());
        assertFalse(TaskCompletion.claimAccepted(pickup.verdict()), "a model step_complete claim is rejected");
    }

    @Test
    void baritoneIdleAloneDoesNotCompleteMineStep() {
        // Nothing about Baritone (idle, stopped, cancelled, elapsed time) is an input to completion.
        assertTrue(Arrays.stream(TaskCompletion.Observation.class.getRecordComponents())
                .noneMatch(component -> component.getName().toLowerCase(Locale.ROOT).contains("baritone")));
        World world = new World();
        world.inventory = Map.of("minecraft:oak_log", 2);
        assertEquals(Verdict.NOT_SATISFIED, world.assess("mine 5 minecraft:oak_log").verdict());
        assertFalse(TaskCompletion.claimAccepted(Verdict.NOT_SATISFIED));
        world.inventory = Map.of("minecraft:oak_log", 5);
        assertEquals(Verdict.SATISFIED, world.assess("mine 5 minecraft:oak_log").verdict());
        assertTrue(TaskCompletion.claimAccepted(Verdict.UNKNOWN), "UNKNOWN does not reject a claim");
    }

    @Test
    void mineDeltaUsesStepBaseline() {
        String step = "mine 2 more minecraft:oak_log";
        TaskCompletion.Tracker tracker = new TaskCompletion.Tracker();
        tracker.noteStepStart(0, step, Map.of("minecraft:oak_log", 5));
        assertEquals(Integer.valueOf(5), tracker.stepBaseline(0, step));
        tracker.noteStepStart(0, step, Map.of("minecraft:oak_log", 6));
        assertEquals(Integer.valueOf(5), tracker.stepBaseline(0, step), "the baseline is taken once, when the step starts");

        World world = new World();
        world.inventory = Map.of("minecraft:oak_log", 6);
        assertEquals(Verdict.NOT_SATISFIED, TaskCompletion.assess(step, world.observe(), 5).verdict());
        world.inventory = Map.of("minecraft:oak_log", 7);
        assertEquals(Verdict.SATISFIED, TaskCompletion.assess(step, world.observe(), 5).verdict());
        assertEquals(Verdict.UNKNOWN, TaskCompletion.assess(step, world.observe(), null).verdict(),
                "without a baseline the delta is unknown, not false");
        // Total forms keep the planner's "hold N" meaning and need no baseline.
        assertEquals(Verdict.SATISFIED, world.assess("mine 7 minecraft:oak_log").verdict());

        tracker.noteStepStart(1, step, Map.of("minecraft:oak_log", 7));
        assertEquals(Integer.valueOf(7), tracker.stepBaseline(1, step), "a later step gets its own baseline");
        assertNull(tracker.stepBaseline(0, step));
        tracker.noteStepStart(2, "mine 3 minecraft:stone", Map.of());
        assertNull(tracker.stepBaseline(2, "mine 3 minecraft:stone"), "total forms record none");
    }

    @Test
    void deterministicUnknownInvokesFinalReview() {
        World world = new World();
        Assessment subjective = world.assess("build a nice garden next to the house");
        assertEquals(Verdict.UNKNOWN, subjective.verdict());
        assertEquals(Decision.REVIEW, fresh(subjective.verdict()));
        // Code-run jobs never had a review, and after one review has answered the next claim is not re-reviewed.
        assertEquals(Decision.FINISH, TaskCompletion.atBoundary(Verdict.UNKNOWN, true, false, 0, 0, 0, TICK));
        assertEquals(Decision.FINISH, TaskCompletion.atBoundary(Verdict.UNKNOWN, false, true, 0, 0, 0, TICK));
        // UNKNOWN is never treated as false.
        for (int replans = 0; replans <= TaskCompletion.MAX_COMPLETION_REPLANS; replans++) {
            Decision decision = TaskCompletion.atBoundary(Verdict.UNKNOWN, false, false, 0, 0, replans, TICK);
            assertNotEquals(Decision.REPLAN, decision);
            assertNotEquals(Decision.END, decision);
        }
    }

    @Test
    void deterministicSatisfiedSkipsFinalReview() {
        for (boolean codeRun : new boolean[] {false, true}) {
            for (int failures = 0; failures <= TaskCompletion.MAX_FINAL_REVIEW_ATTEMPTS; failures++) {
                for (int replans = 0; replans <= TaskCompletion.MAX_COMPLETION_REPLANS; replans++) {
                    assertEquals(Decision.FINISH, TaskCompletion.atBoundary(Verdict.SATISFIED, codeRun, false,
                            failures, TICK + 100, replans, TICK), "evidence finishes even with budgets spent");
                }
            }
        }
    }

    @Test
    void finalReviewRetriesAreBounded() {
        TaskCompletion.Tracker tracker = new TaskCompletion.Tracker();
        long tick = TICK;
        int attempts = 0;
        while (tracker.decide(Verdict.UNKNOWN, false, tick) == Decision.REVIEW) {
            attempts++;
            tracker.reviewFailed(tick);
            assertEquals(Decision.HOLD, tracker.decide(Verdict.UNKNOWN, false, tick + 1), "no immediate retry");
            assertEquals(Decision.HOLD, tracker.decide(Verdict.UNKNOWN, false,
                    tick + TaskCompletion.FINAL_REVIEW_RETRY_TICKS - 1), "retries are 20 ticks apart");
            tick += TaskCompletion.FINAL_REVIEW_RETRY_TICKS;
            assertTrue(attempts <= TaskCompletion.MAX_FINAL_REVIEW_ATTEMPTS, "bounded");
        }
        assertEquals(3, attempts, "one review plus two retries per task");
        assertTrue(tracker.reviewExhausted());
        assertEquals(Decision.HOLD, tracker.decide(Verdict.UNKNOWN, false, tick + 10_000));
    }

    @Test
    void finalReviewExhaustionKeepsTaskIncomplete() {
        TaskCompletion.Tracker tracker = new TaskCompletion.Tracker();
        for (int attempt = 0; attempt < TaskCompletion.MAX_FINAL_REVIEW_ATTEMPTS; attempt++) {
            tracker.reviewFailed(TICK);
        }
        Decision exhausted = tracker.decide(Verdict.UNKNOWN, false, TICK + 10_000);
        assertEquals(Decision.HOLD, exhausted, "the task stays open; it is neither finished nor dropped");
        assertTrue(tracker.expose("FINAL_REVIEW_EXHAUSTED"), "the failure is exposed");
        assertFalse(tracker.expose("FINAL_REVIEW_EXHAUSTED"), "once, while the task holds");
        // Deterministic rechecks continue: evidence still finishes or replans the held task.
        assertEquals(Decision.FINISH, tracker.decide(Verdict.SATISFIED, false, TICK + 10_000));
        assertEquals(Decision.REPLAN, tracker.decide(Verdict.NOT_SATISFIED, false, TICK + 10_000));
    }

    @Test
    void completionMismatchReplansAreBounded() {
        TaskCompletion.Tracker tracker = new TaskCompletion.Tracker();
        int replans = 0;
        while (tracker.decide(Verdict.NOT_SATISFIED, false, TICK) == Decision.REPLAN) {
            tracker.replanned();
            replans++;
            assertTrue(replans <= TaskCompletion.MAX_COMPLETION_REPLANS);
        }
        assertEquals(TaskCompletion.MAX_COMPLETION_REPLANS, replans);
        assertEquals(Decision.HOLD, tracker.decide(Verdict.NOT_SATISFIED, false, TICK), "kept open and exposed");
        assertEquals(Decision.END, tracker.decide(Verdict.NOT_SATISFIED, true, TICK),
                "a code-run job ends without claiming success, as its routine expects");
    }

    @Test
    void observableObjectivesUseTheirOwnPredicates() {
        World world = new World();
        world.x = 10;
        world.y = 64;
        world.z = -20;
        assertEquals(Verdict.SATISFIED, world.assess("goto 12 64 -21").verdict(), "within 3 blocks");
        assertEquals(Verdict.NOT_SATISFIED, world.assess("goto 20 64 -20").verdict());
        assertEquals(Verdict.SATISFIED, world.assess("goto 11 -19").verdict(), "column goto");
        assertEquals(Verdict.NOT_SATISFIED, world.assess("go to the nether").verdict());
        world.dimension = "minecraft:the_nether";
        assertEquals(Verdict.SATISFIED, world.assess("go to the nether").verdict());
        assertEquals(Verdict.NOT_SATISFIED, world.assess(NeedsPlanner.SMELT).verdict(), "a visited smelt step");
        world.smelted = true;
        assertEquals(Verdict.SATISFIED, world.assess(NeedsPlanner.SMELT).verdict());
        world.facts = new ProgressPlanner.Facts(false, false, false, false, false, false, false, true);
        assertEquals(Verdict.UNKNOWN, world.assess(NeedsPlanner.MINE_COBBLE).verdict(),
                "a finished house mine short of its total is not proof either way");
    }

    @Test
    void ollamaErrorBodyExcerptIsBounded() {
        assertEquals("", ActionPlanner.errorExcerpt(null));
        assertEquals("", ActionPlanner.errorExcerpt("  "));
        assertEquals(": {\"error\":\"bad request\"}", ActionPlanner.errorExcerpt("{\"error\":\"bad request\"}\n"));
        String excerpt = ActionPlanner.errorExcerpt("x".repeat(5_000) + "\u0000\u001b[31m");
        assertEquals(2 + ActionPlanner.ERROR_BODY_LIMIT + 3, excerpt.length(), "bounded");
        assertFalse(excerpt.chars().anyMatch(Character::isISOControl), "single line, no control characters");
    }
}
