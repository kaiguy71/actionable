# Actionable version 0.1
Readme Date: 9/30/2026

A client-side Fabric mod framework for turning natural-language tasks into local Gemma plans and Baritone actions.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5, and Fabric API 0.161.0+26.3
- Java 25
- Ollama running locally with a Gemma model (by default, `gemma3:4b`)
- Baritone installed as a Fabric client mod, version 1.20.0 or newer, built for Minecraft 26.3

Run `ollama pull gemma3:4b` and start Ollama, then build with `./gradlew build`. Install the generated JAR from `build/libs` with Fabric API and the Fabric client build of Baritone for Minecraft 26.3 (the upstream `26.3` branch; mod ID `baritone`, currently version `1.20.0`). Do not use a Baritone jar unless its Fabric metadata declares Minecraft `~26.3`; matching the jar's filename or version number alone is not sufficient. Baritone is a required Fabric dependency, so Fabric Loader will report a missing or too-old version during launch.

In game, use `/action <task>`, for example `/action Build a 30x30 house made out of light-colored stone blocks`. Actionable sends a compact snapshot of the player's position, inventory, nearby sampled blocks, health, and nearby hostile mobs to Ollama, including whether a mob is targeting the player. It asks for one next action at a time and refreshes the snapshot every ten seconds so the plan can adapt while Baritone works. The model can use Baritone's `mine` command to gather resources and its `build` command to build an available schematic.

Press **Ctrl+`** to pause or resume planning. Pausing invalidates pending plans and sends Baritone `#cancel`; the active task resumes when re-enabled. Only a small allowlist of Baritone commands is accepted from the model. Set `-Dactionable.ollama.url=http://localhost:11434/api/chat` or `-Dactionable.ollama.model=gemma3:4b` to override the Ollama endpoint or model.

The prompt, compact world snapshot, and previous action are sent to the configured Ollama endpoint. Keep the endpoint local or use a service you trust. Actions are limited to an allowlist of Baritone commands; this first framework does not craft tools or generate schematics, so construction requires an existing usable schematic and needed tools/materials must already be available or gathered through supported commands.

Ollama is best run as a separate local service rather than embedded in the mod JAR: model files and inference runtimes are large, and GPU support depends on native platform-specific libraries. Install Ollama, download the chosen Gemma model once, and let Actionable call its local HTTP API. A 4B model such as `gemma3:4b` is a practical starting point on consumer hardware.


## Contributors
Kai Battistoni, ChatGPT, Claude Code