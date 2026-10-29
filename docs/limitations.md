# Limitations

- **Limited crafting and no smelting:** only catalog items can be crafted, and furnace smelting is
  not implemented, so iron progression stops at a raw iron stockpile. Torches count toward cave
  readiness, but Actionable does not place them as part of that readiness check.
- **Building requires a schematic:** automated building beyond individual block placements requires
  a compatible schematic already saved in Baritone's `schematics` directory.
- **Combat is close-range melee only:** there is no combat pursuit, ranged combat, shield handling,
  or boss strategy.
- **Model quality affects plans:** vague goals may produce redundant steps with smaller models such
  as `gemma3:4b`; concrete goals such as "get 4 oak logs" tend to decompose more cleanly.
- **Lookup has no world-generation database:** it uses local registries and player-known recipes,
  but has no database of biome-specific loot or ore-generation heights. The model is instructed not
  to invent those details.
