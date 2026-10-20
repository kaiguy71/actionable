# Actionable version 0.1.4
Readme Date: 10/19/2026

A client-side Fabric mod that plays Minecraft autonomously: an Ollama or Claude model chooses its own goals,
breaks them into steps, and carries them out through Baritone.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5, and Fabric API 0.161.0+26.3
- Java 25 (the mod is compiled for release 25; a Java 21 runtime will refuse to load it)
- Ollama running locally with a Gemma model (by default, `gemma3:4b`), or Claude Code installed and signed in (see configuration)
- Baritone installed as a Fabric client mod, version 1.20.0 or newer, built for Minecraft 26.3

## Installation

1. Run `ollama pull gemma3:4b` and start Ollama (run `ollama` in the command prompt, then exit after
   choosing to use it without an account). Ollama should now show up as a hidden icon on your taskbar.
2. Build with `./gradlew build` (this also runs the unit tests; `./gradlew test` runs only the tests).
3. Install the generated JAR from `build/libs` (`actionable-0.1.4-26.3-fabric.jar`) with Fabric API
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

Every model request starts its system prompt with `GET TO THE NETHER!!!`, for both Claude and
Ollama, including goal selection, decomposition, actions, and optional judging. This sets the
objective; the existing progression guidance and action validation still apply.

Planning runs in four layers, each a separate call to the configured model:

All interruptions invalidate old request tokens and clear every planner busy flag together. Late
replies cannot leave goal selection stuck or clear a newer request's state.

1. **Director** — picks the next goal, and must say why, from a progression briefing, the world state,
   the goals already achieved, and the goals recently attempted. Only runs when no task is active.
2. **Decomposition** — breaks that goal into an ordered plan of at most 8 concrete steps. The plan is
   printed to chat and persists for the whole task. Goals the tech tree knows (`craft 1
   minecraft:wooden_pickaxe`, `mine 8 minecraft:stone`) are planned in code instead, with no model call.
3. **Step loop** — works one step at a time, re-planning about every 10 seconds (200 ticks). The model
   sees the numbered plan with the current step marked, the last 5 actions and their results, Baritone's
   elapsed time and progress, and the world snapshot. It picks one action per turn, and may return
   `wait` to let a running `mine` continue.
4. **Step check** — decided in code, not by a model call. A step that names a quantity and an item
   (`mine 3 minecraft:oak_log`, `craft 4 minecraft:stick`) is compared with the inventory every second (ores map to
   what they drop, so `iron_ore` checks `raw_iron`) and the plan advances once it holds that many. Steps it cannot
   read are left to the step loop, which may return `step_complete` itself. Set `-Dactionable.judge.model=true` to
   also send unreadable steps to a small judge model call, as earlier versions always did.

Known inventory/world facts override model `complete` and `step_complete` claims. A model cannot
skip an unmet item quantity or missing house furniture, or finish a multi-step task early. Completion
is rechecked on each observation. Adjacent duplicate steps are merged; gathering again after an
intervening craft is preserved because the first batch may have been consumed.

Crafting plans leave tables standing. A distant table is reached with `go to crafting table`, and
is only reclaimed by code when explicitly moving it into an already built house. Goals involving
house furniture first build the house when it is missing. Distinct house/furniture goals retain
separate achievement identities.

A step that is already an action (`mine N X`, `craft N X`, `place minecraft:crafting_table nearby`) is
run directly the first time, without a planning round trip. If it fails or needs follow-up, the step
loop model takes over and sees the failure.

### Progression knowledge

The director and decomposer get a progression briefing built from a curated tech tree:

| Field | Meaning |
| --- | --- |
| `pickaxe_tier` | Best pickaxe held: `none`, `wood`, `stone`, `iron`, `diamond`. |
| `cannot_mine_yet` | Blocks that drop nothing with the current tools, and the pickaxe each needs. |
| `next_milestone` | The next step on the ladder, with the reason it matters. |
| `milestone_plan` | The exact steps the code would use for that milestone. |
| `craftable_now` | Items whose ingredients are already in the inventory, and whether a table is needed. |
| `inventory_uses` | What each held item is for, or that it has no known use. |
| `craftable_catalog` | Everything Actionable can craft. |

