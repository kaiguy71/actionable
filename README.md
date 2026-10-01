# Actionable version 0.1.2
Readme Date: 9/30/2026

A client-side Fabric mod framework for turning natural-language tasks into local Gemma plans and Baritone actions.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5, and Fabric API 0.161.0+26.3
- Java 25
- Ollama running locally with a Gemma model (by default, `gemma3:4b`)
- Baritone installed as a Fabric client mod, version 1.20.0 or newer, built for Minecraft 26.3

Run `ollama pull gemma3:4b` and start Ollama, then build with `./gradlew build`. Install the generated JAR from `build/libs` with Fabric API and the Fabric client build of Baritone for Minecraft 26.3 (the upstream `26.3` branch; mod ID `baritone`, currently version `1.20.0`). Do not use a Baritone jar unless its Fabric metadata declares Minecraft `~26.3`; matching the jar's filename or version number alone is not sufficient. Baritone is a required Fabric dependency, so Fabric Loader will report a missing or too-old version during launch.

In game, use `/action <task>`, for example `/action Build a 30x30 house made out of light-colored stone blocks`. Coordinates follow Minecraft convention: **+X east, +Z south, +Y up** (yaw 0 faces south, yaw 90 west, yaw -90 east). Actionable sends a compact snapshot of the player's exact position, yaw/pitch, looked-at block, current biome, a sample of nearby biomes in already-loaded chunks with distance/direction relative to facing, nearby tree logs/ores, local placement surfaces, hotbar/inventory, health, and nearby hostile mobs to Ollama. The model reports a current subgoal and chooses one action at a time; it receives Baritone command elapsed time, movement/progress, and recent Baritone feedback. It may choose to wait while a command such as `mine` or `explore` continues, or replace that command if circumstances require it. Navigation and gathering use Baritone commands. Baritone's `build` command loads an existing `.schematic`, `.schem`, or `.litematic` file; it does not place individual blocks. Actionable can issue guarded single-block placement requests: it can move an available inventory stack to the hotbar, clear a replaceable plant at the target, aim at an adjacent sturdy face, and place the block if the target is in reach. For the player's current feet cell, `jump_place` jumps up and places the block beneath the player after clearing replaceable obstructions. Routine Actionable status uses the actionbar.

Press **Ctrl+`** to pause or resume planning. Pausing invalidates pending plans and sends Baritone `#cancel`; the active task resumes when re-enabled. Use `/actiondebug` to toggle an on-screen debug panel showing your coordinates, planning state, last Baritone command and feedback, LLM subgoal, last action result, and the latest mod/Baritone error code. The LLM can return a local `lookup` action for registered block/item IDs and recipes known to the player's recipe book. Baritone commands are validated: `mine` accepts a block ID and optional quantity (no coordinates), `goto` accepts exactly three integer coordinates (no block IDs), and `explore` accepts no arguments. Set `-Dactionable.ollama.url=http://localhost:11434/api/chat` or `-Dactionable.ollama.model=gemma3:4b` to override the Ollama endpoint or model.

The prompt, compact world snapshot, previous action, and previous action result are sent to the configured Ollama endpoint. Keep the endpoint local or use a service you trust. Actionable can gather resources through Baritone but does not yet craft tools or generate schematic files. The lookup tool uses the local item/block registries and player-known recipe-book recipes; it does not include a comprehensive database of biome-specific loot or ore-generation heights. Automated building beyond individual supported block placements requires a compatible schematic already saved in Baritone's `schematics` directory.

Ollama is best run as a separate local service rather than embedded in the mod JAR: model files and inference runtimes are large, and GPU support depends on native platform-specific libraries. Install Ollama, download the chosen Gemma model once, and let Actionable call its local HTTP API. A 4B model such as `gemma3:4b` is a practical starting point on consumer hardware.


## Contributors
Kai Battistoni, ChatGPT, Claude Code