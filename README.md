# Actionable

A client-side Fabric mod framework for turning natural-language tasks into local Gemma plans and Baritone actions.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5, and Fabric API 0.161.0+26.3
- Java 25
- Ollama running locally with a Gemma model (by default, `gemma3:4b`)
- Baritone for Minecraft 26.3 installed as a client mod

Run `ollama pull gemma3:4b`, start Ollama, then build with `gradle build`. Install the generated JAR from `build/libs` with Fabric API and Baritone.

In game, use `/action <task>`, for example `/action Build a 30x30 house made out of light-colored stone blocks`. Actionable sends a compact snapshot of the player's position, inventory, nearby sampled blocks, and nearby hostile mobs to Ollama. It asks for one next action at a time and refreshes the snapshot every ten seconds so the plan can adapt while Baritone works. The model can use Baritone's `mine` command to gather resources and its `build` command to build an available schematic.

Press **Ctrl+`** to pause or resume planning. Pausing invalidates pending plans and sends Baritone `#stop`; the active task resumes when re-enabled. Only a small allowlist of Baritone commands is accepted from the model. Set `-Dactionable.ollama.url=http://localhost:11434/api/chat` or `-Dactionable.ollama.model=gemma3:4b` to override the Ollama endpoint or model.

The prompt, compact world snapshot, and previous action are sent to the configured Ollama endpoint. Keep the endpoint local or use a service you trust. Actions are limited to Baritone commands; this first framework does not craft tools or generate schematics, so construction requires an existing usable schematic.
