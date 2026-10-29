package dev.actionable;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task- and step-level completion from observable evidence (deadline correction). Inventory totals, block
 * observations, the player's position and the dimension decide; a model claim, a visited plan step or an idle
 * Baritone never does. UNKNOWN means the evidence cannot decide and is never treated as false. Minecraft-free:
 * the client captures an {@link Observation} on the client thread, and the bounded completion-boundary policy
 * lives here so it can be tested without the game.
 */
final class TaskCompletion {
    /** A goto counts as arrived within this many blocks: the threshold tickMining already uses. */
    static final double GOTO_ARRIVAL_BLOCKS = 3.0;
    /** Final semantic review attempts per task: the first attempt plus two retries. */
    static final int MAX_FINAL_REVIEW_ATTEMPTS = 3;
    /** Minimum ticks between a failed final review and its retry. */
    static final int FINAL_REVIEW_RETRY_TICKS = 20;
    /** Replans allowed when the plan ended but the objective is observably not met. */
    static final int MAX_COMPLETION_REPLANS = 2;
    /** A placed block is read back only after the server has had time to accept or revert the request. */
    static final int PLACEMENT_SETTLE_TICKS = 10;
    /** The last-placement tick before any placement request was sent. */
    static final long NO_PLACEMENT = Long.MIN_VALUE;

    private static final String ITEM = "((?:[a-z0-9_.-]+:)?[a-z0-9_./-]+)";
    private static final String INT = "(-?\\d{1,8})";
    /** "mine 2 more minecraft:oak_log": a delta against the count when the objective started. */
    private static final Pattern MORE = Pattern.compile("^(?:mine|collect|gather|get|obtain|hunt|craft)\\s+(\\d{1,4})\\s+"
            + "(?:more|additional|extra)\\s+" + ITEM + "\\s*\\.?$");
    private static final Pattern GOTO = Pattern.compile("^(?:goto|go to|walk to|move to)\\s+" + INT + "[\\s,]+" + INT
            + "(?:[\\s,]+" + INT + ")?(?:\\s+and\\s+stop)?\\s*\\.?$");
    private static final Pattern PLACE_AT = Pattern.compile("^place\\s+" + ITEM + "\\s+at\\s+" + INT + "[\\s,]+" + INT
            + "[\\s,]+" + INT + "\\s*\\.?$");
    private static final Pattern DIMENSION = Pattern.compile(
            "^(?:go|travel|get|enter|reach|return)(?:\\s+(?:to|into))?\\s+the\\s+(nether|end|overworld)\\s*\\.?$");
    private static final Pattern HOUSE_MINE = Pattern.compile("^dig house mine for (\\d{1,4}) ([a-z0-9_.-]+:[a-z0-9_./-]+)$");
    private static final Pattern PLACE_TABLE = Pattern.compile("^place\\s+minecraft:crafting_table\\b.*");
    /** Free-form task wording for objectives the planner writes more strictly. */
    private static final Pattern FREE_CRAFT = Pattern.compile(
            "^(?:craft|make)\\s+(?:(\\d{1,4}|a|an|one)\\s+)?(?:minecraft:)?([a-z][a-z0-9_]*(?:\\s+[a-z][a-z0-9_]*){0,3})\\s*\\.?$");
    private static final Pattern FREE_GATHER = Pattern.compile(
            "^(mine|collect|gather|get|obtain|hunt)\\s+(\\d{1,4})\\s+(?:minecraft:)?([a-z0-9_]+)\\s*\\.?$");
    private static final Pattern FREE_HOUSE = Pattern.compile("^build\\s+(?:a\\s+|the\\s+|my\\s+)?house\\s*\\.?$");
    private static final Pattern FREE_IN_HOUSE = Pattern.compile("^(?:place|put)\\s+(?:a\\s+|an\\s+|the\\s+)?"
            + "(crafting\\s+table|table|door|bed|furnace)\\s+in(?:side)?\\s+(?:the\\s+|my\\s+)?house\\s*\\.?$");
    private static final Pattern HTTP_STATUS = Pattern.compile("\\bHTTP \\d{3}\\b");

    enum Verdict { SATISFIED, NOT_SATISFIED, UNKNOWN }

    /** A verdict with a short evidence note for the log, the HUD and the model's last result. */
    record Assessment(Verdict verdict, String evidence) {
        Assessment {
            Objects.requireNonNull(verdict, "verdict");
            evidence = evidence == null ? "" : evidence;
        }

