package dev.actionable;

import java.util.OptionalInt;

/**
 * Decides how to get a requested block into the selected hotbar slot before placing it, and whether
 * that preparation actually took effect (EXEC-007). Pure decisions over indices; the caller reads the
 * inventory and issues the Minecraft calls.
 *
 * <p>Index domains are kept apart. A player inventory index is 0-8 for the hotbar and 9-35 for the
 * main inventory. An inventory-menu slot index addresses {@code InventoryMenu.slots}. A hotbar index
 * 0-8 is also the SWAP button.
 *
 * <p>Policy: a block that is only in the main inventory is swapped into the currently selected hotbar
 * slot. The item that was there moves to the block's former main-inventory slot, so later code cannot
 * assume a tool keeps its hotbar position. No empty hotbar slot is searched for.
 */
final class PlacementPreparation {
    static final int NONE = -1;
    static final int HOTBAR_SIZE = 9;
    static final int FIRST_MAIN_INVENTORY_INDEX = 9;
    static final int LAST_MAIN_INVENTORY_INDEX = 35;

    enum Status {
        /** The selected hotbar slot already holds the block. */
        ALREADY_SELECTED,
        /** Another hotbar slot holds the block; select it. */
        SELECT_HOTBAR,
        /** Only the main inventory holds the block; swap it into the selected hotbar slot. */
        SWAP_INTO_SELECTED,
        /** Preparation verified: the player is holding the block. */
        READY,
        BLOCK_NOT_IN_INVENTORY,
        INVALID_SOURCE_SLOT,
        INVALID_SELECTED_HOTBAR_SLOT,
        MENU_UNAVAILABLE,
        TRANSFER_NOT_APPLIED,
        SELECTED_ITEM_MISMATCH;

        boolean requiresSwap() {
            return this == SWAP_INTO_SELECTED;
        }

        boolean allowsPlacement() {
            return this == READY;
        }

        boolean isFailure() {
            return switch (this) {
                case ALREADY_SELECTED, SELECT_HOTBAR, SWAP_INTO_SELECTED, READY -> false;
                default -> true;
            };
        }
    }

    /**
     * A preparation decision. {@code selectedHotbarIndex} is the hotbar slot that must be selected for
     * placement and, for a swap, the SWAP button. The source fields are {@link #NONE} unless a swap is
     * required.
     */
    record Preparation(Status status, int selectedHotbarIndex, int sourceInventoryIndex, int sourceMenuSlot) {
    }

    private PlacementPreparation() {
    }

    static boolean isHotbarIndex(int hotbarIndex) {
        return hotbarIndex >= 0 && hotbarIndex < HOTBAR_SIZE;
    }

    static boolean isSupportedSourceInventoryIndex(int sourceInventoryIndex) {
        return sourceInventoryIndex >= FIRST_MAIN_INVENTORY_INDEX && sourceInventoryIndex <= LAST_MAIN_INVENTORY_INDEX;
    }

    /**
     * The inventory-menu slot that holds a main-inventory index. In 26.3 the menu places the result,
     * crafting and armor slots at 0-8 and then the main inventory, so index 9-35 is menu slot 9-35.
     */
    static OptionalInt menuSlotForInventoryIndex(int sourceInventoryIndex) {
        return isSupportedSourceInventoryIndex(sourceInventoryIndex)
                ? OptionalInt.of(sourceInventoryIndex)
                : OptionalInt.empty();
    }