After the shelter and basic tools, milestones continue through iron pickaxe/sword, shield, iron armor,
bucket and water, flint and steel, a diamond pickaxe, 10 obsidian, and entering the Nether.
Smelted iron and iron invested in gear count toward early iron progress, so smelting does not send
the player back to gather the same raw-iron stock repeatedly. Worn armor and offhand items count
as possessions. Metal crafting plans gather raw ore and fuel and use the house furnace, rather than
trying to mine ingots. Nether entry is complete only when the observed dimension is `minecraft:the_nether`.

### Early game and nights

The first goals are a fixed checklist run by code: 20 logs, a 5x5 house (Baritone builds it from a
generated schematic), a solid dry floor, a crafting table and a door inside, a wooden sword and pickaxe,
3 white wool from sheep, and a bed. The house corner is saved per world in `config/actionable-homes.json`.
There is no fixed home-range limit: movement and mining may go beyond the start area.

House completion is structural. Every expected wall and roof cell must be a full, dry collision
barrier; leaves, plants, torches and partial blocks do not fill a gap. A percentage is used only
to display building progress. The shell, floor/interior, and door remain separate construction stages
so the builder can finish before the floor and door jobs begin. `house_structure` reports `enclosed=true`
only when all walls/roof are sealed, the required floor/entry supports are dry and sturdy, the interior
is dry and clear except for floor-level furniture/workstations, entrance headroom is clear, and both
halves of a correctly oriented wooden door are installed and closed. Planned staircase openings are
preserved. A valid door is closed automatically when the player has moved clear of it and is still
within interaction reach; an open door is reported as open, not enclosed.

Loaded structures are checked again after completion, so later damage invalidates the shell/shelter
status. Unloaded chunks are reported as unknown and retain last-known progress rather than inventing
damage. Existing homes are repaired at exact missing/non-barrier cells; repair plans gather only the
missing shell material and do not reapply schematic air over furniture. Floor/interior jobs also clear
obstructing terrain. Wall/roof and floor repairs take priority over night-time home jobs, so a door
is not repeatedly hung into unfinished framing. Logs include `house_structure`, `house_repair` and
`house_close_door`. Structural validation is not a guarantee against mobs entering through an open
door or the mine tunnel.

At night, a player with a bed sleeps. Without one it works from home, picking the most useful job it can
do without leaving: hang the door, place the table, craft tools, build and use a furnace, or dig the
staircase mine that starts inside the house (cobblestone first, then iron, down to y=16). The mine stops
and the player climbs out on a cave, water, lava, low health or food, or a full inventory. The briefing
includes a Situation line with what the player has, needs and can do now.

### Exploration comparison branch

This branch preserves the original Baritone `explore` command, planner guidance and exploration fallback.
Water-bucket searches use `explore` when no usable source is observed. Diamond mining uses the normal
Baritone mining path; the experimental staircase/tunnel diamond miner is not included. Shared survival,
crafting, logging, Nether preparation and repeat-guard fixes remain included.

Several decisions are made in code rather than by the model, because `gemma3:4b` was measured failing them:

