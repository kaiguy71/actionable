package dev.actionable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the tech tree into goals: which milestone comes next and why, the exact ordered steps to reach
 * it from the current inventory, and inventory-only checks of whether a step is already done.
 */
final class ProgressPlanner {
    private static final Pattern QUANTIFIED_STEP = Pattern.compile(
            "^(mine|craft|get|gather|collect|obtain)\\s+(\\d{1,4})\\s+([a-z0-9_.-]+:[a-z0-9_./-]+)\\b.*");
    private static final Pattern PLACE_TABLE = Pattern.compile("^place\\s+minecraft:crafting_table\\b.*");

    private ProgressPlanner() {
    }

    record Milestone(String item, int count, String why) {
        /** The director-style goal text for this milestone. */
        String goal() {
            return (TechTree.recipeFor(item).isPresent() ? "craft " + count + " " + item
                    : "mine " + count + " " + TechTree.blockToMine(item));
        }
    }

    static final List<Milestone> MILESTONES = List.of(
            new Milestone("minecraft:wooden_pickaxe", 1,
                    "stone, coal and every ore drop nothing without a pickaxe; this unlocks cobblestone and coal"),
            new Milestone("minecraft:stone_pickaxe", 1,
                    "needed to mine iron ore, and mines stone faster"),
            new Milestone("minecraft:stone_sword", 1,
                    "a weapon makes nights and caves survivable"),
            new Milestone("minecraft:torch", 16,
                    "torches light caves; without them going underground is unsafe"),
            new Milestone("minecraft:furnace", 1,
                    "a furnace is required to turn raw iron into ingots"),
            new Milestone("minecraft:raw_iron", 12,
                    "iron tools and armor need iron; stockpile raw iron for smelting"));

    static boolean satisfied(Milestone milestone, Map<String, Integer> inventory) {
        TechTree.Tier pickaxe = TechTree.tierOf(milestone.item());
        if (pickaxe != TechTree.Tier.NONE) {
            return TechTree.pickaxeTier(inventory).compareTo(pickaxe) >= 0;
        }
        if (milestone.item().endsWith("_sword")) {
            return inventory.entrySet().stream().anyMatch(entry -> entry.getValue() > 0
                    && entry.getKey().endsWith("_sword") && !entry.getKey().equals("minecraft:wooden_sword"));
        }
        return TechTree.held(inventory, milestone.item()) >= milestone.count();
    }

    static Optional<Milestone> nextMilestone(Map<String, Integer> inventory) {
        return MILESTONES.stream().filter(milestone -> !satisfied(milestone, inventory)).findFirst();
    }

    private static final Pattern LOOSE_STEP = Pattern.compile(
            "^(mine|craft|get|gather|collect|obtain)\\s+(\\d{1,4})\\s+(?:minecraft:)?([a-z0-9_]+)\\b(.*)");

    /**
     * Cleans model-written steps: bare item ids gain the namespace, and repeated steps for the same
     * verb and item collapse into one with the largest total. Step quantities are totals to hold, so
     * eight "mine 2 spruce_log" steps mean "hold 2", never 16.
     */
    static List<String> normalizeSteps(List<String> steps) {
        java.util.Map<String, Integer> firstIndex = new java.util.LinkedHashMap<>();
        List<String> out = new java.util.ArrayList<>();
        for (String raw : steps) {
            String step = raw.strip();
            Matcher matcher = LOOSE_STEP.matcher(step.toLowerCase(Locale.ROOT));
            if (!matcher.matches()) {
                if (out.stream().noneMatch(step::equalsIgnoreCase)) {
                    out.add(step);
                }
                continue;
            }
            String verb = matcher.group(1).equals("craft") ? "craft" : "mine";
            int count = Integer.parseInt(matcher.group(2));
            String item = "minecraft:" + matcher.group(3);
            String key = verb + " " + item;
            Integer index = firstIndex.get(key);
            if (index == null) {
                firstIndex.put(key, out.size());
                out.add(matcher.group(1) + " " + count + " " + item + matcher.group(4));
            } else {
                Matcher existing = LOOSE_STEP.matcher(out.get(index));
                if (existing.matches() && count > Integer.parseInt(existing.group(2))) {
                    out.set(index, existing.group(1) + " " + count + " " + item + existing.group(4));
                }
            }
        }
        return List.copyOf(out);
    }

