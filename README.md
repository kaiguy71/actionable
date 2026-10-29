# Actionable version 0.2
Readme Date: 10/20/2026

Actionable is a client-side Fabric mod that plays Minecraft autonomously. A local Ollama model or
Claude Code chooses goals and carries them out through Baritone.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5, and Fabric API 0.161.0+26.3
- Java 25 (Java 21 cannot run the mod)
- Baritone 1.20.0 or newer, built for Minecraft 26.3
- Ollama with `gemma3:4b` (default), or Claude Code installed and signed in

## Installation

1. For the default Ollama setup, run `ollama pull gemma3:4b` and start Ollama.
2. Build the mod with `./gradlew build`. This also runs the unit tests; use `./gradlew test` to run
   only the tests.
3. Install the generated JAR from `build/libs` in your Fabric profile's `mods` folder, together
   with Fabric API and Baritone's Fabric client JAR.
4. Launch with the Fabric modloader and Java 25.

Use a Baritone JAR whose Fabric metadata declares Minecraft `~26.3`; a matching filename or version
number alone is not enough. Baritone is required and Fabric Loader reports a missing or incompatible
version during launch.

## Getting started

Autonomy is off by default and resets to off on every disconnect or game exit. Use `/actionauto` to
toggle autonomous goal selection, or give Actionable a single task with `/action <task>`, for example
`/action get 4 oak logs`. Each new task starts a fresh model session and tasks are limited to 1,000
characters.

| Command | Effect |
| --- | --- |
| `/action <task>` | Run one task; autonomous goal selection resumes afterward if enabled. |
| `/actionauto` | Toggle autonomous goal selection. Turning it off abandons the current task. |
| `/actiondebug` | Toggle the on-screen debug panel. |
| `/actiondebug capture` | Save recent model/mod interaction context under `logs/actionable/`. |

Enter command names in full; partial names such as `/act` are not accepted.

**Ctrl+`** pauses or resumes Actionable. Pausing invalidates pending plans and cancels Baritone's
current command; the active task resumes when Actionable is enabled again.

## Documentation

- [Behavior and progression](docs/behavior-and-progression.md) — planning, milestones, early game,
  survival decisions, and autonomous behavior
- [Actions and safety](docs/actions-and-safety.md) — supported model actions, Baritone command
  validation, crafting, menus, placement, and combat
- [Gemma tactical agent](docs/gemma-tactical-agent.md) — tactical combat behavior, configuration,
  and diagnostics
- [Configuration](docs/configuration.md) — Ollama and Claude Code providers and JVM properties
- [Debugging and troubleshooting](docs/debugging.md) — logs, debug panel, states, error codes, and
  common Ollama issues
- [Model context and privacy](docs/model-context.md) — information sent to the model
- [Limitations](docs/limitations.md) — current feature constraints

## Contributors
Kai Battistoni, Chayse Collins, Abram Eastham, Ethan Claybourn, Joey Ruffing, with help from ChatGPT and Claude Code

### Side-by-side model testing

Use separate game directories for Claude and Gemma, each with its own saves, options and logs.
Set `-Dactionable.background=true` in both profiles to allow melee defense while the window is
unfocused. Set `pauseOnLostFocus:false`, `fullscreen:false`, and `inactivityFpsLimit:"minimized"` in each
profile's `options.txt` while Minecraft is closed. Keep both windows visible; minimizing can still
throttle rendering. Open menus and explicit game pauses block melee attacks; Ctrl+` still disables
Actionable. Background mode is opt-in and does not change target, reach, cooldown or health checks.

For a comparison, create separate worlds with the same seed and game settings. Never share a live
world directory. Both clients keep independent action histories; the Gemma client uses the Windows
Ollama server and the Claude client uses the Claude CLI. Watch each profile's `logs/latest.log` for
`model_request`, `model_response_body`, and `model_failure`, and verify the provider in each log.