- **Survival.** Code checks observed threats every client tick before task actions or model planning.
  Inherently hostile mobs (including Drowned, slimes and flying hostiles) are recognized without needing
  a synchronized AI target. Neutral endermen, piglins and spiders require observed aggression, a
  target on the player, or recent observed damage. In single-player, actual attackers are remembered
  for five seconds from damage events; this memory is cleared on disconnect. Players are escaped,
  never automatically attacked. Healthy players fight eligible hostiles in normal melee reach with line of
  sight, using the best available sword/axe. Seeing a hostile within the 16-block observation radius
  does not itself interrupt work. Immediate threats include visible melee mobs within 4 blocks,
  mobs approaching within 10 blocks (inferred from their own observed position changes over five ticks),
  observed targets/recent attackers, and visible ranged hostiles within 10 blocks. Ordinary melee
  mobs separated vertically by more than 4 blocks do not trigger proximity/approach reactions.
  Creepers trigger escape within 7 blocks on the same level, when approaching within 10 blocks,
  or with a visible active fuse within 12 blocks (within 6 blocks even behind cover); merely seeing
  one at 16 blocks is watch-only. Visible
  bosses remain threats throughout the observation radius. Low health (6 or less) and close crowds
  cause escape when an immediate threat exists. Incoming collision-course projectiles, primed TNT,
  lava, fire and suffocation also trigger escape. Detection is limited to loaded observable entities;
  this does not implement ranged attacks, projectile interception or boss-specific combat mechanics.
  Escape is entirely code-owned: the model is not called and cannot declare it complete. Escape ends
  only after three continuous seconds without a threat, or switches immediately to eligible melee.
  Stopped/stalled routes are reissued in code, preferring loaded dry endpoints with headroom, solid
  footing, no large height change and increased separation. A column-only fallback leaves terrain
  height to Baritone when no suitable dry endpoint is found. This checks endpoints, not every block
  along Baritone's route, and cannot guarantee survival. Recent escape threats are remembered for
  60 seconds of game ticks. Mining excludes deposits and standing cells within 12 horizontal blocks
  and 8 vertical blocks of those locations. While this memory is active, mining breaks explicitly
  selected safe blocks instead of issuing an unrestricted Baritone mine that could return to the
  same dangerous tree. With no observed safe deposit, mining reports a recoverable failure.
  This memory is bounded and cleared on disconnect; it does not constrain every navigation route.
  The interrupted task resumes afterward using these resource exclusions.
  Non-pausing menus close for immediate melee defense; an explicit game pause or disabling Actionable
  still takes precedence. The death screen stops all planning until the player respawns.
  Logs include `THREAT`, `ESCAPE_STARTED`, `ESCAPE_ROUTE`, `SURVIVAL_CLEAR`, and `SURVIVAL_DEAD`.
  Threat observations include entity type/ID, block-rounded XYZ coordinates, distance, visibility,
  height separation, reach, target/damage evidence, approach and fuse state, and the decision reason.
- **Breathing.** Before other task ticks, submersion triggers immediate surfacing without a model
  call. The override invalidates pending replies, stops mining/crafting/hunting/combat, holds swim-up,
  cancels sneaking, and seeks a loaded air/shore cell above water or solid ground. It retries the route
  every five seconds and continues until air recovers, then resumes the interrupted task. It runs in
  background mode too; disabling Actionable disables the override. Logs include `WATER_RESCUE_STARTED`
  and `WATER_RESCUE_ENDED`; observations include remaining air and whether the head is submerged.
  This is an escape attempt, not invulnerability: a sealed trap without a reachable air pocket still
  needs in-game validation.
- **Dark areas.** Every second, Actionable rates the player's surroundings from light level, sky
  visibility, depth below the local surface (ignoring leaves), time of day, health, food and gear.
  The rating is shown to the model as `safety={...}`. If the player is at least 4 blocks below the surface,
  cannot see the sky, is at light level 4 or lower, and is not `cave_ready` (a pickaxe and weapon), Actionable abandons the current task. It then walks back to the last lit surface spot it
  recorded, or straight up if there is none, and avoids that area when choosing later mining sites.
- **Tools and mining sites.** Before a `mine` command reaches Baritone:
  - Items become the block that drops them (`cobblestone` → `stone`, `coal` → `coal_ore`).
  - A block that drops nothing without a tool the player lacks is refused with `MINE_FAILED_NO_TOOL`.
  - Without cave gear, only deposits with a face exposed to the sky or to light 8+, and at most 6 blocks
    below the surface, are accepted. If every loaded deposit is in the dark, the command is refused with
    `MINE_FAILED_UNSAFE_SITE`. If the best site is more than 8 blocks away, Actionable walks there first
    so Baritone starts next to it.
  - A running `mine` is not canceled merely for moving away from its starting position. Mining sites
    are not filtered by distance from home, and missing local deposits are left to Baritone's search.
- **Goal validation and repeat avoidance.** A director goal that needs a missing tool, or names
  something Actionable cannot craft, is rejected in chat (`Rejected goal ...`). A chosen goal is also
  compared with its full action and objective; equivalent block/drop names are normalized. Goal identity preserves the action, target quantity and furniture/location rather than just
  its last word. Rejected, repeated or failed goals are replaced with the next progression milestone. A milestone
  attempted twice recently is skipped. When no milestone is left, Actionable explores instead.