    /**
     * Chooses the preparation from where the block was found. {@code hotbarIndexHoldingBlock} and
     * {@code sourceInventoryIndex} are the first hotbar and main-inventory indices holding the block,
     * or {@link #NONE}. A swap is allowed only while the player's own inventory menu is the open menu.
     */
    static Preparation prepare(int selectedHotbarIndex, boolean selectedHoldsBlock, int hotbarIndexHoldingBlock,
            int sourceInventoryIndex, boolean inventoryMenuOpen) {
        if (!isHotbarIndex(selectedHotbarIndex)) {
            return failed(Status.INVALID_SELECTED_HOTBAR_SLOT, selectedHotbarIndex);
        }
        if (selectedHoldsBlock) {
            return new Preparation(Status.ALREADY_SELECTED, selectedHotbarIndex, NONE, NONE);
        }
        if (isHotbarIndex(hotbarIndexHoldingBlock)) {
            return new Preparation(Status.SELECT_HOTBAR, hotbarIndexHoldingBlock, NONE, NONE);
        }
        if (sourceInventoryIndex == NONE) {
            return failed(Status.BLOCK_NOT_IN_INVENTORY, selectedHotbarIndex);
        }
        OptionalInt sourceMenuSlot = menuSlotForInventoryIndex(sourceInventoryIndex);
        if (sourceMenuSlot.isEmpty()) {
            return failed(Status.INVALID_SOURCE_SLOT, selectedHotbarIndex);
        }
        if (!inventoryMenuOpen) {
            return failed(Status.MENU_UNAVAILABLE, selectedHotbarIndex);
        }
        return new Preparation(Status.SWAP_INTO_SELECTED, selectedHotbarIndex, sourceInventoryIndex,
                sourceMenuSlot.getAsInt());
    }

    private static Preparation failed(Status status, int selectedHotbarIndex) {
        return new Preparation(status, selectedHotbarIndex, NONE, NONE);
    }

    /**
     * Checks the local inventory after preparation. Placement may proceed only when the prepared
     * hotbar slot holds the block, that slot is the selected one, and the main hand holds the block.
     * A failed preparation stays failed, so nothing can fall back to whatever was already held.
     */
    static Status verify(Preparation preparation, boolean preparedSlotHoldsBlock, boolean selectionMatches,
            boolean heldItemIsBlock) {
        if (preparation.status().isFailure()) {
            return preparation.status();
        }
        if (preparation.status().requiresSwap() && !preparedSlotHoldsBlock) {
            return Status.TRANSFER_NOT_APPLIED;
        }
        if (!preparedSlotHoldsBlock || !selectionMatches || !heldItemIsBlock) {
            return Status.SELECTED_ITEM_MISMATCH;
        }
        return Status.READY;
    }

    /** Result text for a failed preparation, in the existing BLOCK_PLACE_FAILED_CODE: detail form. */
    static String failureResult(Status status, String block) {
        return switch (status) {
            case BLOCK_NOT_IN_INVENTORY -> "BLOCK_PLACE_FAILED_MATERIAL_MISSING: " + block
                    + " is not in inventory; gather required materials before placing";
            case INVALID_SOURCE_SLOT -> "BLOCK_PLACE_FAILED_INVALID_SOURCE_SLOT: " + block
                    + " is in an inventory slot placement cannot take it from; placement was not attempted";
            case INVALID_SELECTED_HOTBAR_SLOT -> "BLOCK_PLACE_FAILED_INVALID_SELECTED_HOTBAR_SLOT: the selected"
                    + " hotbar index is outside 0-8; placement was not attempted";
            case MENU_UNAVAILABLE -> "BLOCK_PLACE_FAILED_MENU_UNAVAILABLE: " + block
                    + " is only in the main inventory and another screen is open; close it before placing";
            case TRANSFER_NOT_APPLIED -> "BLOCK_PLACE_FAILED_TRANSFER_NOT_APPLIED: " + block
                    + " did not reach the selected hotbar slot; placement was not attempted";
            case SELECTED_ITEM_MISMATCH -> "BLOCK_PLACE_FAILED_SELECTED_ITEM_MISMATCH: the held item is not "
                    + block + "; placement was not attempted";
            default -> throw new IllegalArgumentException("not a failure: " + status);
        };
    }

    /** Result text once the placement interaction was sent. It reports the request, not a placed block. */
    static String placementIssued(String block, String target) {
        return "placement request sent for " + block + " at " + target;
    }
}
