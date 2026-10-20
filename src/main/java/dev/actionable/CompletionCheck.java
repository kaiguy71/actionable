package dev.actionable;

import java.util.Map;

/** Inventory and world evidence take precedence over a model completion claim. */
final class CompletionCheck {
    private CompletionCheck() { }

    static StepCheck.Verdict verdict(String objective, Map<String, Integer> inventory,
            boolean tableNearby, ProgressPlanner.Facts facts) {
        return ProgressPlanner.stepSatisfied(objective, inventory, tableNearby, facts)
                .map(done -> done ? StepCheck.Verdict.DONE : StepCheck.Verdict.NOT_DONE)
                .orElseGet(() -> StepCheck.check(objective, inventory));
    }

    static boolean mayComplete(boolean wholeTask, int currentStep, int stepCount,
            StepCheck.Verdict step, StepCheck.Verdict task) {
        return step != StepCheck.Verdict.NOT_DONE && (!wholeTask
                || (currentStep >= stepCount - 1 && task != StepCheck.Verdict.NOT_DONE));
    }
}