    /** A step of the form "mine 3 minecraft:stone" or "craft 4 minecraft:stick". */
    record QuantifiedStep(String verb, int count, String item) {
    }

    static Optional<QuantifiedStep> parse(String step) {
        Matcher matcher = QUANTIFIED_STEP.matcher(step.strip().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new QuantifiedStep(matcher.group(1), Integer.parseInt(matcher.group(2)), matcher.group(3)));
    }

    /**
     * Decides a step from the inventory alone when the step names a quantity and an item. Empty when
     * the step cannot be judged that way and the model judge should decide.
     */
    static Optional<Boolean> stepSatisfied(String step, Map<String, Integer> inventory, boolean tableNearby) {
        if (PLACE_TABLE.matcher(step.strip().toLowerCase(Locale.ROOT)).matches()) {
            return Optional.of(tableNearby);
        }
        return parse(step).map(parsed -> TechTree.held(inventory, parsed.item()) >= parsed.count());
    }

    /**
     * Problems that make a goal impossible right now, or empty when it is achievable. Used to replace
     * director goals like "mine 20 stone" when the player has no pickaxe.
     */
    static Optional<String> goalProblem(String goal, Map<String, Integer> inventory) {
        Optional<QuantifiedStep> parsed = parse(goal);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        String item = parsed.get().item();
        if (parsed.get().verb().equals("craft")) {
            return TechTree.recipeFor(item).isPresent() ? Optional.empty()
                    : Optional.of(item + " cannot be crafted by Actionable; craftable: " + TechTree.craftableItems());
        }
        if (parsed.get().verb().equals("mine") && TechTree.recipeFor(item).isPresent()) {
            return Optional.of(item + " is crafted, not mined");
        }
        String block = TechTree.blockToMine(item);
        TechTree.Tier needed = TechTree.toolFor(block);
        if (needed.compareTo(TechTree.pickaxeTier(inventory)) > 0) {
            return Optional.of(block + " drops nothing without a " + TechTree.pickaxeFor(needed));
        }
        return Optional.empty();
    }

    /**
     * Ordered steps that leave the inventory holding at least {@code count} of {@code item}. Quantities
     * in each step are totals to hold when that step finishes, matching Baritone's mine semantics.
     * Empty when the item cannot be produced from the tech tree.
     */
    static List<String> stepsFor(String item, int count, Map<String, Integer> inventory, boolean tableNearby,
            String preferredLog) {
        try {
            Expansion first = new Expansion(inventory, tableNearby, preferredLog, List.of());
            first.need(item, count);
            // Mining that needs no new tool is hoisted to the front; otherwise held materials get used up
            // by early crafts and the remainder is mined mid-plan, adding steps.
            List<Op> hoisted = first.ops.stream()
                    .filter(op -> op.verb.equals("mine") && op.dependency < 0).toList();
            if (hoisted.isEmpty()) {
                return first.steps(inventory);
            }
            Expansion second = new Expansion(inventory, tableNearby, preferredLog, hoisted);
            second.need(item, count);
            return second.steps(inventory);
        } catch (IllegalArgumentException unknown) {
            return List.of();
        }
    }

    /** Steps for a goal such as "craft 1 minecraft:wooden_pickaxe" or "mine 8 minecraft:stone". */
    static List<String> stepsForGoal(String goal, Map<String, Integer> inventory, boolean tableNearby,
            String preferredLog) {
        return parse(goal)
                .map(parsed -> stepsFor(parsed.verb().equals("mine") ? TechTree.dropOf(TechTree.blockToMine(parsed.item()))
                        : parsed.item(), parsed.count(), inventory, tableNearby, preferredLog))
                .orElse(List.of());
    }

