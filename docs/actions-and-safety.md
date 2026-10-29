# Actions and safety

## Baritone commands

The model can issue only the allowlisted `mine`, `goto`, and `cancel` Baritone commands, and each is
validated before it is sent. The model cannot issue arbitrary chat commands.

- `mine` takes `<quantity> <blockID>`, such as `mine 32 minecraft:diamond_ore`; it takes no
  coordinates.
- `goto` takes exactly three integer coordinates and no block IDs.
- `travel` is a separate action. A request such as
  `{"type":"travel","direction":"NORTH"}` (also `SOUTH`, `EAST`, or `WEST`) sends Baritone to a
  point 500 blocks in that direction using two coordinates, so Baritone chooses the surface height.
  At the end of the leg, the model gets `TRAVEL_LEG_DONE` and chooses whether to travel farther or
  act on what it found. Before the player has stone gear, travel stays near home.

The old `explore` command is no longer available. Invalid commands are discarded as
`PLAN_NO_VALID_ACTION`. Actionable accepts a command as either a string or separate fields and
reconstructs it for validation.

## Model actions

Besides Baritone commands, the model can return `attack`, `wait`, `lookup`, `place_block`,
`screenshot`, `use_block`, `menu`, `break_block`, `craft`, and `white_flag`.

The house uses Baritone's `build` command with a generated schematic internally; the model cannot
call it. `place_block` moves a stack to the hotbar if needed, clears a replaceable plant, aims at an
adjacent sturdy face, and places within reach. Its supplied coordinates are a hint: the action
searches outward, upward, then downward for a free cell with a solid anchor, avoiding mobs, the
player's body, and plain water. If boxed in, it can jump and place beneath the player. `jump_place`
is accepted as an alias.

`white_flag` gives up on the current model session, sends Baritone `#stop`, and restarts the task
from its original prompt in a fresh session; an autonomous goal is dropped and another is chosen.
Five consecutive restarts of the same task end it.

Before a task counts as finished, the model gets one final-check turn with a fresh synopsis and
screenshot to confirm the result. The check is skipped when code already verified the final step
from the inventory or world, and runs at most once per task.

On disconnect, world exit, or game exit, in-flight model requests are cancelled and mod and model
state reset. Routine status appears in the actionbar; plans appear in chat.

## Crafting

`{"type":"craft","item":"minecraft:stick","count":4}` crafts until the inventory holds four sticks;
`count` is a total-to-hold quantity, like Baritone's `mine`.

Actionable uses fixed patterns in the inventory's 2x2 grid or a crafting table within reach for 3x3
recipes. It does not use the recipe book, so recipes can be crafted before they are unlocked. This
requires the default `doLimitedCrafting=false`.

The craftable items are planks, sticks, crafting tables, wooden and stone pickaxes/axes/swords/
shovels, torches, furnaces, and chests. Wood variants are interchangeable, and planks prefer the
matching log.

## Menus, blocks, and screenshots

These are model actions, not player commands:

- `{"type":"use_block","x":...,"y":...,"z":...}` right-clicks a block within 4.5 blocks. This can
  open crafting tables, furnaces, chests, or barrels, and use doors or beds.
- `{"type":"menu","op":"read"}` lists occupied slots in the open menu or inventory. `quick_move`
  (shift-click), `move` (slot to slot), `drop`, and `close` operate on it.
- `{"type":"screenshot"}` attaches the current view to the model's next turn (Ollama vision models
  only). With `/actiondebug` enabled, screenshots are saved under
  `screenshots/LLM-screenshots/` and zipped as `session-<timestamp>.zip` when leaving the world or
  quitting.

Every `/action` starts a fresh model session with no memory of previous tasks. The model sees
Baritone's live process and pathing state as `baritone_live=`; it is notified when Baritone becomes
idle. The inventory context includes occupied slots, durability, and item totals.
Autonomous mode follows
[`progression.txt`](../src/main/resources/assets/actionable/progression.txt), from wood to the Ender
Dragon, including house, storage, farm, and ranch stages.

## Basic combat

The planner can request one melee hit with `{"type":"attack","entity_id":123}`. The world snapshot
provides entity IDs, health, and `melee_reachable`. Targets must be alive, visible, and within normal
interaction reach. The action waits for at least 90% attack cooldown, prefers a sword (then an axe)
already in the hotbar, and otherwise uses the held item. `ATTACK_SENT` means a hit was requested;
later observations determine whether it caused damage or killed the target.

Autonomous mode provides immediate close-range defense without waiting for Claude or Ollama. Manual
tasks can also request attacks through the planner. Players, animals, endermen, piglins, zombified
piglins, and creepers are excluded; spiders require an observed target on the player. Actionable
does not attack at health 6 or lower. There is no pursuit, ranged combat, shield handling, or boss
strategy. Pause with Ctrl+` to stop automatic attacks. Restart Minecraft after updating the mod JAR.
