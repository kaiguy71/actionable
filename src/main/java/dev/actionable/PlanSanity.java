package dev.actionable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans model-written goals and plan steps without judging which game features they use: crafting,
 * smelting, trading and anything else the game offers are all allowed through. It only collapses
 * a step repeated back to back and caps absurd mine quantities.
 */
final class PlanSanity {
    /** One mine command never needs more than a stack; larger numbers are model optimism. */
    static final int MAX_MINE_QUANTITY = 64;

    private static final Pattern MINE_QUANTITY = Pattern.compile("\\b(mine|get|collect|gather)\\s+(\\d{1,5})\\b");

    private PlanSanity() {
    }

    /** True when the text says something at all. Feature words are never grounds for rejection. */
    static boolean usable(String text) {
        return text != null && !text.isBlank();
    }

    /** Caps "mine 500 stone" at one stack; leaves text without a quantity untouched. */
    static String clampQuantity(String text) {
        Matcher matcher = MINE_QUANTITY.matcher(text);
        StringBuilder clamped = new StringBuilder();
        while (matcher.find()) {
            int quantity = Integer.parseInt(matcher.group(2));
            String replacement = matcher.group(1) + ' ' + Math.min(quantity, MAX_MINE_QUANTITY);
            matcher.appendReplacement(clamped, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(clamped);
        return clamped.toString();
    }

    /**
     * Drops blank steps and a step repeated immediately after itself (ignoring case and spacing),
     * clamps quantities, and keeps order. The same step later in the plan is kept: gathering logs,
     * crafting them, then gathering logs again is legitimate. An empty result means nothing was left.
     */
    static List<String> sanitizeSteps(List<String> steps) {
        List<String> kept = new ArrayList<>();
        String previous = "";
        for (String step : steps) {
            if (!usable(step)) {
                continue;
            }
            String clamped = clampQuantity(step.strip());
            String key = clamped.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (key.equals(previous)) {
                continue;
            }
            previous = key;
            kept.add(clamped);
        }
        return kept;
    }
}