    /** The ordered progression from "get wood" to the Ender Dragon, loaded from the mod's resources. */
    static String progression() {
        try (var in = ProgressPlanner.class.getResourceAsStream("/assets/actionable/progression.txt")) {
            if (in == null) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    out.append(out.length() > 0 ? "\n" : "").append(line);
                }
            }
            return out.toString();
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** Compact knowledge block for the director and decomposition prompts. */
    static String briefing(Map<String, Integer> inventory, boolean tableNearby, String preferredLog) {
        StringBuilder text = new StringBuilder("Progression briefing:");
        text.append("\n- pickaxe_tier=").append(TechTree.pickaxeTier(inventory).name().toLowerCase(Locale.ROOT));
        text.append("\n- cannot_mine_yet=").append(TechTree.cannotMineYet(inventory));
        text.append("\n- crafting_table_nearby=").append(tableNearby);
        Optional<Milestone> next = nextMilestone(inventory);
        if (next.isPresent()) {
            Milestone milestone = next.get();
            text.append("\n- next_milestone=").append(milestone.goal())
                    .append(" (why: ").append(milestone.why()).append(')');
            List<String> steps = stepsForGoal(milestone.goal(), inventory, tableNearby, preferredLog);
            if (!steps.isEmpty()) {
                text.append("\n- milestone_plan=").append(steps);
            }
        } else {
            text.append("\n- next_milestone=none (early-game milestones done; explore or gather what a plan needs)");
        }
        text.append("\n- craftable_now=").append(TechTree.craftableNow(inventory, tableNearby));
        text.append("\n- inventory_uses=").append(inventoryUses(inventory));
        text.append("\n- craftable_catalog=").append(TechTree.craftableItems());
        text.append("\n- night_prep_missing=").append(SafetyAssessment.nightPrepMissing(inventory));
        String progression = progression();
        if (!progression.isEmpty()) {
            text.append("\nGame progression (earliest unfinished stage first):\n").append(progression);
        }
        return text.toString();
    }