        static Assessment of(boolean met, String evidence) {
            return new Assessment(met ? Verdict.SATISFIED : Verdict.NOT_SATISFIED, evidence);
        }

        static Assessment unknown(String why) {
            return new Assessment(Verdict.UNKNOWN, why);
        }
    }

    /** What the completion boundary does next. None of them reopens generic action planning. */
    enum Decision {
        /** Evidence proves the objective, or nothing more can be checked for this task: finish now. */
        FINISH,
        /** Evidence cannot decide: send the bounded final semantic review. */
        REVIEW,
        /** Evidence says the objective is not met: keep the task and rebuild its plan, within budget. */
        REPLAN,
        /** A code-run job's plan ended without its observable result: end it without claiming success. */
        END,
        /** Stay at the boundary: a review retry is not due yet, or the review or replan budget is spent. */
        HOLD
    }

    /** Block id at a position, or null when that chunk is not loaded. */
    interface BlockLookup {
        String at(int x, int y, int z);
    }

    /**
     * Everything the predicates read, captured on the client thread. {@code houseCompletion} is the share of the
     * house schematic in place, or null when the house cannot be observed; {@code lastPlacementTick} is the tick
     * of the latest placement request, whose block is only trusted in a later observation.
     */
    record Observation(long tick, Map<String, Integer> inventory, ProgressPlanner.Facts facts, boolean homeLoaded,
            Double houseCompletion, boolean tableNearby, int x, int y, int z, String dimension, BlockLookup blocks,
            long lastPlacementTick, boolean smelted) {
        Observation {
            inventory = Map.copyOf(inventory);
            facts = facts == null ? ProgressPlanner.Facts.NONE : facts;
        }

        boolean placementSettled() {
            return TaskCompletion.placementSettled(tick, lastPlacementTick);
        }
    }

    private TaskCompletion() {
    }

