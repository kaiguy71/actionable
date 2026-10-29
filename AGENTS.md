# Actionable: instructions for AI agents

Actionable is a client-side Fabric mod (Minecraft 26.3, Java 25) that plays Minecraft autonomously by
asking a model for goals and steps and carrying them out through Baritone. `README.md` documents the
behavior; keep it in sync with any change.

## Build and test

- Needs a Java 25 JDK. Run `./gradlew build`; it compiles, runs the JUnit tests in `src/test`, and
  produces the jar in `build/libs`.
- Keep logic that does not need Minecraft types in plain classes (`ActionLoopGuard`, `PlanSanity`,
  `StepCheck`, `EquipRules`) so it can be unit tested. Add or update tests with every behavior change.

## Do not commit without a double check

Never run `git commit`, `git push`, or open a pull request unless the user asked for it in this
session. When they do, complete **every** item below first, and report the results. If any item
fails or cannot be done, say so and stop instead of committing.

1. **Build and tests.** Run `./gradlew build` from a clean state of your changes and confirm
   `BUILD SUCCESSFUL` with zero test failures. Do not rely on an earlier run if you edited anything
   since.
2. **Read the whole diff.** Run `git status` and read the full `git diff` (staged and unstaged) plus
   every untracked file. Confirm each change is intended and nothing is missing.
3. **Check the file list.** Stage files by name. Never commit `build/`, `.gradle/`, logs, jars,
   credentials, or editor files.
4. **Re-check against the request.** Compare the diff with what the user asked for, line by line.
   Look for scope creep, leftover debug code, and assumptions that were never verified.
5. **Verify assumptions against reality.** If behavior depends on something outside this tree (the
   jar actually running in the game, another branch, `logs/latest.log`, Baritone's real behavior),
   look at it. An earlier filter here rejected crafting because this tree had no crafting, while the
   running build did. Do not block or remove game features on an assumption.
6. **Docs and tests.** `README.md` matches the behavior, and new logic has tests.
7. **Second pass.** After fixing anything found in steps 1 to 6, repeat step 1 and re-read the diff.
8. **Honest report.** State what was verified (build, tests) and what was not (in-game behavior).
   Never describe untested game behavior as working.

Do not skip hooks or force-push. Do not amend or rewrite commits that already exist unless asked.

## Design rules

- Anything the model was measured to get wrong (survival, repeat avoidance, step completion) is
  decided in code, not in a prompt.
- Do not filter, reject, or rewrite goals or plan steps because of which game feature they use
  (crafting, smelting, trading, and so on). Only cap or dedupe.
- Every model-chosen action is validated in code before it runs; never pass model text straight to
  chat or to Baritone.