- **Loop and plan sanity.** The same goal attempted twice without being achieved is replaced the same way
  as an achieved one. Exploration goals remain available; duplicate steps are removed
  and any `mine` quantity is capped at 64.
  Block interactions and menu moves are tracked by target so distinct targets are not treated as repeats.
  Within a task, an action that fails twice in a row, or is repeated 3 times with no observed progress,
  is refused with `REPEAT_BLOCKED`. Fresh inventory/position evidence is checked before a veto.
  A block observed present at the start and absent at completion also counts as work, even before its
  drop is collected. Duplicate events, already-air requests and model claims do not get that credit.
  At most 64 distinct verified work events per unchanged step/target state reset the guards and direct
  step retry counter; flint has no blanket exception. Repeated failures and the five-minute task
  timeout still apply. If 6 planning rounds have no observed progress, the task is abandoned.
  A stalled/timed-out goal is not immediately chosen again with unchanged inventory and coarse
  location: it waits for changed conditions or a 60-second game-time cooldown. This check applies to
  forced milestones as well as director choices. Logs include `VERIFIED_WORK` and `GOAL_COOLDOWN`.

Ordinary tasks still running after 5 minutes (6,000 ticks) are abandoned. The staircase miner
uses its own timeout. Actionable remembers the last 20 achieved and 20 attempted goals.

## Debug panel

### Logs

Actionable writes to Minecraft's standard `logs/latest.log` in the profile folder.
In CurseForge, choose the profile's **⋯ → Open Folder**, then open **logs → latest.log**.
Search for `[Actionable]`. Older session logs use Minecraft's normal archived log files.

Logs include startup/provider selection, task and step progress, selected actions and results,
Baritone feedback, combat start/end and actual hits requested, plus model request IDs, planning stage,
model, duration, and failures. Every Claude/Ollama reply is logged before parsing as
`model_response_body id=... stage=... provider=... body=...`, including malformed replies.
The body is the full provider response envelope (Claude CLI output or Ollama HTTP response),
JSON-escaped onto one line without truncation. Match its ID to `model_request`, `model_response`,
or `model_failure`. No response body is available if the request times out before returning.
Ollama uses the same per-stage JSON schemas as Claude, with an explicit 8,192-token context,
768-token response cap and temperature 0.2. Length-limited replies are reported as
`OLLAMA_RESPONSE_TRUNCATED` before gameplay JSON parsing; repeated request failures count toward
the no-progress guard. These options do not rely on the Ollama server's 4,096-token default.
Full model prompts and world snapshots are not logged. Combat cooldown
checks are not logged every tick. Restart Minecraft after installing the updated jar to enable logging.

`/actiondebug` shows:

