# Actionable

Read and follow `AGENTS.md` in this directory; it is the source of truth for build commands, design
rules, and the pre-commit checklist. The rule that matters most is repeated here so it cannot be missed:

## Double check before ever committing

Do not commit, push, or open a pull request unless the user asked for it in this session. When asked,
first do all of the following and report the results, and stop if any of them fails:

1. `./gradlew build` passes with zero test failures, run after your last edit.
2. Read the full `git status` and `git diff` (staged, unstaged, and untracked files); everything in
   it is intended, and nothing else is.
3. Stage files by name; never include `build/`, `.gradle/`, logs, jars, or credentials.
4. Compare the diff with the request line by line (no scope creep, no debug leftovers).
5. Check anything that depends on the outside world (the running jar, other branches,
   `logs/latest.log`) instead of assuming it. Never remove or block a game feature on an assumption.
6. README and tests match the change.
7. After fixing anything found, repeat step 1 and re-read the diff.
8. Say plainly what was verified and what was not run in-game.

## Quick facts

- Java 25 is required (`./gradlew build`). Pure logic lives in classes without Minecraft imports so it
  can be unit tested (`ActionLoopGuard`, `PlanSanity`, `StepCheck`, `EquipRules`).
- Survival, repeat avoidance, and step completion are decided in code, not by the model.
- Never filter goals or steps because of the game feature they use; only dedupe and cap quantities.
