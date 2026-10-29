package dev.actionable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides in code whether a plan step such as "mine 4 minecraft:oak_log" is already satisfied by
 * the inventory, so no model round trip is needed. Steps it cannot read come back as UNKNOWN and
 * are left to the planner, which can still return step_complete itself.
 */
final class StepCheck {
    enum Verdict { DONE, NOT_DONE, UNKNOWN }

    private static final Pattern QUANTITY_STEP = Pattern.compile(
            "\\b(?:mine|get|collect|gather|pickup|obtain|chop|harvest)\\s+(?:at least\\s+|about\\s+)?(\\d{1,4})\\s+"
                    + "(?:more\\s+|of\\s+|the\\s+)*([a-z0-9_]+:)?([a-z0-9_]+)");
    /** Block id path -> item(s) that mining it puts in the inventory. */
    private static final Map<String, List<String>> DROPS = Map.ofEntries(
            Map.entry("stone", List.of("cobblestone", "stone")),
            Map.entry("cobblestone", List.of("cobblestone")),
            Map.entry("grass_block", List.of("dirt")),
            Map.entry("coal_ore", List.of("coal")),
            Map.entry("deepslate_coal_ore", List.of("coal")),
            Map.entry("iron_ore", List.of("raw_iron")),
            Map.entry("deepslate_iron_ore", List.of("raw_iron")),
            Map.entry("copper_ore", List.of("raw_copper")),
            Map.entry("deepslate_copper_ore", List.of("raw_copper")),
            Map.entry("gold_ore", List.of("raw_gold")),
            Map.entry("deepslate_gold_ore", List.of("raw_gold")),
            Map.entry("diamond_ore", List.of("diamond")),
            Map.entry("deepslate_diamond_ore", List.of("diamond")),
            Map.entry("emerald_ore", List.of("emerald")),
            Map.entry("deepslate_emerald_ore", List.of("emerald")),
            Map.entry("lapis_ore", List.of("lapis_lazuli")),
            Map.entry("deepslate_lapis_ore", List.of("lapis_lazuli")),
            Map.entry("redstone_ore", List.of("redstone")),
            Map.entry("deepslate_redstone_ore", List.of("redstone")));

    private StepCheck() {
    }

    /** {@code inventory} maps item ids such as {@code minecraft:oak_log} to total counts. */
    static Verdict check(String step, Map<String, Integer> inventory) {
        Matcher matcher = QUANTITY_STEP.matcher(step.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            return Verdict.UNKNOWN;
        }
        int wanted = Integer.parseInt(matcher.group(1));
        String path = matcher.group(3);
        List<String> items = DROPS.getOrDefault(path, List.of(path));
        int have = 0;
        for (String item : items) {
            have += inventory.getOrDefault("minecraft:" + item, 0);
        }
        // A plural such as "oak_logs" is a plain English slip, not a different item.
        if (have == 0 && path.endsWith("s") && !DROPS.containsKey(path)) {
            have = inventory.getOrDefault("minecraft:" + path.substring(0, path.length() - 1), 0);
        }
        if (wanted <= 0) {
            return Verdict.UNKNOWN;
        }
        return have >= wanted ? Verdict.DONE : Verdict.NOT_DONE;
    }
}
