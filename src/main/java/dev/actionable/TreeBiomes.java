package dev.actionable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Which logs grow naturally in each overworld biome, so the model can pick where to get wood. */
final class TreeBiomes {
    private static final String NONE = "";
    private static final Map<String, String> LOGS = new LinkedHashMap<>();
    private static final Pattern NEARBY = Pattern.compile("(minecraft:[a-z_]+)\\(distance=(\\d+)blocks");

    static {
        put("plains", "oak (sparse, scattered)");
        put("sunflower_plains", "oak (sparse, scattered)");
        put("meadow", "oak (rare), birch (rare)");
        put("forest", "oak, birch");
        put("flower_forest", "oak, birch");
        put("birch_forest", "birch");
        put("old_growth_birch_forest", "birch");
        put("dark_forest", "dark_oak, oak");
        put("pale_garden", "pale_oak");
        put("taiga", "spruce");
        put("snowy_taiga", "spruce");
        put("old_growth_pine_taiga", "spruce");
        put("old_growth_spruce_taiga", "spruce");
        put("grove", "spruce");
        put("snowy_slopes", NONE);
        put("windswept_forest", "oak, spruce");
        put("windswept_hills", "oak, spruce (sparse)");
        put("windswept_gravelly_hills", NONE);
        put("jungle", "jungle, oak");
        put("sparse_jungle", "jungle, oak");
        put("bamboo_jungle", "jungle");
        put("savanna", "acacia, oak (rare)");
        put("savanna_plateau", "acacia");
        put("windswept_savanna", "acacia");
        put("swamp", "oak (with vines)");
        put("mangrove_swamp", "mangrove");
        put("cherry_grove", "cherry");
        put("river", "none itself; trees grow on its banks");
        put("beach", NONE);
        put("snowy_beach", NONE);
        put("stony_shore", NONE);
        put("desert", NONE);
        put("badlands", NONE);
        put("eroded_badlands", NONE);
        put("wooded_badlands", "oak (sparse)");
        put("ice_spikes", NONE);
        put("snowy_plains", "spruce (very rare)");
        put("frozen_river", NONE);
        put("mushroom_fields", NONE);
        put("stony_peaks", NONE);
        put("jagged_peaks", NONE);
        put("frozen_peaks", NONE);
    }

    private TreeBiomes() {
    }

    private static void put(String biome, String logs) {
        LOGS.put(biome, logs);
    }

    static boolean hasTrees(String biomeId) {
        String logs = logsFor(biomeId);
        return logs != null && !logs.isEmpty() && !logs.startsWith("none");
    }

    /** The first log that grows in the biome as an item id, if any. */
    static java.util.Optional<String> primaryLog(String biomeId) {
        String logs = logsFor(biomeId);
        if (logs == null || !hasTrees(biomeId)) {
            return java.util.Optional.empty();
        }
        Matcher log = Pattern.compile("([a-z_]+_log)").matcher(logs);
        return log.find() ? java.util.Optional.of("minecraft:" + log.group(1)) : java.util.Optional.empty();
    }

    /** Logs that grow in the biome; null when the biome is unknown, empty when it has no trees. */
    static String logsFor(String biomeId) {
        String key = biomeId == null ? "" : biomeId.toLowerCase(Locale.ROOT).replaceFirst("^[a-z0-9_.-]+:", "");
        if (key.contains("ocean") || key.equals("deep_dark") || key.equals("lush_caves")
                || key.equals("dripstone_caves")) {
            return NONE;
        }
        return LOGS.get(key);
    }

    static String name(String biomeId) {
        return biomeId == null ? "" : biomeId.replaceFirst("^[a-z0-9_.-]+:", "");
    }

    /** The whole table, for a lookup. */
    static String table() {
        StringBuilder text = new StringBuilder("Logs by overworld biome:");
        for (Map.Entry<String, String> entry : LOGS.entrySet()) {
            text.append("\n- ").append(entry.getKey()).append(": ")
                    .append(entry.getValue().isEmpty() ? "no trees" : entry.getValue());
        }
        return text.append("\n- any ocean / deep_ocean / frozen_ocean: no trees (islands may have some)")
                .append("\nNether stems (crimson, warped) are not logs. In a biome without trees, travel to a")
                .append(" neighbouring biome that has them.").toString();
    }

    /**
     * One briefing line: the trees here, and where to go when this biome has none. {@code nearbyBiomes}
     * is the synopsis list "[minecraft:forest(distance=40blocks,vector=..,angle_from_facing=..deg), ...]".
     */
    static String advice(String currentBiome, String nearbyBiomes) {
        String logs = logsFor(currentBiome);
        String here = name(currentBiome);
        if (logs == null) {
            return "wood_here=unknown biome " + here + "; read nearby_resources for log types actually in reach"
                    + " and trust it over this table";
        }
        if (hasTrees(currentBiome)) {
            return "wood_here=" + here + " grows " + logs + "; nearby_resources lists the logs actually in reach."
                    + " If no trees are in reach, travel to a wooded biome (forest, birch_forest, taiga, jungle,"
                    + " dark_forest); the home range does not apply before the house exists";
        }
        StringBuilder text = new StringBuilder("wood_here=NONE: ").append(here)
                .append(" has no trees. Use goto/travel toward a biome with trees");
        Matcher matcher = NEARBY.matcher(nearbyBiomes == null ? "" : nearbyBiomes);
        StringBuilder options = new StringBuilder();
        while (matcher.find()) {
            String biome = matcher.group(1);
            if (hasTrees(biome)) {
                options.append(options.length() == 0 ? "" : "; ").append(name(biome)).append(" (")
                        .append(logsFor(biome)).append(") ").append(matcher.group(2)).append(" blocks away");
            }
        }
        if (options.length() > 0) {
            text.append(": loaded nearby: ").append(options)
                    .append(" (vectors are in nearby_loaded_biomes_sampled)");
        } else {
            text.append("; none is loaded nearby, so use travel in a direction (e.g. toward plains, forest,"
                    + " taiga or savanna); deserts and oceans are the worst places to look");
        }
        return text.toString();
    }
}