| Line | Meaning |
| --- | --- |
| `State` | Current phase, plus `[auto]` or `[manual]`. |
| `Task` | The goal being pursued, or `none`. |
| `Achieved` | Goals completed so far this session (up to 20). |
| `Step` | Current step number, total steps, and the step text. |
| `Player xyz` | Your block position. |
| `Baritone cmd` | The Baritone command currently running. |
| `Baritone` | Elapsed time, movement, and distance to a `goto` target. |
| `Baritone feedback` | The most recent `[Baritone]` chat line. |
| `LLM goal` | The model's current subgoal. |
| `Why` | The director's reason for the current goal, or the milestone's reason when code chose it. |
| `Safety` | `safe`, `caution` or `danger`, with the first reason. |
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
| `CLAUDE_REQUEST_FAILED` | Claude could not start, exited with an error, or returned an error result. Check the executable path, login, and model access. |
| `CLAUDE_TIMEOUT` | Claude did not finish within 180 seconds; its process is stopped. |
| `CLAUDE_INVALID_JSON` | Claude returned an invalid response object. Check the request stage and error in `logs/latest.log`. |
| `PLAN_NO_VALID_ACTION` | The model returned no action Actionable could validate. |
| `ACTION_TYPE_UNSUPPORTED` | The model asked for an action type that does not exist. |
| `BARITONE_COMMAND_REJECTED` | Baritone refused the command; Actionable re-plans immediately. |
| `REPEAT_BLOCKED` | The model repeated an action that kept failing or changed nothing; Actionable refused it. Not fatal. |
| `EQUIP_FAILED_*` | An `equip` could not be completed: `NO_WORLD`, `UNKNOWN_ITEM`, `CONTAINER_OPEN`, `NOT_IN_INVENTORY`. |
| `MINE_FAILED_NO_TOOL` | The block drops nothing without a tool the player lacks (for example stone with no pickaxe). |
| `MINE_FAILED_UNSAFE_SITE` | Every loaded deposit nearby is dark or deep underground, and the player lacks cave gear. |
| `MINE_FAILED_CRAFTED_ITEM` | The model tried to mine something that is crafted, such as planks. |
| `CRAFT_FAILED_*` | A craft could not be done: `UNSUPPORTED`, `MISSING_INGREDIENTS`, `NO_TABLE`, `TABLE_OUT_OF_REACH`, `TABLE_DID_NOT_OPEN`, `NO_RESULT`, `OTHER_SCREEN_OPEN`, `NO_WORLD`. |
| `BLOCK_PLACE_FAILED_*` | A `place_block` could not be completed: `NO_ITEM`, `MATERIAL_MISSING`, `UNKNOWN_BLOCK`, `NO_SUPPORT`, `OUT_OF_REACH`, `TARGET_OCCUPIED`, `NO_HEADROOM`, `PLAYER_IN_WAY`, `NO_WORLD`. |
| `JUMP_PLACE_FAILED_*` | A `jump_place` could not be completed: `NOT_CURRENT_CELL`, `NOT_GROUNDED`, `JUMP_TIMEOUT`, `TARGET_OCCUPIED`, `NO_HEADROOM`, `WORLD_ENDED`. |
| `GAME_SESSION_ENDED` | The world or connection closed while a plan was in flight. |

`BLOCK_PLACE_DEFERRED_*` is not an error. It appears in `Result` when a placement needed one more tick
(an obstruction was cleared first) and is retried on the next tick rather than after the usual delay.

## Actions and safety

The model's Baritone commands are restricted to an allowlist — `mine`, `goto`, `build`,
`explore`, `find`, `pickup`, `farm`, `cancel`, `come`, `follow` — and each is argument-checked before it is sent,
so the model cannot run an arbitrary chat command. `mine` takes `<quantity> <blockID>` (for example
`mine 32 minecraft:diamond_ore`; no coordinates), `goto` takes exactly three integer coordinates (no
block IDs), and `explore` takes no arguments. Exploration goals are accepted. Commands that fail
validation are discarded as `PLAN_NO_VALID_ACTION`. Small models often return a command as separate fields rather than one string,
so Actionable reconstructs the command from either shape before validating it.