    static List<String> inventoryUses(Map<String, Integer> inventory) {
        List<String> uses = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            uses.add(entry.getKey() + " x" + entry.getValue() + ": "
                    + TechTree.useOf(entry.getKey()).orElse("no known use in current progression"));
        }
        return uses;
    }

    /** Recursive bill of materials that merges repeated work into as few ordered steps as possible. */
    private static final class Expansion {
        private final Map<String, Integer> stock;
        private final String preferredLog;
        private final List<Op> ops = new ArrayList<>();
        private final Map<TechTree.Tier, Integer> tierReadyAt = new HashMap<>();
        private TechTree.Tier tier;
        private boolean tableReady;
        private int tableReadyAt = -1;
        private int depth;

        Expansion(Map<String, Integer> inventory, boolean tableNearby, String preferredLog, List<Op> seeds) {
            this.stock = new HashMap<>(inventory);
            this.preferredLog = preferredLog == null || preferredLog.isBlank() ? "minecraft:oak_log" : preferredLog;
            this.tier = TechTree.pickaxeTier(inventory);
            this.tableReady = tableNearby;
            for (Op seed : seeds) {
                ops.add(new Op(seed.verb, seed.item, seed.amount, -1));
                stock.merge(TechTree.dropOf(seed.item), seed.amount, Integer::sum);
            }
        }

        /** Ensures {@code quantity} of a key is available; returns the index of the producing step or -1. */
        int need(String key, int quantity) {
            if (++depth > 32) {
                throw new IllegalArgumentException("tech tree recursion too deep for " + key);
            }
            try {
                int missing = quantity - take(key, quantity);
                if (missing <= 0) {
                    return -1;
                }
                String item = concrete(key);
                Optional<TechTree.Recipe> recipe = TechTree.recipeFor(item);
                if (recipe.isPresent()) {
                    int crafts = (missing + recipe.get().yield() - 1) / recipe.get().yield();
                    int dependency = -1;
                    for (Map.Entry<String, Integer> input : recipe.get().inputs().entrySet()) {
                        dependency = Math.max(dependency, need(input.getKey(), input.getValue() * crafts));
                    }
                    if (recipe.get().needsTable()) {
                        dependency = Math.max(dependency, ensureTable());
                    }
                    int produced = crafts * recipe.get().yield();
                    stock.merge(item, produced - missing, Integer::sum);
                    int index = add("craft", item, produced, dependency);
                    TechTree.Tier crafted = TechTree.tierOf(item);
                    if (crafted.compareTo(tier) > 0) {
                        tier = crafted;
                        tierReadyAt.put(crafted, index);
                    }
                    return index;
                }
                TechTree.Source source = TechTree.sourceFor(item)
                        .orElseThrow(() -> new IllegalArgumentException("no known source for " + item));
                return add("mine", source.block(), missing, ensureTier(source.tool()));
            } finally {
                depth--;
            }
        }

        private int ensureTier(TechTree.Tier needed) {
            if (tier.compareTo(needed) >= 0) {
                return tierReadyAt.entrySet().stream().filter(entry -> entry.getKey().compareTo(needed) >= 0)
                        .mapToInt(Map.Entry::getValue).min().orElse(-1);
            }
            return need(TechTree.pickaxeFor(needed), 1);
        }

        private int ensureTable() {
            if (tableReady) {
                return tableReadyAt;
            }
            int dependency = need(TechTree.CRAFTING_TABLE, 1);
            tableReady = true;
            tableReadyAt = add("place", TechTree.CRAFTING_TABLE, 1, dependency);
            return tableReadyAt;
        }

        /** Removes up to {@code quantity} matching items from stock and returns how many were taken. */
        private int take(String key, int quantity) {
            int taken = 0;
            for (Map.Entry<String, Integer> entry : stock.entrySet()) {
                if (taken == quantity || !TechTree.matches(key, entry.getKey())) {
                    continue;
                }
                int used = Math.min(entry.getValue(), quantity - taken);
                entry.setValue(entry.getValue() - used);
                taken += used;
            }
            return taken;
        }

        private String concrete(String key) {
            return switch (key) {
                case TechTree.LOGS -> preferredLog;
                case TechTree.PLANKS -> TechTree.planksFor(preferredLog);
                case TechTree.STONE_MATERIAL -> "minecraft:cobblestone";
                case TechTree.COALS -> "minecraft:coal";
                default -> key;
            };
        }

        /** Merges into an existing identical step that already runs after every dependency. */
        private int add(String verb, String item, int amount, int dependency) {
            for (int index = dependency + 1; index < ops.size(); index++) {
                Op op = ops.get(index);
                if (op.verb.equals(verb) && op.item.equals(item)) {
                    op.amount += amount;
                    return index;
                }
            }
            ops.add(new Op(verb, item, amount, dependency));
            return ops.size() - 1;
        }

        /** Replays the steps against the starting inventory to state each step as a total to hold. */
        List<String> steps(Map<String, Integer> inventory) {
            Map<String, Integer> simulated = new HashMap<>(inventory);
            List<String> steps = new ArrayList<>();
            for (Op op : ops) {
                switch (op.verb) {
                    case "mine" -> {
                        simulated.merge(TechTree.dropOf(op.item), op.amount, Integer::sum);
                        steps.add("mine " + TechTree.held(simulated, op.item) + " " + op.item);
                    }
                    case "craft" -> {
                        TechTree.Recipe recipe = TechTree.recipeFor(op.item).orElseThrow();
                        int crafts = op.amount / recipe.yield();
                        for (Map.Entry<String, Integer> input : recipe.inputs().entrySet()) {
                            remove(simulated, input.getKey(), input.getValue() * crafts);
                        }
                        simulated.merge(op.item, op.amount, Integer::sum);
                        steps.add("craft " + TechTree.held(simulated, op.item) + " " + op.item);
                    }
                    default -> {
                        remove(simulated, op.item, 1);
                        steps.add("place " + op.item + " nearby");
                    }
                }
            }
            return steps;
        }

        private static void remove(Map<String, Integer> inventory, String key, int quantity) {
            int remaining = quantity;
            for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
                if (remaining > 0 && TechTree.matches(key, entry.getKey())) {
                    int used = Math.min(entry.getValue(), remaining);
                    entry.setValue(entry.getValue() - used);
                    remaining -= used;
                }
            }
        }
    }

    private static final class Op {
        private final String verb;
        private final String item;
        private final int dependency;
        private int amount;

        Op(String verb, String item, int amount, int dependency) {
            this.verb = verb;
            this.item = item;
            this.amount = amount;
            this.dependency = dependency;
        }
    }
}
