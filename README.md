# Actionable version 0.1.2
Readme Date: 10/5/2026

A client-side Fabric mod that plays Minecraft autonomously: a local Gemma model chooses its own goals,
breaks them into steps, and carries them out through Baritone.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5, and Fabric API 0.161.0+26.3
- Java 25 (the mod is compiled for release 25; a Java 21 runtime will refuse to load it)
- Ollama running locally with a Gemma model (by default, `gemma3:4b`)
- Baritone installed as a Fabric client mod, version 1.20.0 or newer, built for Minecraft 26.3

## Installation

1. Run `ollama pull gemma3:4b` and start Ollama (run `ollama` in the command prompt, then exit after
   choosing to use it without an account). Ollama should now show up as a hidden icon on your taskbar.
2. Build with `./gradlew build`.
3. Install the generated JAR from `build/libs` (`actionable-0.1.2-26.3-fabric.jar`) with Fabric API
   (https://fabricmc.com) and the Fabric client build of Baritone (API version from
   https://github.com/cabaletta/baritone/releases/tag/v1.20.0) for Minecraft 26.3.
4. Place all 3 JARs (Fabric API, Baritone API, and Actionable) into your `/mods` folder in your
   Minecraft directory. Launch the game with the Fabric modloader, and make sure the launcher profile
   uses Java 25.

Do not use a Baritone jar unless its Fabric metadata declares Minecraft `~26.3`; matching the jar's
filename or version number alone is not sufficient. Baritone is a required Fabric dependency, so
Fabric Loader will report a missing or too-old version during launch.

## Commands

| Command | Effect |
| --- | --- |
| *(none)* | Autonomy is **on by default**. Join a world and Actionable starts choosing and pursuing its own goals with no input. |
| `/action <task>` | Run one specific task, for example `/action get 4 oak logs`. Replaces whatever is running. When the task finishes, autonomous goal selection resumes. Tasks are capped at 1,000 characters. |
| `/actionauto` | Toggle autonomous goal selection on or off. Turning it off abandons the current task; Actionable then only runs tasks you give it with `/action`. |
| `/actiondebug` | Toggle the on-screen debug panel. |

Type command names in full. A partial name such as `/act` is not a command and Minecraft will answer
`Unknown or incomplete command`, which looks like the mod failing to load but is not.

## Controls

**Ctrl+`** pauses or resumes everything. Pausing invalidates pending plans, clears the recent-action
history, and sends Baritone `#cancel`; the active task resumes when re-enabled.

## How it decides what to do

Planning runs in four layers, each a separate call to the local model:

1. **Director** — picks the next goal from the world state, the goals already achieved, and the goals
   recently attempted. Only runs when no task is active.
2. **Decomposition** — breaks that goal into an ordered plan of at most 8 concrete steps. The plan is
   printed to chat and persists for the whole task.
3. **Step loop** — works one step at a time, re-planning about every 10 seconds (200 ticks). The model
   sees the numbered plan with the current step marked, the last 5 actions and their results, Baritone's
   elapsed time and progress, and the world snapshot. It picks one action per turn, and may return
   `wait` to let a running `mine` or `explore` continue.
4. **Judge** — a small dedicated call that decides whether the current step's objective is already
   satisfied, from the inventory and recent history. When it says yes, the plan advances to the next step.

Two decisions are made in code rather than by the model, because `gemma3:4b` was measured failing both:

- **Survival.** Checked every second. If health is 6 or lower, or a hostile mob is targeting the player
  from within 8 blocks, Actionable interrupts whatever is running and flees 40 blocks directly away from
  the threat. The flee command runs immediately without waiting for a planning round trip, and ends as
  soon as the threat clears.
- **Repeat avoidance.** A chosen goal is reduced to the resource it concerns, so "mine 20 stone" and
  "mine 8 cobblestone" count as the same goal. If the director picks something already achieved, it is
  replaced using a built-in ladder: 16 oak logs, then 24 cobblestone, then 12 coal ore, then 12 iron
  ore, each skipped if the inventory already holds enough. If the ladder is exhausted, Actionable
  explores instead.

Any task still running after 5 minutes (6,000 ticks) is abandoned and a new goal is chosen. Actionable
remembers the last 6 achieved goals and the last 6 attempted goals.

## Debug panel

`/actiondebug` shows:

| Line | Meaning |
| --- | --- |
| `State` | Current phase, plus `[auto]` or `[manual]`. |
| `Task` | The goal being pursued, or `none`. |
| `Achieved` | Goals completed so far this session (up to 6). |
| `Step` | Current step number, total steps, and the step text. |
| `Player xyz` | Your block position. |
| `Baritone cmd` | The Baritone command currently running. |
| `Baritone` | Elapsed time, movement, and distance to a `goto` target. |
| `Baritone feedback` | The most recent `[Baritone]` chat line. |
| `LLM goal` | The model's current subgoal. |
| `Result` | Result of the last action. |
| `Error` | Latest error code and detail. |

### States

| State | Meaning |
| --- | --- |
| `paused` | Ctrl+` pause is active. Nothing runs. |
| `idle` | No task. In `[auto]` the director will pick one shortly; in `[manual]` it waits for `/action`. |
| `choosing goal` | Director call in flight. |
| `decomposing` | Decomposition call in flight. |
| `planning` | Step-loop call in flight. |
| `active` | A task is running and Baritone is working. |

### Error codes

| Code | Meaning |
| --- | --- |
| `OLLAMA_UNREACHABLE` | Nothing is listening at the configured endpoint. |
| `OLLAMA_HTTP_404` | Ollama is up but does not have the requested model. Run `ollama pull <model>` against **that** Ollama. |
| `OLLAMA_HTTP_ERROR` | Any other non-2xx response from Ollama. |
| `PLANNER_REQUEST_FAILED` | The request failed or the reply could not be parsed. |
| `PLAN_NO_VALID_ACTION` | The model returned no action Actionable could validate. |
| `ACTION_TYPE_UNSUPPORTED` | The model asked for an action type that does not exist. |
| `BARITONE_COMMAND_REJECTED` | Baritone refused the command; Actionable re-plans immediately. |
| `BLOCK_PLACE_FAILED_*` | A `place_block` could not be completed: `NO_ITEM`, `MATERIAL_MISSING`, `UNKNOWN_BLOCK`, `NO_SUPPORT`, `OUT_OF_REACH`, `TARGET_OCCUPIED`, `NO_HEADROOM`, `PLAYER_IN_WAY`, `NO_WORLD`. |
| `JUMP_PLACE_FAILED_*` | A `jump_place` could not be completed: `NOT_CURRENT_CELL`, `NOT_GROUNDED`, `JUMP_TIMEOUT`, `TARGET_OCCUPIED`, `NO_HEADROOM`, `WORLD_ENDED`. |
| `GAME_SESSION_ENDED` | The world or connection closed while a plan was in flight. |

`BLOCK_PLACE_DEFERRED_*` is not an error. It appears in `Result` when a placement needed one more tick
(an obstruction was cleared first) and is retried on the next tick rather than after the usual delay.

## Actions and safety

The model's Baritone commands are restricted to an allowlist — `mine`, `goto`, `build`, `explore`,
`find`, `pickup`, `farm`, `cancel`, `come`, `follow` — and each is argument-checked before it is sent,
so the model cannot run an arbitrary chat command. `mine` takes `<quantity> <blockID>` (for example
`mine 32 minecraft:diamond_ore`; no coordinates), `goto` takes exactly three integer coordinates (no
block IDs), and `explore` takes no arguments. Commands that fail validation are discarded as
`PLAN_NO_VALID_ACTION`. Small models often return a command as separate fields rather than one string,
so Actionable reconstructs the command from either shape before validating it.

Besides Baritone commands the model can return `wait`, `lookup` (registered block/item IDs and recipes
known to the player's recipe book), `place_block`, and `jump_place`. Baritone's own `build` command
loads an existing `.schematic`, `.schem`, or `.litematic` file; it does not place individual blocks.
Actionable's guarded single-block placement moves an available inventory stack to the hotbar, clears a
replaceable plant at the target, aims at an adjacent sturdy face, and places the block if the target is
in reach. `jump_place` jumps up and places the block beneath the player, for the player's own feet cell.
Routine status goes to the actionbar; plans go to chat.

## What the model is told

Coordinates follow Minecraft convention: **+X east, +Z south, +Y up** (yaw 0 faces south, yaw 90 west,
yaw -90 east). Actionable sends a compact snapshot of the player's exact position, yaw/pitch, looked-at
block, current biome, a sample of nearby biomes in already-loaded chunks with distance and direction
relative to facing, nearby tree logs and ores, local placement surfaces, hotbar and inventory, health,
and nearby hostile mobs — plus the current plan, the last 5 actions and results, and recent Baritone
feedback. Keep the endpoint local or use a service you trust.

## Configuration

| Property | Default |
| --- | --- |
| `-Dactionable.ollama.url` | `http://localhost:11434/api/chat` |
| `-Dactionable.ollama.model` | `gemma3:4b` |

Set these as JVM arguments in your launcher profile.

If you use a *thinking* model such as `qwen3.5:9b`, be aware it will spend thousands of tokens reasoning
before answering — measured at over two minutes per planning call, against a 10-second planning cadence.
Such a model needs `"think": false` added to the request body in `ActionPlanner` to be usable here.

### If Ollama seems reachable but the model is missing

You can have more than one Ollama installed — commonly a native Windows one and a second inside WSL.
Both answer on `localhost:11434`, but they are separate servers with separate model stores, and
Minecraft reaches only the one on its own side. `OLLAMA_HTTP_404` with Ollama apparently running is
this. Check which models the one Minecraft talks to actually has:

```
curl http://localhost:11434/api/tags
```

Ollama is best run as a separate local service rather than embedded in the mod JAR: model files and
inference runtimes are large, and GPU support depends on native platform-specific libraries. A 4B model
such as `gemma3:4b` is a practical starting point on consumer hardware.

## Limitations

- **No crafting.** Actionable cannot craft tools or items, so the director and decomposer are instructed
  never to choose goals or steps that need crafting. This is the main ceiling on what it can accomplish.
- **No schematic generation.** Automated building beyond individual block placements requires a
  compatible schematic already saved in Baritone's `schematics` directory.
- **No combat action.** The only response to a threat is to flee.
- **Decomposition quality is limited by the model.** On a vague goal, `gemma3:4b` produces redundant
  steps; concrete goals such as "get 4 oak logs" decompose cleanly.
- The `lookup` tool uses local registries and player-known recipes. It has no database of biome-specific
  loot or ore-generation heights, and the model is instructed not to invent them.

## Contributors
Kai Battistoni, ChatGPT, Claude Code