Besides Baritone commands the model can return `attack`, `wait`, `lookup` (registered block/item IDs,
tech-tree uses and recipes, and recipes known to the player's recipe book), `equip`, `craft`, `place_block`, and
`jump_place`. Baritone's own `build` command
loads an existing `.schematic`, `.schem`, or `.litematic` file; it does not place individual blocks.
Actionable's guarded single-block placement moves an available inventory stack to the hotbar, clears a
replaceable plant at the target, aims at an adjacent sturdy face, and places the block if the target is
in reach. `jump_place` jumps up and places the block beneath the player, for the player's own feet cell.
Routine status goes to the actionbar; plans go to chat.

### Crafting

`{"type":"craft","item":"minecraft:stick","count":4}` crafts until the inventory holds `count` of the
item, the same "total to hold" meaning as Baritone's `mine` quantity. Actionable clicks fixed patterns
into the inventory's 2x2 grid, or into a crafting table within reach for 3x3 recipes. It does not use the
recipe book, so crafting works before recipes are unlocked. This needs the default
`doLimitedCrafting=false`. Craftable items: any planks, sticks, crafting table, wooden and stone
pickaxe/axe/sword/shovel, torches, furnace, chest, wooden doors and white beds. The catalog also includes
iron and diamond pickaxes/axes/swords/shovels and all four armor pieces, buckets, flint and steel,
shields and golden boots. These 20 added recipes are checked against the bundled Minecraft recipe data
by a regression test. Wood variants are interchangeable, and planks
prefer the matching log.

Flint gathering breaks individual exposed gravel blocks until flint actually appears; holding gravel
does not complete a flint objective. Obsidian requires a diamond-or-better pickaxe. Obsidian placement
uses exact coordinates: it never silently falls back to another cell, which would break a portal frame.

### Buckets and Nether entry

The `fill water bucket` objective has a code-owned plan and controller. It does not ask a model to
decompose that task into crafting or mining. It selects a loaded water source, fills from reach or
walks to a dry bank with a clear interaction ray, and explores when no usable source is observed.
Inventory evidence must show a water bucket before the objective completes; the normal task timeout
still applies. It does not intentionally navigate into the source cell.

`{"type":"use_item","item":"minecraft:bucket","x":10,"y":64,"z":20}` fills an empty bucket from
the specified observed water/lava source. With `water_bucket` or `lava_bucket`, coordinates name the
solid block to click and `face` names the adjacent cell to fill (`up` by default; also `down`, `north`,
`south`, `east`, `west`). `flint_and_steel` clicks the top of an obsidian base to light a portal.
The action validates loaded coordinates, reach, visibility, inventory, source fluids and destination.
It rejects placing water in the Nether, placing fluid into the player or occupied cells, and lava
placement within three blocks of the player. A sent interaction is not proof of success: subsequent
inventory, fluid and portal observations must confirm it. Snapshots include nearby source-fluid
coordinates as well as obsidian and portal resource hints.

The preparation route uses a diamond pickaxe to harvest 10 ordinary obsidian and a minimum cornerless
frame with a 2x3 interior. The model receives frame geometry and uses validated individual placement,
bucket and ignition actions, then walks into the lit portal. Construction and route choice are still
model-driven, not an end-to-end tested speedrun controller. Shield blocking is not automatic.
Both direct bed interaction and the nightly sleep routine refuse explosive beds in the Nether/End.

### Inventory

During enabled play, inventory maintenance equips one available armor upgrade or a shield into an
empty offhand every five seconds when no menu/action blocks it. It never replaces an elytra or
removes worn gold in the Nether; ordinary upgrades follow material tier, and available gold boots are preferred
there when no gold is worn. Equipment swap packets use container ID, menu slot and hotbar button
in that order. Equipping a shield does not implement active shield blocking.

Every snapshot lists occupied inventory slots with durability, item totals, the held item, the
off-hand, worn armor (head, chest, legs, feet), and the number of free inventory slots.

`{"type":"equip","item":"minecraft:iron_sword"}` puts an item the player already carries into use.
Tools and weapons are moved to the selected hotbar slot and held; helmets, chestplates, elytra,
leggings, boots, and shields are swapped into their wear slot. Results are `EQUIP_SENT`,
`EQUIP_ALREADY`, or `EQUIP_FAILED_*`. Equipping cannot create items and does not open chests or other
containers; it is refused while a container screen is open.

### Menus, right-click and screenshots

These are internal model actions, not player commands:

- `{"type":"use_block","x":..,"y":..,"z":..}` right-clicks a block within 4.5 blocks (opens crafting
  tables, furnaces, chests, barrels; uses doors and beds).
- `{"type":"menu","op":"read"}` lists occupied slots of the open menu (or the inventory); `quick_move`
  (shift-click), `move` (slot to slot), `drop` and `close` operate on it.
- `{"type":"screenshot"}` attaches the current view to the model's next turn (Ollama vision models only).
  With `/actiondebug` on, shots are saved to `screenshots/LLM-screenshots/<timestamp>.png` and zipped into
  `session-<timestamp>.zip` when you leave the world or quit.

Each new `/action` sends `#stop` and starts with no memory of earlier tasks. Baritone's live state
(active process, pathing) is read through its API and shown to the model as `baritone_live=`; when Baritone
goes idle on its own the model is told. The inventory sent to the model lists only occupied slots with
durability, plus item totals. Autonomous mode follows
[progression.txt](src/main/resources/assets/actionable/progression.txt) (wood to Ender Dragon, including
house, storage, farm and ranch stages). Darkness and night are cautions, not dangers; the model is told to
prepare a weapon, light source and bed.

### Basic combat

The planner can issue `{"type":"attack","entity_id":123}` to send one melee hit to a hostile mob.
The world snapshot supplies mob entity IDs, health, and `melee_reachable`. Targets must be alive,
within the player's normal entity interaction reach, and visible. The action waits for at least 90%
attack cooldown, prefers a sword (then an axe) already in the hotbar, and otherwise uses the held item.
`ATTACK_SENT` means a hit was requested; subsequent observations determine damage or death.

Autonomous mode provides immediate close-range defense without waiting for Claude or Ollama.
Manual tasks can also request attacks through the planner. Players and passive animals are excluded from defense. Neutral endermen, piglins, zombified piglins
and spiders require observed aggression; creepers and bosses trigger escape instead of melee.
Health at or below 6 prevents attacks. There is no pursuit, ranged attack, shield handling, or boss attack strategy; code escapes those threats.
Pause with Ctrl+` to stop automatic attacks. Restart Minecraft after updating the mod jar.

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
| `-Dactionable.background` | `false`; allow unfocused melee defense for parallel testing (menus and pauses still block combat) |
| `-Dactionable.model.provider` | `ollama` (use `claude` for Claude Code print mode) |
| `-Dactionable.ollama.url` | `http://localhost:11434/api/chat` |
| `-Dactionable.ollama.model` | `gemma3:4b` |
| `-Dactionable.claude.command` | `claude` (executable name or full path; no extra arguments) |
| `-Dactionable.claude.model` | `claude-sonnet-5-5` |

Set these as JVM arguments in your launcher profile.

### Claude Sonnet 5.5 through `claude -p`

Install the native Claude Code CLI and run `claude auth login` in a terminal using the same
Windows account that launches Minecraft. Verify your account can use the model:

```powershell
claude -p --model claude-sonnet-5-5 "Reply with OK"
```

Add these JVM arguments to your Minecraft launcher profile, then restart Minecraft:

```text
-Dactionable.model.provider=claude -Dactionable.claude.model=claude-sonnet-5-5
```

Ollama is not needed in Claude mode. If Minecraft cannot find `claude`, set the full native
executable path, for example `"-Dactionable.claude.command=C:\Users\YOUR_NAME\.local\bin\claude.exe"`.
Use the native executable on Windows; PowerShell aliases and npm `.cmd` shims cannot be launched
directly by this integration. You can change `actionable.claude.model` to any model ID your CLI accepts.

All four planning layers invoke a fresh `claude -p` request on the background planner thread.
Prompts are supplied through UTF-8 files/stdin, with a JSON Schema for each planning stage and validated
`structured_output` replies, built-in tools disabled, an empty
MCP configuration, and no persisted session. Each request has a 180-second timeout. Claude receives
the same world snapshot and action history as Ollama; requests use your Claude account's usage limits.
The CLI runs in a temporary directory and loads user settings rather than Minecraft project settings.
The existing action allowlist and validation still apply to its replies.

CLI flags and model selection: [Claude Code CLI reference](https://code.claude.com/docs/en/cli-reference)
and [Claude model configuration](https://support.claude.com/en/articles/11940350-claude-code-model-configuration).

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

- **Limited crafting.** Automatic recipes cover the catalog; other recipes require model-driven menu
  operations. The house furnace cooks food and smelts raw ore. Torches are recommended for caves;
  cave readiness currently requires a pickaxe and weapon, and torch placement is not automatic.
- **Limited schematic generation.** The starter house schematic is generated in code. Other schematic
  builds require a compatible file already saved in Baritone's `schematics` directory.
- **Combat is close-range melee only.** No combat pursuit, ranged weapons, shields, or boss strategy.
- **Decomposition quality is limited by the model.** On a vague goal, `gemma3:4b` produces redundant
  steps; concrete goals such as "get 4 oak logs" decompose cleanly.
- The `lookup` tool uses local registries and player-known recipes. It has no database of biome-specific
  loot or ore-generation heights, and the model is instructed not to invent them.

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
