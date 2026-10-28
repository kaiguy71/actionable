# Behavior and progression

## Planning

Planning uses four model calls:

1. **Director:** selects the next goal and explains why, based on the progression briefing, world
   state, completed goals, and recently attempted goals. It runs only when no task is active.
2. **Decomposition:** turns a goal into an ordered plan of up to eight concrete steps. Known tech-tree
   goals, such as crafting a wooden pickaxe or mining stone, are planned in code without a model call.
   The plan is printed in chat and persists for the task.
3. **Step loop:** chooses one action at a time and replans about every 10 seconds (200 ticks). The
   model sees the plan and current step, the last five actions and results, Baritone progress, and
   the world snapshot. It can return `wait` while a `mine`, `goto`, or `travel` continues.
4. **Judge:** checks whether a step is complete. Quantity-and-item steps, such as `mine 3
   minecraft:oak_log`, are checked against the inventory every second without a model call; other
   steps use a dedicated model call.

Steps that already express an action, such as `mine N X`, `craft N X`, or
`place minecraft:crafting_table nearby`, run directly the first time. If they fail or need follow-up,
the step loop takes over.

The progression briefing is built from a curated tech tree and includes:

| Field | Meaning |
| --- | --- |
| `pickaxe_tier` | Best held pickaxe: `none`, `wood`, `stone`, `iron`, or `diamond`. |
| `cannot_mine_yet` | Blocks that require a tool the player does not have. |
| `next_milestone` | The next progression step and why it matters. |
| `milestone_plan` | The code's planned steps for that milestone. |
| `craftable_now` | Items whose ingredients are available and whether a table is needed. |
| `inventory_uses` | Known uses for held items. |
| `craftable_catalog` | Everything Actionable can craft. |

Milestones are a wooden pickaxe, stone pickaxe, stone sword, 16 torches, a furnace, and 12 raw iron.
Completed milestones are skipped.

## Early game and nights

The opening checklist mirrors
[`progression.txt`](../src/main/resources/assets/actionable/progression.txt): gather eight logs of a
type that grows in the biome; make a wooden pickaxe and axe, then stone pickaxe, axe, shovel, and
sword; gather 64 cobblestone and 16 logs; build a 5x5 house; and add a solid, dry floor, a crafting
table and door inside, a wooden sword, three white wool, and a bed. The house is built from a
generated schematic; the schematic lays a plank floor one level below the house, and the floor step
patches remaining gaps such as the doorstep. The house corner is saved per world in
`config/actionable-homes.json`.

Before building, the site scan checks corners within 14 blocks of the player. It prefers the fewest
logs and leaves in and around the house footprint, flatter ground, no water, and shorter distance.

Until the player has a stone pickaxe and sword, after the house exists the bot stays within six
chunks horizontally and 30 blocks vertically of home. Before the house exists, it can travel to find
wood. The first launch use of Baritone also sets `blacklistClosestOnFailure false`,
`useSwordToMine false`, its three parkour options, `allowVines true`, `legitMine false`, and
`smoothLook true`.

At night, the player sleeps if a bed is available. Otherwise, Actionable works from home on tasks it
can do there, such as placing a door or table, crafting tools, using a furnace, or digging a staircase
mine. Mining stops for caves, water, lava, low health or food, or a full inventory.
The briefing includes a `Situation` line describing what the player has, needs, and can do at that
time.

On death, the model receives the cause, death coordinates, home location, and a 600-second recovery
window, along with a warning that mobs may remain. It chooses whether to respawn; in autonomous mode
with no active task, the mod respawns after three seconds.

Logging never allows Baritone to dig down for trees: if no logs are loaded, the bot searches the
surface, and a log-mining command is stopped if it descends more than four blocks. New Baritone
commands cancel a running one first.

## Code-driven decisions

Several behaviors are handled in code rather than delegated to the model:

- **Survival:** Healthy players automatically defend against hostile mobs already in melee reach,
  using a hotbar sword or axe when available. Defense cancels Baritone movement and invalidates
  pending plans; the interrupted task resumes afterward. At 6 health or lower, when a creeper is
  within 8 blocks, or when an unreachable hostile targets the player from within 8 blocks,
  Actionable interrupts the current activity and flees 40 blocks directly away. Fleeing ends when
  the threat clears.
- **Dark areas:** Each second, Actionable rates the surroundings from light, sky visibility, depth,
  time, health, food, and gear. This appears in the debug panel and model context as `safety={...}`.
  Darkness is advice, not a reason to cancel tasks or retreat.
- **Mining safety:** Items are translated to the blocks that drop them (for example, `cobblestone`
  to `stone`). Mining is refused with `MINE_FAILED_NO_TOOL` when the required tool is missing.
  Without cave gear, deposits more than 30 blocks below the surface are refused with
  `MINE_FAILED_UNSAFE_SITE`. Light level is not checked.
- **Wood by biome:** The briefing reports logs that grow in the current biome, the nearest loaded
  tree biome and its direction when needed, or a suggestion to `travel`. The `lookup` action can
  return the biome/log table for wood, tree, log, or biome queries.
- **Automatic torches:** When holding torches with light level 4 or lower at the player's feet,
  Actionable places one in a reachable nearby location at least as dark as the player's cell.
- **Automatic hotbar:** Required blocks, tools, weapons, and food are moved to the hotbar as needed.
  Tools, weapons, and food use fixed slots; blocks use slots 4–7.
- **Goal validation:** Goals requiring missing tools or unsupported crafting are rejected. Goals are
  normalized to the resource they concern to avoid repeats. Rejected, repeated, or failed goals are
  replaced by a progression milestone; a milestone attempted twice recently is skipped before
  exploration.

There are no replanning or placement-retry caps, and a running `mine` is not cancelled merely
because the deposit is far away. A task still running after five minutes (6,000 ticks) is abandoned
and a new goal is chosen. Actionable remembers the last six achieved and six attempted goals.
Autonomous progression follows the full `progression.txt` path from wood to the Ender Dragon,
including house, storage, farm, and ranch stages. Darkness and night are cautions rather than
dangers; the model is told to prepare a weapon, light source, and bed.

### Autonomous combat styles

When autonomous mode is active, a weapon is held, and health is above 6, `MobFighter` uses per-mob
styles. Skeletons, strays, and bogged are rushed and attacked with jump-critical hits. Creepers get
one critical hit, then the bot retreats while the attack recharges (and when the fuse swells), before
moving back in. Other hostiles use the basic melee defense described in
[Actions and safety](actions-and-safety.md#basic-combat).

### Reflexive shelter

In autonomous mode, three or more melee mobs within 9 blocks trigger a four-block pillar using cheap
blocks. Two or more skeletons in line of sight at 9 or more blocks trigger a four-sided, two-block
high wall with a roof. Actionable opens a gap and resumes after the threat has been gone for about
four seconds, or after 60 seconds.