    /**
     * Decides {@code objective} (a task or a plan step) from {@code observation}. {@code baseline} is the item
     * count when the objective started; only "N more" objectives need it.
     */
    static Assessment assess(String objective, Observation observation, Integer baseline) {
        if (objective == null || objective.isBlank()) {
            return Assessment.unknown("no objective");
        }
        String text = canonical(objective);
        Map<String, Integer> inventory = observation.inventory();
        Matcher more = MORE.matcher(text);
        if (more.matches()) {
            int wanted = Integer.parseInt(more.group(1));
            String item = namespaced(more.group(2));
            if (baseline == null) {
                return Assessment.unknown("no starting count for " + item);
            }
            int gained = TechTree.held(inventory, item) - baseline;
            return Assessment.of(gained >= wanted, "gained " + Math.max(0, gained) + "/" + wanted + " " + item
                    + " since the start (" + baseline + ")");
        }
        Matcher goTo = GOTO.matcher(text);
        if (goTo.matches()) {
            int gx = Integer.parseInt(goTo.group(1));
            boolean column = goTo.group(3) == null;
            int gy = column ? observation.y() : Integer.parseInt(goTo.group(2));
            int gz = Integer.parseInt(column ? goTo.group(2) : goTo.group(3));
            double distance = Math.sqrt(square(observation.x() - gx) + square(observation.y() - gy)
                    + square(observation.z() - gz));
            return Assessment.of(distance <= GOTO_ARRIVAL_BLOCKS, String.format(Locale.ROOT, "%.1f blocks from the goal",
                    distance));
        }
        Matcher dimension = DIMENSION.matcher(text);
        if (dimension.matches()) {
            String wanted = dimension.group(1).equals("overworld") ? "minecraft:overworld"
                    : "minecraft:the_" + dimension.group(1);
            if (observation.dimension() == null || observation.dimension().isBlank()) {
                return Assessment.unknown("dimension unknown");
            }
            return Assessment.of(wanted.equals(observation.dimension()), "in " + observation.dimension());
        }
        Matcher placeAt = PLACE_AT.matcher(text);
        if (placeAt.matches()) {
            String block = namespaced(placeAt.group(1));
            int x = Integer.parseInt(placeAt.group(2));
            int y = Integer.parseInt(placeAt.group(3));
            int z = Integer.parseInt(placeAt.group(4));
            if (!observation.placementSettled()) {
                return Assessment.unknown("placement request sent; waiting for a later observation");
            }
            String seen = observation.blocks() == null ? null : observation.blocks().at(x, y, z);
            if (seen == null) {
                return Assessment.unknown("target " + x + "," + y + "," + z + " is not loaded");
            }
            return Assessment.of(block.equals(seen), "observed " + seen + " at " + x + "," + y + "," + z);
        }
        if (text.equals(ProgressPlanner.BUILD_HOUSE)) {
            Double share = observation.houseCompletion();
            if (share == null) {
                return Assessment.unknown("house not loaded");
            }
            // The same probe and threshold as the house_built log line.
            return Assessment.of(share >= HouseDesign.COMPLETE, String.format(Locale.ROOT,
                    "house structure %.0f%% in place (complete at %.0f%%)", share * 100, HouseDesign.COMPLETE * 100));
        }
        if (text.equals(ProgressPlanner.PLACE_TABLE_IN_HOUSE)) {
            return houseCell(observation, observation.facts().tableInHouse(), "crafting table at the house table cell");
        }
        if (text.equals(ProgressPlanner.PLACE_DOOR)) {
            return houseCell(observation, observation.facts().doorPlaced(), "door in the house doorway");
        }
        if (text.equals(ProgressPlanner.PLACE_BED)) {
            return houseCell(observation, observation.facts().bedPlaced(), "bed in the house");
        }
        if (text.equals(NeedsPlanner.PLACE_FURNACE)) {
            return houseCell(observation, observation.facts().furnaceInHouse(), "furnace at the house furnace cell");
        }
        if (text.equals(ProgressPlanner.GO_HOME)) {
            return observation.homeLoaded() ? Assessment.of(observation.facts().atHome(), "inside the house")
                    : Assessment.unknown("house not loaded");
        }
        if (text.equals(ProgressPlanner.LAY_FLOOR)) {
            return observation.homeLoaded() ? Assessment.of(observation.facts().floorReady(), "house floor ready")
                    : Assessment.unknown("house not loaded");
        }
        if (text.equals(ProgressPlanner.GATHER_FOOD)) {
            int stock = FoodPlan.stock(inventory);
            return Assessment.of(stock >= FoodPlan.STOCK_WANTED,
                    "carrying " + stock + "/" + FoodPlan.STOCK_WANTED + " hunger points of food");
        }
        if (text.equals(NeedsPlanner.SMELT)) {
            // The smelter reports SMELTED only after loading and collecting a load; a visited step proves nothing.
            return Assessment.of(observation.smelted(), observation.smelted()
                    ? "the furnace finished a load" : "no completed furnace load for this task");
        }
        Matcher houseMine = HOUSE_MINE.matcher(text);
        if (houseMine.matches()) {
            int wanted = Integer.parseInt(houseMine.group(1));
            String item = houseMine.group(2);
            int have = TechTree.held(inventory, item);
            if (have >= wanted) {
                return Assessment.of(true, "holding " + have + "/" + wanted + " " + item);
            }
            // The stairs reached their end or a hazard: the dig is over, but the total is not proven.
            return observation.facts().mineFinished()
                    ? Assessment.unknown("the house mine ended at " + have + "/" + wanted + " " + item)
                    : Assessment.of(false, "holding " + have + "/" + wanted + " " + item);
        }
        if (PLACE_TABLE.matcher(text).matches() && !text.endsWith(" in house")) {
            if (!observation.placementSettled()) {
                return Assessment.unknown("placement request sent; waiting for a later observation");
            }
            return Assessment.of(observation.tableNearby(), observation.tableNearby()
                    ? "a placed crafting table is nearby" : "no placed crafting table nearby");
        }
        Optional<ProgressPlanner.QuantifiedStep> parsed = ProgressPlanner.parse(text);
        if (parsed.isPresent()) {
            // craft, mine, hunt, collect: totals to hold, the same semantics as the plan steps and Baritone.
            int have = TechTree.held(inventory, parsed.get().item());
            return Assessment.of(have >= parsed.get().count(),
                    "holding " + have + "/" + parsed.get().count() + " " + parsed.get().item());
        }
        return Assessment.unknown("no observable predicate for this objective");
    }

    private static Assessment houseCell(Observation observation, boolean present, String what) {
        if (!observation.homeLoaded()) {
            return Assessment.unknown("house not loaded");
        }
        if (!observation.placementSettled()) {
            return Assessment.unknown("placement request sent; waiting for a later observation");
        }
        return Assessment.of(present, (present ? "observed " : "not observed: ") + what);
    }

