package dev.actionable;

import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crafts tech-tree items by clicking ingredients into the inventory's 2x2 grid or an opened crafting
 * table, one craft per cycle. Uses the fixed patterns in {@link TechTree} rather than the recipe book,
 * so it works before recipes are unlocked.
 */
final class Crafter {
    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final int RESULT_TIMEOUT_TICKS = 30;

    private enum Phase { OPEN_TABLE, FILL, TAKE }

    private final String item;
    private final int target;
    private final TechTree.Recipe recipe;
    private final BlockPos table;
    private final int maxCrafts;
    private Phase phase;
    private long phaseStartedAt;
    private boolean tableClicked;
    private int crafted;

    private Crafter(String item, int target, TechTree.Recipe recipe, BlockPos table, int maxCrafts, long tick) {
        this.item = item;
        this.target = target;
        this.recipe = recipe;
        this.table = table;
        this.maxCrafts = maxCrafts;
        this.phase = table == null ? Phase.FILL : Phase.OPEN_TABLE;
        this.phaseStartedAt = tick;
    }

    /** Either a crafter to tick, or a final result when crafting finished or failed immediately. */
    record Start(Crafter crafter, String result) {
    }

    static Start start(Minecraft client, String item, int target, long tick) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            return new Start(null, "CRAFT_FAILED_NO_WORLD: player world is unavailable");
        }
        var recipe = TechTree.recipeFor(item);
        if (recipe.isEmpty()) {
            return new Start(null, "CRAFT_FAILED_UNSUPPORTED: " + item + " is not craftable here; craftable: "
                    + TechTree.craftableItems());
        }
        Map<String, Integer> inventory = WorldProbe.inventory(player);
        int held = TechTree.held(inventory, item);
        if (held >= target) {
            return new Start(null, "CRAFTED: already holding " + held + " " + item);
        }
        if (item.equals(TechTree.CRAFTING_TABLE)) {
            // Tables are reused: the model kept crafting new ones instead of picking up the old one.
            BlockPos placed = WorldProbe.nearestCraftingTable(client.level, player.blockPosition());
            if (placed != null) {
                return new Start(null, "CRAFT_FAILED_TABLE_EXISTS: a crafting table is already placed at "
                        + placed.getX() + "," + placed.getY() + "," + placed.getZ() + ". Reuse it: craft there,"
                        + " or mine 1 minecraft:crafting_table to pick it up and place it where you need it.");
            }
        }
        for (Map.Entry<String, Integer> input : recipe.get().inputs().entrySet()) {
            if (TechTree.count(inventory, input.getKey()) < input.getValue()) {
                return new Start(null, "CRAFT_FAILED_MISSING_INGREDIENTS: one " + item + " needs "
                        + recipe.get().inputs() + " but inventory holds " + inventory
                        + "; gather or craft the missing ingredient first");
            }
        }
        BlockPos table = null;
        if (recipe.get().needsTable()) {
            table = WorldProbe.nearestCraftingTable(client.level, player.blockPosition());
            if (table == null) {
                return new Start(null, "CRAFT_FAILED_NO_TABLE: " + item + " needs a 3x3 crafting table. "
                        + (inventory.getOrDefault(TechTree.CRAFTING_TABLE, 0) > 0
                        ? "Place the minecraft:crafting_table from inventory first."
                        : "Craft minecraft:crafting_table (4 planks) and place it first."));
            }
            if (!WorldProbe.tableInReach(player, table)) {
                return new Start(null, "CRAFT_FAILED_TABLE_OUT_OF_REACH: crafting table at " + table.getX() + ","
                        + table.getY() + "," + table.getZ() + "; goto " + table.getX() + " " + (table.getY() + 1)
                        + " " + table.getZ() + " first, or mine 1 minecraft:crafting_table to pick it up"
                        + " and place it nearby");
            }
        }
        int crafts = (target - held + recipe.get().yield() - 1) / recipe.get().yield();
        return new Start(new Crafter(item, target, recipe.get(), table, crafts, tick), null);
    }

    String item() {
        return item;
    }

    /** Advances crafting by one tick. Returns null while still working, otherwise the final result. */
    String tick(Minecraft client, long tick) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return "CRAFT_FAILED_NO_WORLD: player world became unavailable";
        }
        AbstractContainerMenu menu = player.containerMenu;
        return switch (phase) {
            case OPEN_TABLE -> {
                if (menu instanceof CraftingMenu) {
                    enter(Phase.FILL, tick);
                    yield null;
                }
                if (!tableClicked) {
                    tableClicked = true;
                    Vec3 face = Vec3.atCenterOf(table).add(0, 0.5, 0);
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, face);
                    client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(face, Direction.UP, table, false));
                } else if (tick - phaseStartedAt > OPEN_TIMEOUT_TICKS) {
                    yield "CRAFT_FAILED_TABLE_DID_NOT_OPEN: clicking the crafting table at " + table + " opened nothing";
                }
                yield null;
            }
            case FILL -> {
                if (menu != player.inventoryMenu && !(menu instanceof CraftingMenu)) {
                    yield finish(client, "CRAFT_FAILED_OTHER_SCREEN_OPEN: close the open container first");
                }
                String missing = fill(client, menu);
                if (missing != null) {
                    yield finish(client, "CRAFT_FAILED_MISSING_INGREDIENTS: ran out of " + missing + " after "
                            + crafted + " crafts of " + item);
                }
                enter(Phase.TAKE, tick);
                yield null;
            }
            case TAKE -> {
                ItemStack result = menu.getSlot(0).getItem();
                if (!result.isEmpty() && TechTree.groupOf(id(result)).equals(TechTree.groupOf(item))) {
                    click(client, menu, 0, 0, ContainerInput.QUICK_MOVE);
                    crafted++;
                    int held = TechTree.held(WorldProbe.inventory(player), item);
                    if (held >= target || crafted >= maxCrafts) {
                        yield finish(client, "CRAFTED: made " + crafted + " craft(s); now holding " + held + " " + item);
                    }
                    enter(Phase.FILL, tick);
                } else if (tick - phaseStartedAt > RESULT_TIMEOUT_TICKS) {
                    yield finish(client, "CRAFT_FAILED_NO_RESULT: the grid did not produce " + item
                            + (result.isEmpty() ? "" : " (produced " + id(result) + ")"));
                }
                yield null;
            }
        };
    }

    /** Stops crafting, returning grid items to the inventory and closing a crafting table. */
    String cancel(Minecraft client) {
        return finish(client, "CRAFT_CANCELLED after " + crafted + " craft(s) of " + item);
    }

    private void enter(Phase next, long tick) {
        phase = next;
        phaseStartedAt = tick;
    }

    /** Places one ingredient per pattern cell. Returns the missing ingredient key, or null on success. */
    private String fill(Minecraft client, AbstractContainerMenu menu) {
        LocalPlayer player = java.util.Objects.requireNonNull(client.player);
        int gridWidth = menu instanceof CraftingMenu ? 3 : 2;
        clearGrid(client, menu, gridWidth);
        for (Map.Entry<Integer, String> cell : recipe.layout(gridWidth).entrySet()) {
            int source = sourceSlot(player, menu, cell.getValue());
            if (source < 0) {
                clearGrid(client, menu, gridWidth);
                return cell.getValue();
            }
            // Pick up the stack, drop one into the cell, put the rest back.
            click(client, menu, source, 0, ContainerInput.PICKUP);
            click(client, menu, cell.getKey(), 1, ContainerInput.PICKUP);
            click(client, menu, source, 0, ContainerInput.PICKUP);
        }
        return null;
    }

    private int sourceSlot(LocalPlayer player, AbstractContainerMenu menu, String key) {
        // Planks crafted from a matching log keep the requested wood type.
        String preferred = TechTree.matches(TechTree.PLANKS, item) ? TechTree.logFor(item) : null;
        int fallback = -1;
        for (Slot slot : menu.slots) {
            if (slot.container != player.getInventory() || slot.getItem().isEmpty()) {
                continue;
            }
            String id = id(slot.getItem());
            if (!TechTree.matches(key, id)) {
                continue;
            }
            if (preferred == null || id.equals(preferred)) {
                return slot.index;
            }
            if (fallback < 0) {
                fallback = slot.index;
            }
        }
        return fallback;
    }

    private static void clearGrid(Minecraft client, AbstractContainerMenu menu, int gridWidth) {
        for (int slot = 1; slot <= gridWidth * gridWidth; slot++) {
            if (!menu.getSlot(slot).getItem().isEmpty()) {
                click(client, menu, slot, 0, ContainerInput.QUICK_MOVE);
            }
        }
    }

    private String finish(Minecraft client, String result) {
        LocalPlayer player = client.player;
        if (player != null && client.gameMode != null) {
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == player.inventoryMenu || menu instanceof CraftingMenu) {
                clearGrid(client, menu, menu instanceof CraftingMenu ? 3 : 2);
            }
            if (menu instanceof CraftingMenu) {
                player.closeContainer();
            }
        }
        return result;
    }

    private static void click(Minecraft client, AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        java.util.Objects.requireNonNull(client.gameMode).handleContainerInput(
                menu.containerId, slot, button, input, java.util.Objects.requireNonNull(client.player));
    }

    private static String id(ItemStack stack) {
        return java.util.Objects.requireNonNull(BuiltInRegistries.ITEM.getKey(stack.getItem())).toString();
    }
}
