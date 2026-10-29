# Gemma tactical agent

Against spiders, endermen, shulkers, wardens, skeletons, witches, piglins, hoglins, blazes, ghasts,
and raid mobs, the mod asynchronously sends a state snapshot to the local Ollama server with a
100 ms budget. It converts the returned tactic into Baritone commands. On timeout or malformed JSON,
the agent holds position with a raised shield.

## Configuration

Use `/llmagent config endpoint <url>` to set the Ollama URL, for example
`http://localhost:11434`. The setting is saved in `config/`.

## Debugging

Use `/actiondebug combat` to toggle the combat debug sidebar and tactic overlay, which show the
current tactic, target, Baritone path, and Ollama latency. Runtime tracking writes tactic changes
and a p95 latency summary (PASS/FAIL against the 100 ms budget) to `logs/gemma_agent.log`.

Invalid payloads (with 1-based line numbers), timeouts, and connection drops are also logged to
`logs/gemma_agent.log`.

Melee attacks are limited by a 4.5-block reach check and the weapon's attack cooldown meter.