    /**
     * The objective in the planner's own wording when free-form task text names one ("craft a stone pickaxe",
     * "place table in house"); otherwise the trimmed lower-case text. Crafted items must have a known recipe.
     */
    static String canonical(String objective) {
        String text = objective.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (FREE_HOUSE.matcher(text).matches()) {
            return ProgressPlanner.BUILD_HOUSE;
        }
        Matcher inHouse = FREE_IN_HOUSE.matcher(text);
        if (inHouse.matches()) {
            return switch (inHouse.group(1)) {
                case "door" -> ProgressPlanner.PLACE_DOOR;
                case "bed" -> ProgressPlanner.PLACE_BED;
                case "furnace" -> NeedsPlanner.PLACE_FURNACE;
                default -> ProgressPlanner.PLACE_TABLE_IN_HOUSE;
            };
        }
        Matcher craft = FREE_CRAFT.matcher(text);
        if (craft.matches()) {
            String count = craft.group(1) == null || !Character.isDigit(craft.group(1).charAt(0)) ? "1" : craft.group(1);
            String name = craft.group(2).replace(' ', '_');
            for (String candidate : new String[] {name, trimSuffix(name, "es"), trimSuffix(name, "s")}) {
                if (candidate != null && TechTree.recipeFor("minecraft:" + candidate).isPresent()) {
                    return "craft " + count + " minecraft:" + candidate;
                }
            }
        }
        Matcher gather = FREE_GATHER.matcher(text);
        if (gather.matches()) {
            return gather.group(1) + " " + gather.group(2) + " minecraft:" + gather.group(3);
        }
        return text;
    }

