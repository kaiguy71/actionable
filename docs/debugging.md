# Debugging and troubleshooting

## Logs

Actionable writes to Minecraft's `logs/latest.log` in the profile folder. In CurseForge, choose the
profile's **⋯ → Open Folder**, then open `logs/latest.log`. Search for `[Actionable]`; older logs are
in Minecraft's archived log files.

Logs include provider selection, task and step progress, selected actions and results, Baritone
feedback, combat start/end and requested hits, plus model request IDs, planning stage, model,
duration, and failures. Full model prompts and world snapshots are not logged. Combat cooldown checks
are not logged every tick. Restart Minecraft after installing an updated JAR to enable its logging.

Use `/actiondebug` to toggle the on-screen panel. `/actiondebug capture` saves the past 400
interactions (state, system prompts, requests and responses, and actions) to
`logs/actionable/<timestamp>-contextOutput.md`, or as a ZIP if larger than 2 MB. Capture does not
enable debug mode.

## Debug panel

| Line | Meaning |
| --- | --- |
| `State` | Current phase, plus `[auto]` or `[manual]`. |
| `Task` | Current goal, or `none`. |
| `Achieved` | Goals completed this session (up to six). |
| `Step` | Current step number, total steps, and step text. |
| `Player xyz` | Player's block position. |
| `Baritone cmd` | Current Baritone command. |
| `Baritone` | Elapsed time, movement, and distance to a `goto` target. |
| `Baritone feedback` | Most recent `[Baritone]` chat line. |
| `LLM goal` | Model's current subgoal. |
| `Why` | Director's reason for the goal, or the code-selected milestone reason. |
| `Safety` | `safe`, `caution`, or `danger`, with the first reason. |
| `Result` | Last action result. |
| `Error` | Latest error code and detail. |

## States

| State | Meaning |
| --- | --- |
| `paused` | Ctrl+` pause is active; nothing runs. |
| `idle` | No task. In `[auto]`, the director will choose one; in `[manual]`, Actionable waits for `/action`. |
| `choosing goal` | Director call in flight. |
| `decomposing` | Decomposition call in flight. |
| `planning` | Step-loop call in flight. |
| `active` | A task is running and Baritone is working. |

## Error codes

| Code | Meaning |
| --- | --- |
| `OLLAMA_UNREACHABLE` | Nothing is listening at the configured endpoint. |
| `OLLAMA_HTTP_404` | Ollama is running but lacks the requested model. Run `ollama pull <model>` against the Ollama instance Minecraft uses. |
| `OLLAMA_HTTP_ERROR` | Another non-2xx response from Ollama. |
| `PLANNER_REQUEST_FAILED` | The request failed or the reply could not be parsed. |
| `CLAUDE_REQUEST_FAILED` | Claude could not start, exited with an error, or returned an error. Check the executable path, login, and model access. |
| `CLAUDE_TIMEOUT` | Claude did not finish within 180 seconds; its process was stopped. |
| `CLAUDE_INVALID_JSON` | Claude returned an invalid response object. Check the request stage and `logs/latest.log`. |
| `PLAN_NO_VALID_ACTION` | The model returned no action Actionable could validate. |
| `ACTION_TYPE_UNSUPPORTED` | The model requested an unknown action type. |
| `BARITONE_COMMAND_REJECTED` | Baritone refused the command; Actionable replans immediately. |
| `MINE_FAILED_NO_TOOL` | The block drops nothing without a tool the player lacks (for example, stone without a pickaxe). |
| `MINE_FAILED_UNSAFE_SITE` | Every loaded deposit nearby is dark or deep underground, and the player lacks cave gear. |
| `MINE_FAILED_CRAFTED_ITEM` | The model tried to mine a crafted item, such as planks. |
| `CRAFT_FAILED_*` | Crafting failed: `UNSUPPORTED`, `MISSING_INGREDIENTS`, `NO_TABLE`, `TABLE_OUT_OF_REACH`, `TABLE_DID_NOT_OPEN`, `NO_RESULT`, `OTHER_SCREEN_OPEN`, or `NO_WORLD`. |
| `BLOCK_PLACE_FAILED_*` | Placement failed: `NO_ITEM`, `MATERIAL_MISSING`, `UNKNOWN_BLOCK`, `NO_SUPPORT`, `OUT_OF_REACH`, `TARGET_OCCUPIED`, `NO_HEADROOM`, `PLAYER_IN_WAY`, or `NO_WORLD`. |
| `JUMP_PLACE_FAILED_*` | Jump placement failed: `NOT_GROUNDED`, `JUMP_TIMEOUT`, `TARGET_OCCUPIED`, `NO_HEADROOM`, or `WORLD_ENDED`. |
| `GAME_SESSION_ENDED` | The world or connection closed while a plan was in flight. |

`BLOCK_PLACE_DEFERRED_*` is not an error. It appears in `Result` when placement needs another tick,
such as when an obstruction is cleared first; the action is retried on the next tick.

## Ollama model not found

There may be multiple Ollama installations, such as one on Windows and one in WSL. Both may answer
on `localhost:11434`, but they have separate model stores; Minecraft can only use the Ollama
instance on its side. For `OLLAMA_HTTP_404`, check which models that instance has:

```sh
curl http://localhost:11434/api/tags
```
