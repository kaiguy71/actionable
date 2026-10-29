# Model context and privacy

Actionable sends a compact world snapshot to the configured model. It includes the player's exact
position, yaw and pitch, looked-at block, current biome, a sample of nearby biomes in loaded chunks
with distance and direction relative to facing, nearby tree logs and ores, local placement surfaces,
hotbar and inventory, health, and nearby hostile mobs. The model also receives the current plan,
the last five actions and results, and recent Baritone feedback.

Coordinates use Minecraft convention: **+X east, +Z south, +Y up**; yaw 0 faces south, yaw 90 west,
and yaw -90 east.

Keep the model endpoint local or use a service you trust. See [Configuration](configuration.md) for
provider settings. With `/actiondebug` enabled, screenshots can be saved locally; see
[Actions and safety](actions-and-safety.md#menus-blocks-and-screenshots).