    private static String trimSuffix(String name, String suffix) {
        return name.length() > suffix.length() && name.endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length()) : null;
    }

    private static String namespaced(String item) {
        return item.contains(":") ? item : "minecraft:" + item;
    }

    private static double square(double value) {
        return value * value;
    }

    /** The item an "N more" objective counts from its starting baseline; empty for every other objective. */
    static Optional<String> additiveItem(String objective) {
        if (objective == null) {
            return Optional.empty();
        }
        Matcher more = MORE.matcher(canonical(objective));
        return more.matches() ? Optional.of(namespaced(more.group(2))) : Optional.empty();
    }

    /** A model's step claim is accepted only when the observable evidence does not contradict it. */
    static boolean claimAccepted(Verdict evidence) {
        return evidence != Verdict.NOT_SATISFIED;
    }

    /**
     * The completion boundary's next move. Evidence first; a semantic review only when the evidence cannot decide,
     * at most {@link #MAX_FINAL_REVIEW_ATTEMPTS} attempts per task spaced {@link #FINAL_REVIEW_RETRY_TICKS} apart.
     * Code-run jobs (night work, reflexes) never had a review and keep finishing when evidence cannot decide; after
     * one review has answered, the model's next completion claim is accepted unless evidence contradicts it.
     */
    static Decision atBoundary(Verdict verdict, boolean codeRun, boolean reviewed, int reviewFailures,
            long reviewNotBefore, int replans, long tick) {
        return switch (verdict) {
            case SATISFIED -> Decision.FINISH;
            case NOT_SATISFIED -> codeRun ? Decision.END
                    : replans < MAX_COMPLETION_REPLANS ? Decision.REPLAN : Decision.HOLD;
            case UNKNOWN -> codeRun || reviewed ? Decision.FINISH
                    : reviewExhausted(reviewFailures) || tick < reviewNotBefore ? Decision.HOLD : Decision.REVIEW;
        };
    }

    static boolean reviewExhausted(int reviewFailures) {
        return reviewFailures >= MAX_FINAL_REVIEW_ATTEMPTS;
    }

    /** A placement request's block may be read back once the server has had time to answer it. */
    static boolean placementSettled(long tick, long lastPlacementTick) {
        return lastPlacementTick == NO_PLACEMENT || tick - lastPlacementTick >= PLACEMENT_SETTLE_TICKS;
    }

    /** Baritone gave a mine up because no target could be reached ("Unable to find any path ..., canceling mine"). */
    static boolean mineNoPath(String baritoneFeedback) {
        return baritoneFeedback != null && baritoneFeedback.contains("Unable to find any path")
                && baritoneFeedback.contains("canceling mine");
    }

    /** A bounded failure code for a final review that did not produce a decision. */
    static String reviewFailureCode(Throwable error) {
        boolean http = false;
        boolean timeout = false;
        boolean parse = false;
        boolean transport = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            http |= HTTP_STATUS.matcher(message).find();
            timeout |= cause.getClass().getSimpleName().contains("Timeout") || message.startsWith("CLAUDE_TIMEOUT")
                    || message.toLowerCase(Locale.ROOT).contains("timed out");
            parse |= cause instanceof com.google.gson.JsonParseException || cause instanceof NumberFormatException
                    || message.startsWith("Not a JSON") || message.startsWith("CLAUDE_INVALID_JSON");
            transport |= cause instanceof java.io.IOException || message.contains("Connection refused")
                    || message.startsWith("CLAUDE_REQUEST_FAILED");
        }
        if (http) {
            return "FINAL_REVIEW_HTTP_FAILED";
        }
        if (timeout) {
            return "FINAL_REVIEW_TIMEOUT";
        }
        if (parse) {
            return "FINAL_REVIEW_PARSE_FAILED";
        }
        return transport ? "FINAL_REVIEW_TRANSPORT_FAILED" : "FINAL_REVIEW_PROVIDER_FAILED";
    }

    /**
     * One task's completion bookkeeping: why the boundary was entered, the final-review and replan budgets, the
     * starting counts of "N more" objectives and whether the smelter finished a load. Reset on every task
     * transition (new task, replacement, finish, end, disconnect) and on death.
     */
    static final class Tracker {
        private String reason = "";
        private int reviewFailures;
        private long reviewNotBefore;
        private boolean reviewPromptReady;
        private boolean reviewed;
        private int replans;
        private String exposed = "";
        private boolean smelted;
        private Integer taskBaseline;
        private String stepKey;
        private Integer stepBaseline;

        void reset() {
            reason = "";
            reviewFailures = 0;
            reviewNotBefore = 0;
            reviewPromptReady = false;
            reviewed = false;
            replans = 0;
            exposed = "";
            smelted = false;
            taskBaseline = null;
            stepKey = null;
            stepBaseline = null;
        }

        void enter(String why) {
            reason = why == null ? "" : why;
        }

        String reason() {
            return reason;
        }

        Decision decide(Verdict verdict, boolean codeRun, long tick) {
            return atBoundary(verdict, codeRun, reviewed, reviewFailures, reviewNotBefore, replans, tick);
        }

        boolean reviewPromptReady() {
            return reviewPromptReady;
        }

        void reviewPromptPrepared() {
            reviewPromptReady = true;
        }

        /** A review attempt failed: count it, space the retry, and rebuild the prompt and screenshot for it. */
        void reviewFailed(long tick) {
            reviewFailures++;
            reviewNotBefore = tick + FINAL_REVIEW_RETRY_TICKS;
            reviewPromptReady = false;
        }

        /** A review answered that work is left; the model's next completion claim is not reviewed again. */
        void reviewAnswered() {
            reviewed = true;
            reviewPromptReady = false;
        }

        int reviewFailures() {
            return reviewFailures;
        }

        long reviewNotBefore() {
            return reviewNotBefore;
        }

        boolean reviewExhausted() {
            return TaskCompletion.reviewExhausted(reviewFailures);
        }

        void replanned() {
            replans++;
        }

        int replans() {
            return replans;
        }

        /** True the first time {@code code} is exposed while the task stays at the boundary. */
        boolean expose(String code) {
            if (code.equals(exposed)) {
                return false;
            }
            exposed = code;
            return true;
        }

        void smelted() {
            smelted = true;
        }

        boolean hasSmelted() {
            return smelted;
        }

        /** Records the task's starting count for an "N more" task, once. */
        void noteTaskStart(String task, Map<String, Integer> inventory) {
            if (taskBaseline == null) {
                taskBaseline = additiveItem(task).map(item -> TechTree.held(inventory, item)).orElse(null);
            }
        }

        /** Records a step's starting count for an "N more" step when the step (by index and text) begins. */
        void noteStepStart(int index, String step, Map<String, Integer> inventory) {
            String key = index + ":" + step;
            if (!key.equals(stepKey)) {
                stepKey = key;
                stepBaseline = additiveItem(step).map(item -> TechTree.held(inventory, item)).orElse(null);
            }
        }

        Integer taskBaseline() {
            return taskBaseline;
        }

        Integer stepBaseline(int index, String step) {
            return (index + ":" + step).equals(stepKey) ? stepBaseline : null;
        }
    }
}
