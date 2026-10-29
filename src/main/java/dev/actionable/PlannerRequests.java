package dev.actionable;

/** Client-thread ownership of async requests. Interruptions release every busy flag atomically. */
final class PlannerRequests {
    enum Phase { DIRECTOR, DECOMPOSITION, JUDGE, ACTION }
    long generation;
    boolean planning;
    boolean choosingGoal;
    boolean decomposing;
    boolean judging;

    void invalidate() {
        generation++;
        planning = choosingGoal = decomposing = judging = false;
    }

    long begin(Phase phase) {
        invalidate();
        switch (phase) {
            case DIRECTOR -> choosingGoal = true;
            case DECOMPOSITION -> decomposing = true;
            case JUDGE -> judging = true;
            case ACTION -> planning = true;
        }
        return generation;
    }

    boolean complete(long token) {
        if (token != generation) return false;
        planning = choosingGoal = decomposing = judging = false;
        return true;
    }

    boolean busy() {
        return planning || choosingGoal || decomposing || judging;
    }
}
