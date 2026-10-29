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
            "^(mine|craft|hunt|get|gather|collect|obtain)\\s+(\\d{1,4})\\s+([a-z0-9_.-]+:[a-z0-9_./-]+)\\b.*");
    static final String BUILD_HOUSE = "build house";
    static final String PLACE_BED = "place " + TechTree.WHITE_BED + " in house";
    static final String PLACE_TABLE_IN_HOUSE = "place " + TechTree.CRAFTING_TABLE + " in house";
    static final String PLACE_DOOR = "place door in house";
    static final String GO_HOME = "go to house";
    static final String GO_TABLE = "go to crafting table";
    static final String LAY_FLOOR = "lay house floor";
    static final String GATHER_FOOD = "gather food";
    /** The goal when no milestone is left: there is always something to do, and diamonds lead onward. */
    static final String FALLBACK_GOAL = "mine 3 minecraft:diamond";
    private static final Pattern HOUSE_MINE = Pattern.compile("^dig house mine for (\\d{1,4}) ([a-z0-9_.-]+:[a-z0-9_./-]+)$");
    /** Planks kept for filling holes and water in the floor; the floor has 11 cells. */
    static final int FLOOR_PLANKS = 12;
    /** The house needs this many planks, plus a few spare for the bed and a crafting table. */
    static final int HOUSE_PLANKS = HouseDesign.planksNeeded() + 1;
    private static final Pattern PLACE_TABLE = Pattern.compile("^place\\s+minecraft:crafting_table\\b.*");

    private ProgressPlanner() {
    }

    /** Progress that lives in the world rather than the inventory. */
    record Facts(boolean houseBuilt, boolean bedPlaced, boolean tableInHouse, boolean doorPlaced, boolean atHome,
            boolean floorReady, boolean furnaceInHouse, boolean mineFinished, boolean inNether) {
        Facts(boolean houseBuilt, boolean bedPlaced, boolean tableInHouse, boolean doorPlaced, boolean atHome,
                boolean floorReady, boolean furnaceInHouse, boolean mineFinished) {
            this(houseBuilt, bedPlaced, tableInHouse, doorPlaced, atHome, floorReady, furnaceInHouse, mineFinished, false);
        }
        static final Facts NONE = new Facts(false, false, false, false, false, false, false, false);

        Facts(boolean houseBuilt, boolean bedPlaced) {
            this(houseBuilt, bedPlaced, false, false, false, houseBuilt);
        }

        /** Treats a built house's floor as sound unless told otherwise. */
        Facts(boolean houseBuilt, boolean bedPlaced, boolean tableInHouse, boolean doorPlaced, boolean atHome) {
            this(houseBuilt, bedPlaced, tableInHouse, doorPlaced, atHome, houseBuilt);
        }

        Facts(boolean houseBuilt, boolean bedPlaced, boolean tableInHouse, boolean doorPlaced, boolean atHome,
                boolean floorReady) {
            this(houseBuilt, bedPlaced, tableInHouse, doorPlaced, atHome, floorReady, false, false);
        }
    }

    /** HOLD milestones are inventory targets; the others are checked in the world. */
    enum Kind { HOLD, STOCK, HOUSE, FLOOR, TABLE, DOOR, BED, FOOD, FURNACE, NETHER }

    record Milestone(String item, int count, String why, Kind kind) {
        Milestone(String item, int count, String why) {
            this(item, count, why, Kind.HOLD);
        }

        /** The director-style goal text for this milestone. */
        String goal() {
            return switch (kind) {
                case HOUSE -> BUILD_HOUSE;
                case FLOOR -> LAY_FLOOR;
                case TABLE -> PLACE_TABLE_IN_HOUSE;
                case DOOR -> PLACE_DOOR;
                case BED -> PLACE_BED;
                case FOOD -> GATHER_FOOD;
                case FURNACE -> NeedsPlanner.PLACE_FURNACE;
                case NETHER -> NetherProgress.ENTER;
                case HOLD, STOCK -> item.equals("minecraft:water_bucket") ? "fill water bucket"
                        : TechTree.recipeFor(item).isPresent() ? "craft " + count + " " + item
                        : TechTree.huntedFrom(item).isPresent() ? "hunt " + count + " " + item
                        : "mine " + count + " " + TechTree.blockToMine(item);
            };
        }
    }

    /**
     * Early milestones are chosen in code, in order, without asking the director (see isFoundation). It is a checklist:
     * wood, house, crafting table in the house, door, wooden sword, wooden pickaxe, then sheep for wool
     * and a bed. The model kept racing to cobblestone and coal before it had any shelter, and went after
     * sheep bare-handed.
     */
    static final int FOUNDATION_MILESTONES = 10;

    static final List<Milestone> MILESTONES = List.of(
            new Milestone("minecraft:oak_log", 8,
                    "trees are the safest early resource: 8 logs make 32 planks for a crafting table and the first"
                            + " tools; use the log type that grows in this biome (Wood: line), or travel to a biome"
                            + " with trees"),
            new Milestone("minecraft:wooden_pickaxe", 1,
                    "stone, coal and every ore drop nothing without a pickaxe; this unlocks cobblestone"),
            new Milestone("minecraft:wooden_axe", 1, "chops trees faster and is a second weapon"),
            new Milestone("minecraft:stone_pickaxe", 1,
                    "needed to mine iron ore, and mines stone faster; the stone tools also lift the home range"),
            new Milestone("minecraft:stone_axe", 1, "chops wood faster than a wooden axe"),
            new Milestone("minecraft:stone_shovel", 1, "digs dirt, sand and gravel faster"),
            new Milestone("minecraft:stone_sword", 1,
                    "a weapon makes nights and caves survivable"),
            new Milestone("minecraft:cobblestone", 64,
                    "64 stone for the house, furnace and tools; mined once, before building", Kind.STOCK),
            new Milestone("minecraft:oak_log", 16,
                    "16 logs of wood for the house, door, bed and table; gathered once, before building",
                    Kind.STOCK),
            new Milestone("house", 1,
                    "a small shelter near the trees to sleep in and hide from mobs at night; stay close to it"
                            + " until you have stone tools", Kind.HOUSE),
            new Milestone("floor", 1,
                    "fill holes and water in the house floor and doorway with planks so the house can be walked"
                            + " into", Kind.FLOOR),
            new Milestone(TechTree.CRAFTING_TABLE, 1,
                    "a crafting table kept inside the house is where every tool, the door and the bed get"
                            + " crafted; it stays there", Kind.TABLE),
            new Milestone("minecraft:oak_door", 1,
                    "a door closes the doorway so mobs cannot walk in at night", Kind.DOOR),
            new Milestone("minecraft:wooden_sword", 1,
                    "a sword before hunting: sheep die faster and you can defend yourself"),
            new Milestone(TechTree.WHITE_WOOL, 3,
                    "kill white sheep: 3 white wool + 3 planks make a bed, and sheep drop mutton for food"),
            new Milestone(TechTree.WHITE_BED, 1,
                    "a bed inside the house lets you sleep through the night and sets your respawn point", Kind.BED),
            new Milestone("food", FoodPlan.STOCK_WANTED,
                    "health only regenerates when fed; carry food before heading out to mine", Kind.FOOD),
            new Milestone("furnace", 1,
                    "a furnace in the house cooks meat and smelts ore; coal is only useful once there is one",
                    Kind.FURNACE),
            new Milestone("minecraft:torch", 16,
                    "coal for torches and furnace fuel, now that there is a furnace and food", Kind.HOLD),
            new Milestone("minecraft:raw_iron", 12,
                    "iron tools and armor need iron; stockpile raw iron for smelting"));

    static boolean satisfied(Milestone milestone, Map<String, Integer> inventory) {
        return satisfied(milestone, inventory, Facts.NONE);
    }

    static boolean satisfied(Milestone milestone, Map<String, Integer> inventory, Facts facts) {
        switch (milestone.kind()) {
            case HOUSE -> {
                return facts.houseBuilt();
            }
            case FLOOR -> {
                return facts.floorReady();
            }
            case TABLE -> {
                return facts.tableInHouse();
            }
            case DOOR -> {
                return facts.doorPlaced();
            }
            case BED -> {
                return facts.bedPlaced();
            }
            case FOOD -> {
                return FoodPlan.stock(inventory) >= milestone.count();
            }
            case FURNACE -> {
                return facts.furnaceInHouse();
            }
            case NETHER -> {
                return facts.inNether();
            }
            default -> {
            }
        }
        if (milestone.kind() == Kind.STOCK) {
            // One-time stockpiles for building: done when held, or once the house stands and used them up.
            int held = TechTree.matches(TechTree.LOGS, milestone.item())
                    ? TechTree.count(inventory, TechTree.LOGS) : TechTree.held(inventory, milestone.item());
            return facts.houseBuilt() || held >= milestone.count();
        }
        if (TechTree.matches(TechTree.LOGS, milestone.item())) {
            // Logs become planks, so count wood in plank units; a built house or wooden tools mean wood is done.
            if (TechTree.pickaxeTier(inventory).compareTo(TechTree.Tier.WOOD) >= 0) {
                return true;
            }
            int planks = TechTree.count(inventory, TechTree.LOGS) * 4 + TechTree.count(inventory, TechTree.PLANKS);
            return facts.houseBuilt() || planks >= milestone.count() * 4;
        }
        if (milestone.item().equals("minecraft:raw_iron")) {
            return NetherProgress.ironProgress(inventory) >= milestone.count();
        }
        if (milestone.item().equals("minecraft:bucket")) {
            return inventory.getOrDefault("minecraft:bucket", 0) + inventory.getOrDefault("minecraft:water_bucket", 0)
                    + inventory.getOrDefault("minecraft:lava_bucket", 0) >= milestone.count();
        }
        if (milestone.item().equals(TechTree.WHITE_WOOL)) {
            return facts.bedPlaced() || inventory.getOrDefault(TechTree.WHITE_BED, 0) > 0
                    || TechTree.held(inventory, milestone.item()) >= milestone.count();
        }
        TechTree.Tier pickaxe = TechTree.tierOf(milestone.item());
        if (pickaxe != TechTree.Tier.NONE) {
            return TechTree.pickaxeTier(inventory).compareTo(pickaxe) >= 0;
        }
        if (milestone.item().endsWith("_sword")) {
            TechTree.Tier needed = TechTree.tierOf(milestone.item().replace("_sword", "_pickaxe"));
            return inventory.entrySet().stream().anyMatch(entry -> entry.getValue() > 0
                    && entry.getKey().endsWith("_sword")
                    && TechTree.tierOf(entry.getKey().replace("_sword", "_pickaxe")).compareTo(needed) >= 0);
        }
        return TechTree.held(inventory, milestone.item()) >= milestone.count();
    }

    static Optional<Milestone> nextMilestone(Map<String, Integer> inventory) {
        return nextMilestone(inventory, Facts.NONE);
    }

    static Optional<Milestone> nextMilestone(Map<String, Integer> inventory, Facts facts) {
        if (facts.inNether()) return Optional.empty();
        return ordered(inventory).stream().filter(milestone -> !satisfied(milestone, inventory, facts)).findFirst()
                .or(() -> NetherProgress.next(inventory));
    }

    /**
     * The milestone order for this inventory. Coal normally waits until there is food and a furnace;
     * holding raw ore that needs smelting moves it up, right after the stone tools.
     */
    static List<Milestone> ordered(Map<String, Integer> inventory) {
        if (!holdsRawOre(inventory)) {
            return MILESTONES;
        }
        List<Milestone> order = new ArrayList<>(MILESTONES);
        Milestone coal = order.stream().filter(m -> m.item().equals("minecraft:torch")).findFirst().orElseThrow();
        order.remove(coal);
        int food = order.indexOf(order.stream().filter(m -> m.kind() == Kind.FOOD).findFirst().orElseThrow());
        order.add(food, coal);
        return List.copyOf(order);
    }

    /**
     * Drops the trailing "pick the table back up" step when the next milestone is a craft that needs no
     * mining, so back-to-back crafts share one table placement instead of breaking and re-placing it.
     */
    static List<String> keepTableForNext(String goal, List<String> steps, Map<String, Integer> inventory,
            TableAccess table, String preferredLog) {
        String pickup = "mine 1 " + TechTree.CRAFTING_TABLE;
        if (steps.size() < 2 || !steps.get(steps.size() - 1).equalsIgnoreCase(pickup)) {
            return steps;
        }
        boolean seen = false;
        for (Milestone milestone : ordered(inventory)) {
            if (milestone.goal().equalsIgnoreCase(goal.strip())
                    || milestone.item().equalsIgnoreCase(goal.strip())) {
                seen = true;
                continue;
            }
            if (!seen || satisfied(milestone, inventory)) {
                continue;
            }
            if (milestone.kind() != Kind.HOLD || TechTree.recipeFor(milestone.item()).isEmpty()) {
                return steps;
            }
            List<String> next = stepsFor(milestone.item(), milestone.count(), inventory, TableAccess.NEARBY,
                    preferredLog);
            boolean craftsOnly = !next.isEmpty() && next.stream().allMatch(step -> step.startsWith("craft") || step.equalsIgnoreCase(pickup));
            return craftsOnly ? List.copyOf(steps.subList(0, steps.size() - 1)) : steps;
        }
        return steps;
    }

    /**
     * The craftable tool milestones that follow the goal's own in a row (e.g. wooden pickaxe + axe, or all four
     * stone tools), so their materials are gathered together before any crafting. Empty when not applicable.
     */
    static List<Map.Entry<String, Integer>> craftBatch(String goal, Map<String, Integer> inventory) {
        List<Map.Entry<String, Integer>> batch = new ArrayList<>();
        boolean seen = false;
        for (Milestone milestone : ordered(inventory)) {
            boolean craft = milestone.kind() == Kind.HOLD && TechTree.recipeFor(milestone.item()).isPresent();
            if (!seen) {
                seen = milestone.goal().equalsIgnoreCase(goal.strip());
                if (!seen) {
                    continue;
                }
            } else if (!craft) {
                break;
            }
            if (!craft) {
                return List.of();
            }
            // A tier change (wooden to stone) needs mining between crafts, so it starts a new batch.
            if (!batch.isEmpty() && !tier(batch.get(0).getKey()).equals(tier(milestone.item()))) {
                break;
            }
            if (!satisfied(milestone, inventory)) {
                batch.add(Map.entry(milestone.item(), milestone.count()));
            }
        }
        return batch.size() > 1 ? batch : List.of();
    }

    private static String tier(String item) {
        String name = item.substring(item.indexOf(':') + 1);
        return name.substring(0, Math.max(0, name.indexOf('_')));
    }

    static boolean holdsRawOre(Map<String, Integer> inventory) {
        return inventory.getOrDefault("minecraft:raw_iron", 0) > 0 || inventory.getOrDefault("minecraft:raw_gold", 0) > 0
                || inventory.getOrDefault("minecraft:raw_copper", 0) > 0;
    }

    /**
     * Why a coal or torch goal is premature, or empty when it is fine: coal is for smelting and
     * torches, so it waits until there is a furnace and food, unless raw ore is waiting to be smelted.
     */
    static Optional<String> coalTooEarly(String goal, Map<String, Integer> inventory, Facts facts) {
        String text = goal.toLowerCase(Locale.ROOT);
        boolean coal = text.contains("coal") || text.contains("torch");
        if (!coal || holdsRawOre(inventory)) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        if (!facts.furnaceInHouse()) {
            missing.add("a furnace in the house");
        }
        if (FoodPlan.stock(inventory) < FoodPlan.STOCK_WANTED) {
            missing.add("food (" + FoodPlan.stock(inventory) + "/" + FoodPlan.STOCK_WANTED + " hunger points)");
        }
        return missing.isEmpty() ? Optional.empty()
                : Optional.of("coal waits until there is " + String.join(" and ", missing)
                + ", unless raw ore needs smelting");
    }

    /** Every milestone up to coal is run by code, in order; the director takes over after that. */
    static boolean isFoundation(Milestone milestone) {
        return !milestone.item().equals("minecraft:raw_iron");
    }

    private static final Pattern LOOSE_STEP = Pattern.compile(
            "^(mine|craft|get|gather|collect|obtain)\\s+(\\d{1,4})\\s+(?:minecraft:)?([a-z0-9_]+)\\b(.*)");

    /**
     * Cleans model-written steps: bare item ids gain the namespace, and adjacent steps for the same
     * verb and item collapse into one with the largest total. Later repeats remain because an
     * intervening craft may have consumed the first batch. Step quantities are totals to hold, so
     * eight "mine 2 spruce_log" steps mean "hold 2", never 16.
     */
    static List<String> normalizeSteps(List<String> steps) {
        List<String> out = new ArrayList<>();
        for (String raw : steps) {
            String step = raw.strip();
            Matcher matcher = LOOSE_STEP.matcher(step.toLowerCase(Locale.ROOT));
            if (!matcher.matches()) {
                if (out.isEmpty() || !out.get(out.size() - 1).equalsIgnoreCase(step)) {
                    out.add(step);
                }
                continue;
            }
            String verb = matcher.group(1).equals("craft") ? "craft" : "mine";
            int count = Integer.parseInt(matcher.group(2));
            String item = "minecraft:" + matcher.group(3);
            if (!out.isEmpty()) {
                Matcher previous = LOOSE_STEP.matcher(out.get(out.size() - 1));
                if (previous.matches() && previous.group(1).equals(verb) && previous.group(3).equals(item.substring(10))) {
                    int largest = Math.max(count, Integer.parseInt(previous.group(2)));
                    out.set(out.size() - 1, verb + " " + largest + " " + item + previous.group(4));
                    continue;
                }
            }
            out.add(verb + " " + count + " " + item + matcher.group(4));
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

    /** Where the nearest crafting table is, which decides whether a plan places, fetches or crafts one. */
    enum TableAccess {
        /** A placed table close enough to use without walking. */
        NEARBY,
        /** A placed table farther away in loaded terrain; mining it picks it up for reuse. */
        ELSEWHERE,
        NONE,
        /** The house's own table, within reach. It stays in the house and is never picked up. */
        HOME_NEARBY,
        /** The house's own table, out of reach: walk home to craft. */
        HOME_FAR;

        boolean home() {
            return this == HOME_NEARBY || this == HOME_FAR;
        }

        static TableAccess of(boolean nearby) {
            return nearby ? NEARBY : NONE;
        }
    }

    /**
     * Raises a model's mine quantity to the current step's target when both name the same resource.
     * Baritone's quantity is a total to hold, but the model writes how many more it wants.
     */
    static String alignMineQuantity(String command, String step) {
        String[] parts = command.strip().split("\\s+");
        Optional<QuantifiedStep> target = parse(step);
        if (parts.length != 3 || !parts[0].equals("mine") || !parts[1].matches("\\d{1,4}") || target.isEmpty()
                || !target.get().verb().equals("mine")) {
            return command;
        }
        String item = parts[2].contains(":") ? parts[2] : "minecraft:" + parts[2];
        String same = TechTree.groupOf(TechTree.dropOf(TechTree.blockToMine(item)));
        String wanted = TechTree.groupOf(TechTree.dropOf(TechTree.blockToMine(target.get().item())));
        if (!same.equals(wanted) || Integer.parseInt(parts[1]) >= target.get().count()) {
            return command;
        }
        return "mine " + target.get().count() + " " + parts[2];
    }

    /**
     * Decides a step from the inventory alone when the step names a quantity and an item. Empty when
     * the step cannot be judged that way and the model judge should decide.
     */
    static Optional<Boolean> stepSatisfied(String step, Map<String, Integer> inventory, boolean tableNearby) {
        return stepSatisfied(step, inventory, tableNearby, null);
    }

    /** As above; with {@code facts}, house and bed steps are judged from the world too. */
    static Optional<Boolean> stepSatisfied(String step, Map<String, Integer> inventory, boolean tableNearby,
            Facts facts) {
        String text = step.strip();
        if (text.equalsIgnoreCase(NetherProgress.ENTER)) {
            return Optional.of(facts != null && facts.inNether());
        }
        if (text.equalsIgnoreCase("fill water bucket")) {
            return Optional.of(inventory.getOrDefault("minecraft:water_bucket", 0) > 0);
        }
        if (text.equalsIgnoreCase(GO_TABLE)) {
            return Optional.of(tableNearby);
        }
        if (facts != null && text.equalsIgnoreCase(PLACE_TABLE_IN_HOUSE)) {
            return Optional.of(facts.tableInHouse());
        }
        if (facts != null && text.equalsIgnoreCase(PLACE_DOOR)) {
            return Optional.of(facts.doorPlaced());
        }
        if (facts != null && text.equalsIgnoreCase(GO_HOME)) {
            return Optional.of(facts.atHome());
        }
        if (facts != null && text.equalsIgnoreCase(LAY_FLOOR)) {
            return Optional.of(facts.floorReady());
        }
        if (text.equalsIgnoreCase(GATHER_FOOD)) {
            return Optional.of(FoodPlan.stock(inventory) >= FoodPlan.STOCK_WANTED);
        }
        if (facts != null && text.equalsIgnoreCase(NeedsPlanner.PLACE_FURNACE)) {
            return Optional.of(facts.furnaceInHouse());
        }
        if (text.equalsIgnoreCase(NeedsPlanner.SMELT)) {
            // Finished by the smelter itself when the furnace is loaded and emptied.
            return Optional.of(false);
        }
        Matcher mine = HOUSE_MINE.matcher(text.toLowerCase(Locale.ROOT));
        if (mine.matches()) {
            return Optional.of((facts != null && facts.mineFinished())
                    || TechTree.held(inventory, mine.group(2)) >= Integer.parseInt(mine.group(1)));
        }
        if (PLACE_TABLE.matcher(text.toLowerCase(Locale.ROOT)).matches() && !text.endsWith("in house")) {
            return Optional.of(tableNearby);
        }
        if (facts != null && step.strip().equalsIgnoreCase(BUILD_HOUSE)) {
            return Optional.of(facts.houseBuilt());
        }
        if (facts != null && step.strip().equalsIgnoreCase(PLACE_BED)) {
            return Optional.of(facts.bedPlaced());
        }
        return parse(step).map(parsed -> TechTree.held(inventory, parsed.item()) >= parsed.count());
    }

    /**
     * Problems that make a goal impossible right now, or empty when it is achievable. Used to replace
     * director goals like "mine 20 stone" when the player has no pickaxe.
     */
    static Optional<String> goalProblem(String goal, Map<String, Integer> inventory) {
        if (goal.strip().toLowerCase(Locale.ROOT).startsWith("explore")) {
            return Optional.of("exploring is not available; pick a milestone goal, or " + FALLBACK_GOAL
                    + " to find diamonds");
        }
        Optional<QuantifiedStep> parsed = parse(goal);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        String item = parsed.get().item();
        if (parsed.get().verb().equals("craft")) {
            return TechTree.recipeFor(item).isPresent() ? Optional.empty()
                    : Optional.of(item + " cannot be crafted by Actionable; craftable: " + TechTree.craftableItems());
        }
        if (parsed.get().verb().equals("mine") && TechTree.recipeFor(item).isPresent()
                && !TechTree.isReclaimable(item)) {
            return Optional.of(item + " is crafted, not mined");
        }
        String block = TechTree.blockToMine(item);
        TechTree.Tier needed = TechTree.toolFor(block);
        if (needed.compareTo(TechTree.pickaxeTier(inventory)) > 0) {
            return Optional.of(block + " drops nothing without a " + TechTree.pickaxeFor(needed));
        }
        return Optional.empty();
    }

    /** True when a goal would craft another crafting table although one is carried or placed nearby. */
    static boolean duplicateTable(String goal, Map<String, Integer> inventory, TableAccess table) {
        return parse(goal).filter(parsed -> parsed.verb().equals("craft")
                        && parsed.item().equals(TechTree.CRAFTING_TABLE))
                .map(parsed -> inventory.getOrDefault(TechTree.CRAFTING_TABLE, 0) > 0 || table != TableAccess.NONE)
                .orElse(false);
    }

    /**
     * Ordered steps that leave the inventory holding at least {@code count} of {@code item}. Quantities
     * in each step are totals to hold when that step finishes, matching Baritone's mine semantics.
     * Empty when the item cannot be produced from the tech tree.
     */
    static List<String> stepsFor(String item, int count, Map<String, Integer> inventory, boolean tableNearby,
            String preferredLog) {
        return stepsFor(item, count, inventory, TableAccess.of(tableNearby), preferredLog);
    }

    /**
     * As above, but a table placed elsewhere is walked to rather than crafting a new one,
     * and placed tables stay in the world for the next crafting job.
     */
    static List<String> stepsFor(String item, int count, Map<String, Integer> inventory, TableAccess table,
            String preferredLog) {
        return stepsForAll(List.of(Map.entry(item, count)), inventory, table, preferredLog);
    }

    /** One plan for several items: all materials are gathered first, then everything is crafted at one table. */
    static List<String> stepsForAll(List<Map.Entry<String, Integer>> wanted, Map<String, Integer> inventory,
            TableAccess table, String preferredLog) {
        List<Map.Entry<String, Integer>> needs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wanted) {
            // Any wood counts toward a log goal; the plan names the log type found nearby.
            needs.add(Map.entry(TechTree.matches(TechTree.LOGS, entry.getKey()) ? TechTree.LOGS : entry.getKey(),
                    entry.getValue()));
        }
        try {
            Expansion first = new Expansion(inventory, table, preferredLog, List.of());
            needs.forEach(need -> first.need(need.getKey(), need.getValue()));
            // Mining that needs no new tool is hoisted to the front; otherwise held materials get used up
            // by early crafts and the remainder is mined mid-plan, adding steps.
            List<Op> hoisted = first.ops.stream()
                    .filter(op -> op.verb.equals("mine") && op.dependency < 0).toList();
            if (hoisted.isEmpty()) {
                return first.steps(inventory);
            }
            Expansion second = new Expansion(inventory, table, preferredLog, hoisted);
            needs.forEach(need -> second.need(need.getKey(), need.getValue()));
            return second.steps(inventory);
        } catch (IllegalArgumentException unknown) {
            return List.of();
        }
    }

    /** Steps for a goal such as "craft 1 minecraft:wooden_pickaxe" or "mine 8 minecraft:stone". */
    static List<String> stepsForGoal(String goal, Map<String, Integer> inventory, boolean tableNearby,
            String preferredLog) {
        return stepsForGoal(goal, inventory, TableAccess.of(tableNearby), preferredLog);
    }

    static List<String> stepsForGoal(String goal, Map<String, Integer> inventory, TableAccess table,
            String preferredLog) {
        String log = preferredLog == null || preferredLog.isBlank() ? "minecraft:oak_log" : preferredLog;
        if (goal.strip().equalsIgnoreCase("fill water bucket")) return List.of("fill water bucket");
        if (goal.strip().equalsIgnoreCase(BUILD_HOUSE)) {
            List<String> steps = new ArrayList<>(stepsFor(TechTree.planksFor(log), HOUSE_PLANKS, inventory, table, log));
            steps.add(BUILD_HOUSE);
            return List.copyOf(steps);
        }
        if (goal.strip().equalsIgnoreCase(GATHER_FOOD)) {
            List<String> steps = new ArrayList<>(hasWeapon(inventory) ? List.of()
                    : stepsFor("minecraft:wooden_sword", 1, inventory, table, log));
            steps.add(GATHER_FOOD);
            return List.copyOf(steps);
        }
        if (goal.strip().equalsIgnoreCase(NeedsPlanner.PLACE_FURNACE)) {
            List<String> steps = new ArrayList<>(inventory.getOrDefault("minecraft:furnace", 0) > 0 ? List.of()
                    : stepsFor("minecraft:furnace", 1, inventory, table, log));
            steps.add(NeedsPlanner.PLACE_FURNACE);
            return List.copyOf(steps);
        }
        if (goal.strip().equalsIgnoreCase(NeedsPlanner.SMELT) || HOUSE_MINE.matcher(goal.strip()).matches()) {
            return List.of(goal.strip());
        }
        if (goal.strip().equalsIgnoreCase(LAY_FLOOR)) {
            List<String> steps = new ArrayList<>(TechTree.count(inventory, TechTree.PLANKS) >= FLOOR_PLANKS ? List.of()
                    : stepsFor(TechTree.planksFor(log), FLOOR_PLANKS, inventory, table, log));
            steps.add(LAY_FLOOR);
            return List.copyOf(steps);
        }
        if (goal.strip().equalsIgnoreCase(PLACE_TABLE_IN_HOUSE)) {
            List<String> steps = new ArrayList<>();
            if (inventory.getOrDefault(TechTree.CRAFTING_TABLE, 0) == 0) {
                // Reuse a table placed elsewhere; only craft one when there is none.
                steps.addAll(table == TableAccess.NEARBY || table == TableAccess.ELSEWHERE
                        ? List.of("mine 1 " + TechTree.CRAFTING_TABLE)
                        : stepsFor(TechTree.CRAFTING_TABLE, 1, inventory, TableAccess.NONE, log));
            }
            steps.add(PLACE_TABLE_IN_HOUSE);
            return List.copyOf(steps);
        }
        if (goal.strip().equalsIgnoreCase(PLACE_DOOR)) {
            String door = inventory.keySet().stream().filter(TechTree::isDoor).findFirst()
                    .orElse(TechTree.doorFor(TechTree.planksFor(log)));
            List<String> steps = new ArrayList<>(inventory.getOrDefault(door, 0) > 0 ? List.of()
                    : stepsFor(door, 1, inventory, table, log));
            steps.add(PLACE_DOOR);
            return List.copyOf(steps);
        }
        if (goal.strip().equalsIgnoreCase(PLACE_BED)) {
            List<String> steps = new ArrayList<>(inventory.getOrDefault(TechTree.WHITE_BED, 0) > 0 ? List.of()
                    : stepsFor(TechTree.WHITE_BED, 1, inventory, table, log));
            steps.add(PLACE_BED);
            return List.copyOf(steps);
        }
        Optional<QuantifiedStep> quantified = parse(goal);
        if (quantified.isPresent() && quantified.get().verb().equals("hunt") && !hasWeapon(inventory)) {
            // Never hunt bare-handed: make a wooden sword first.
            List<String> steps = new ArrayList<>(stepsFor("minecraft:wooden_sword", 1, inventory, table, log));
            steps.addAll(stepsFor(quantified.get().item(), quantified.get().count(), inventory, table, log));
            return List.copyOf(steps);
        }
        return quantified
                .map(parsed -> stepsFor(parsed.verb().equals("mine") && !parsed.item().equals("minecraft:flint")
                        ? TechTree.dropOf(TechTree.blockToMine(parsed.item()))
                        : parsed.item(), parsed.count(), inventory, table, preferredLog))
                .orElse(List.of());
    }

    static boolean needsHouse(String goal) {
        String text = goal.strip();
        return text.equalsIgnoreCase(PLACE_TABLE_IN_HOUSE) || text.equalsIgnoreCase(PLACE_BED)
                || text.equalsIgnoreCase(PLACE_DOOR) || text.equalsIgnoreCase(LAY_FLOOR)
                || text.equalsIgnoreCase(GO_HOME) || text.equalsIgnoreCase(NeedsPlanner.PLACE_FURNACE)
                || text.equalsIgnoreCase(NeedsPlanner.SMELT) || HOUSE_MINE.matcher(text.toLowerCase(Locale.ROOT)).matches();
    }

    static List<String> stepsForGoal(String goal, Map<String, Integer> inventory, TableAccess table,
            String preferredLog, Facts facts) {
        if (needsHouse(goal) && !facts.houseBuilt()) {
            List<String> steps = new ArrayList<>(stepsForGoal(BUILD_HOUSE, inventory, table, preferredLog));
            steps.addAll(stepsForGoal(goal, inventory, table, preferredLog));
            return List.copyOf(steps);
        }
        return stepsForGoal(goal, inventory, table, preferredLog);
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
        return briefing(inventory, TableAccess.of(tableNearby), preferredLog);
    }

    static String briefing(Map<String, Integer> inventory, TableAccess table, String preferredLog) {
        return briefing(inventory, table, preferredLog, Facts.NONE, "");
    }

    static String briefing(Map<String, Integer> inventory, TableAccess table, String preferredLog, Facts facts,
            String home) {
        boolean tableNearby = table == TableAccess.NEARBY;
        StringBuilder text = new StringBuilder("Progression briefing:");
        if (!home.isBlank()) {
            text.append("\n- ").append(home);
        }
        text.append("\n- house_built=").append(facts.houseBuilt()).append(", bed_placed=").append(facts.bedPlaced());
        text.append("\n- pickaxe_tier=").append(TechTree.pickaxeTier(inventory).name().toLowerCase(Locale.ROOT));
        text.append("\n- cannot_mine_yet=").append(TechTree.cannotMineYet(inventory));
        text.append("\n- crafting_table=").append(tableStatus(inventory, table));
        Optional<Milestone> next = nextMilestone(inventory, facts);
        if (next.isPresent()) {
            Milestone milestone = next.get();
            text.append("\n- next_milestone=").append(milestone.goal())
                    .append(" (why: ").append(milestone.why()).append(')');
            List<String> steps = stepsForGoal(milestone.goal(), inventory, table, preferredLog);
            if (!steps.isEmpty()) {
                text.append("\n- milestone_plan=").append(steps);
            }
        } else {
            text.append("\n- next_milestone=none (early-game milestones done; gather what a plan needs, or mine diamonds)");
        }
        text.append("\n- craftable_now=").append(TechTree.craftableNow(inventory, tableNearby));
        text.append("\n- inventory_uses=").append(inventoryUses(inventory));
        text.append("\n- craftable_catalog=").append(TechTree.craftableItems());
        text.append("\n- night_prep_missing=").append(SafetyAssessment.nightPrepMissing(inventory));
        String progression = progression();
        if (!progression.isEmpty()) {
            text.append("\nGame progression (earliest unfinished stage first; lettered lines are options, branches D and N are either order):\n").append(progression);
        }
        return text.toString();
    }

    static boolean hasWeapon(Map<String, Integer> inventory) {
        return inventory.entrySet().stream().anyMatch(entry -> entry.getValue() > 0 && TechTree.isWeapon(entry.getKey()));
    }

    /** Tells the model where its crafting table is and that it is reused, never crafted twice. */
    static String tableStatus(Map<String, Integer> inventory, TableAccess table) {
        if (table.home()) {
            return "kept inside the house" + (table == TableAccess.HOME_NEARBY ? " (within reach)" : " (go to house to craft)")
                    + "; never pick it up and never craft another";
        }
        if (inventory.getOrDefault(TechTree.CRAFTING_TABLE, 0) > 0) {
            return "in inventory (place it where you craft and leave it standing; do not craft another)";
        }
        return switch (table) {
            case NEARBY -> "placed within reach (reuse it and leave it standing; do not craft another)";
            case ELSEWHERE -> "placed nearby, see nearest_crafting_table (go to crafting table and leave it standing;"
                    + " do not craft another)";
            case NONE -> "none (craft one from 4 planks, then keep reusing it)";
            default -> "kept inside the house";
        };
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
        private final TableAccess table;
        private boolean tableReady;
        private int tableReadyAt = -1;
        private int depth;

        Expansion(Map<String, Integer> inventory, TableAccess table, String preferredLog, List<Op> seeds) {
            this.stock = new HashMap<>(inventory);
            this.preferredLog = preferredLog == null || preferredLog.isBlank() ? "minecraft:oak_log" : preferredLog;
            this.tier = TechTree.pickaxeTier(inventory);
            this.table = table;
            this.tableReady = table == TableAccess.NEARBY || table == TableAccess.HOME_NEARBY;
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
                Optional<String> smeltInput = FurnacePlan.inputFor(item);
                if (smeltInput.isPresent()) {
                    int dependency = need(smeltInput.get(), missing);
                    dependency = Math.max(dependency, need(TechTree.COALS, (missing + 7) / 8));
                    return add("smelt", item, missing, dependency);
                }
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
                if (TechTree.huntedFrom(item).isPresent()) {
                    return add("hunt", item, missing, -1);
                }
                TechTree.Source source = TechTree.sourceFor(item)
                        .orElseThrow(() -> new IllegalArgumentException("no known source for " + item));
                return add("mine", item.equals("minecraft:flint") ? item : source.block(), missing, ensureTier(source.tool()));
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
            if (table == TableAccess.HOME_FAR) {
                // The house table stays put: walk home to use it.
                tableReady = true;
                tableReadyAt = add("goto", "house", 1, -1);
                return tableReadyAt;
            }
            if (table == TableAccess.ELSEWHERE) {
                tableReady = true;
                tableReadyAt = add("goto", "crafting_table", 1, -1);
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
                    case "mine", "hunt" -> {
                        simulated.merge(TechTree.dropOf(op.item), op.amount, Integer::sum);
                        steps.add(op.verb + " " + TechTree.held(simulated, op.item) + " " + op.item);
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
                    case "goto" -> steps.add(op.item.equals("house") ? GO_HOME : GO_TABLE);
                    case "smelt" -> {
                        remove(simulated, FurnacePlan.inputFor(op.item).orElseThrow(), op.amount);
                        remove(simulated, TechTree.COALS, (op.amount + 7) / 8);
                        simulated.merge(op.item, op.amount, Integer::sum);
                        steps.add(NeedsPlanner.SMELT);
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
