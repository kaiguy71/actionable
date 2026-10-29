package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.actionable.PlacementPreparation.Preparation;
import dev.actionable.PlacementPreparation.Status;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class PlacementPreparationTest {
    private static final int NONE = PlacementPreparation.NONE;
    private static final String BLOCK = "minecraft:crafting_table";

    /** The selected slot does not hold the block; it is only in main-inventory index 20. */
    private static Preparation swapFromMain(int selectedHotbarIndex) {
        return PlacementPreparation.prepare(selectedHotbarIndex, false, NONE, 20, true);
    }

    private static Preparation alreadySelected() {
        return PlacementPreparation.prepare(3, true, NONE, NONE, true);
    }

    // Inventory-index validation and menu-slot conversion

    @Test
    void firstSupportedMainInventoryIndexMapsCorrectly() {
        assertEquals(OptionalInt.of(9), PlacementPreparation.menuSlotForInventoryIndex(9));
    }

    @Test
    void lastSupportedMainInventoryIndexMapsCorrectly() {
        assertEquals(OptionalInt.of(35), PlacementPreparation.menuSlotForInventoryIndex(35));
    }

    @Test
    void mainInventoryIndexMapsToCorrectMenuSlot() {
        // InventoryMenu puts result, crafting and armor slots at 0-8, so main inventory index i is menu slot i.
        for (int sourceInventoryIndex : List.of(10, 17, 18, 26, 27, 34)) {
            assertEquals(OptionalInt.of(sourceInventoryIndex),
                    PlacementPreparation.menuSlotForInventoryIndex(sourceInventoryIndex));
        }
    }

    @Test
    void inventoryIndexBelowSupportedRangeIsRejected() {
        for (int inventoryIndex : List.of(8, 0, -1)) {
            assertEquals(OptionalInt.empty(), PlacementPreparation.menuSlotForInventoryIndex(inventoryIndex));
        }
    }

    @Test
    void inventoryIndexAboveSupportedRangeIsRejected() {
        for (int inventoryIndex : List.of(36, 40, 45)) {
            assertEquals(OptionalInt.empty(), PlacementPreparation.menuSlotForInventoryIndex(inventoryIndex));
        }
    }

    // Hotbar validation

    @Test
    void selectedHotbarIndexZeroIsAccepted() {
        assertTrue(PlacementPreparation.isHotbarIndex(0));
    }

    @Test
    void selectedHotbarIndexEightIsAccepted() {
        assertTrue(PlacementPreparation.isHotbarIndex(8));
    }

    @Test
    void negativeHotbarIndexIsRejected() {
        assertFalse(PlacementPreparation.isHotbarIndex(-1));
    }

    @Test
    void hotbarIndexNineIsRejected() {
        assertFalse(PlacementPreparation.isHotbarIndex(9));
    }

    // Preparation decisions

    @Test
    void alreadySelectedBlockRequiresNoSwap() {
        Preparation preparation = alreadySelected();
        assertEquals(Status.ALREADY_SELECTED, preparation.status());
        assertFalse(preparation.status().requiresSwap());
    }

    @Test
    void blockInAnotherHotbarSlotIsSelectedWithoutSwap() {
        Preparation preparation = PlacementPreparation.prepare(3, false, 6, 20, true);
        assertEquals(Status.SELECT_HOTBAR, preparation.status());
        assertEquals(6, preparation.selectedHotbarIndex());
        assertFalse(preparation.status().requiresSwap());
    }

    @Test
    void mainInventoryBlockRequiresSwap() {
        Preparation preparation = swapFromMain(3);
        assertEquals(Status.SWAP_INTO_SELECTED, preparation.status());
        assertTrue(preparation.status().requiresSwap());
    }

    @Test
    void unsupportedSourceDoesNotProduceSwapInstruction() {
        for (int sourceInventoryIndex : List.of(5, 36)) {
            Preparation preparation = PlacementPreparation.prepare(3, false, NONE, sourceInventoryIndex, true);
            assertEquals(Status.INVALID_SOURCE_SLOT, preparation.status());
            assertFalse(preparation.status().requiresSwap());
        }
    }

    @Test
    void invalidHotbarDoesNotProduceSwapInstruction() {
        for (int selectedHotbarIndex : List.of(-1, 9)) {
            Preparation preparation = PlacementPreparation.prepare(selectedHotbarIndex, false, NONE, 20, true);
            assertEquals(Status.INVALID_SELECTED_HOTBAR_SLOT, preparation.status());
            assertFalse(preparation.status().requiresSwap());
        }
    }

    @Test
    void otherOpenScreenAbortsInsteadOfReportingMissingMaterial() {
        Preparation preparation = PlacementPreparation.prepare(3, false, NONE, 20, false);
        assertEquals(Status.MENU_UNAVAILABLE, preparation.status());
        assertFalse(preparation.status().requiresSwap());
    }

    @Test
    void swapInstructionUsesSourceMenuSlot() {
        Preparation preparation = swapFromMain(3);
        assertEquals(20, preparation.sourceInventoryIndex());
        assertEquals(20, preparation.sourceMenuSlot());
    }

    @Test
    void swapInstructionUsesSelectedHotbarIndexAsButton() {
        assertEquals(3, swapFromMain(3).selectedHotbarIndex());
    }

    // Selected-slot policy

    @Test
    void swapTargetsCurrentlySelectedHotbarSlot() {
        for (int selectedHotbarIndex = 0; selectedHotbarIndex < PlacementPreparation.HOTBAR_SIZE; selectedHotbarIndex++) {
            assertEquals(selectedHotbarIndex, swapFromMain(selectedHotbarIndex).selectedHotbarIndex());
        }
    }

    @Test
    void swapDisplacesSelectedItemIntoSourceSlot() {
        // Models the compiled SWAP branch: the hotbar button slot takes the source stack, and the source
        // slot takes the stack that was in the hotbar slot. Later code cannot assume the old tool position.
        String[] inventory = new String[36];
        inventory[2] = "minecraft:iron_pickaxe";
        inventory[20] = BLOCK;
        Preparation preparation = swapFromMain(2);
        String sourceStack = inventory[preparation.sourceInventoryIndex()];
        inventory[preparation.sourceInventoryIndex()] = inventory[preparation.selectedHotbarIndex()];
        inventory[preparation.selectedHotbarIndex()] = sourceStack;
        assertEquals(BLOCK, inventory[2]);
        assertEquals("minecraft:iron_pickaxe", inventory[20]);
    }

    @Test
    void noEmptySlotSearchIsPerformed() {
        // Whatever other hotbar slots hold, a transfer always targets the selected slot.
        Preparation preparation = swapFromMain(7);
        assertEquals(7, preparation.selectedHotbarIndex());
        assertEquals(Status.SWAP_INTO_SELECTED, preparation.status());
    }

    // Production-path decisions (the Minecraft calls themselves are covered by PlacementWiringTest and RV-013A)

    @Test
    void blockAlreadySelectedSkipsSwap() {
        Preparation preparation = alreadySelected();
        assertFalse(preparation.status().requiresSwap());
        assertEquals(Status.READY, PlacementPreparation.verify(preparation, true, true, true));
    }

    @Test
    void missingRequestedBlockAbortsBeforeInteraction() {
        Preparation preparation = PlacementPreparation.prepare(3, false, NONE, NONE, true);
        assertEquals(Status.BLOCK_NOT_IN_INVENTORY, preparation.status());
        assertTrue(preparation.status().isFailure());
        assertFalse(PlacementPreparation.verify(preparation, true, true, true).allowsPlacement());
    }

    @Test
    void failedTransferAbortsPlacement() {
        assertEquals(Status.TRANSFER_NOT_APPLIED, PlacementPreparation.verify(swapFromMain(3), false, true, false));
    }

    @Test
    void wrongSelectedItemAbortsPlacement() {
        assertEquals(Status.SELECTED_ITEM_MISMATCH, PlacementPreparation.verify(swapFromMain(3), true, true, false));
        assertEquals(Status.SELECTED_ITEM_MISMATCH, PlacementPreparation.verify(swapFromMain(3), true, false, true));
        assertEquals(Status.SELECTED_ITEM_MISMATCH, PlacementPreparation.verify(alreadySelected(), false, true, true));
    }

    @Test
    void priorSelectedItemIsNeverUsedAsFallback() {
        List<Preparation> preparations = List.of(alreadySelected(), swapFromMain(3),
                PlacementPreparation.prepare(3, false, 6, NONE, true));
        boolean[] values = {false, true};
        for (Preparation preparation : preparations) {
            for (boolean slotHoldsBlock : values) {
                for (boolean selectionMatches : values) {
                    for (boolean heldItemIsBlock : values) {
                        Status verified = PlacementPreparation.verify(preparation, slotHoldsBlock, selectionMatches,
                                heldItemIsBlock);
                        assertEquals(slotHoldsBlock && selectionMatches && heldItemIsBlock, verified.allowsPlacement(),
                                preparation.status() + " " + slotHoldsBlock + " " + selectionMatches + " " + heldItemIsBlock);
                    }
                }
            }
        }
        for (Status failure : List.of(Status.BLOCK_NOT_IN_INVENTORY, Status.INVALID_SOURCE_SLOT,
                Status.INVALID_SELECTED_HOTBAR_SLOT, Status.MENU_UNAVAILABLE)) {
            Preparation failed = new Preparation(failure, 3, NONE, NONE);
            assertEquals(failure, PlacementPreparation.verify(failed, true, true, true));
        }
    }

    @Test
    void verifiedPreparationAllowsPlacementRequest() {
        assertEquals(Status.READY, PlacementPreparation.verify(swapFromMain(3), true, true, true));
        assertTrue(Status.READY.allowsPlacement());
    }

    @Test
    void issuedPlacementIsNotReportedAsConfirmed() {
        String issued = PlacementPreparation.placementIssued(BLOCK, "BlockPos{x=1, y=2, z=3}");
        assertEquals("placement request sent for minecraft:crafting_table at BlockPos{x=1, y=2, z=3}", issued);
        for (String claim : List.of("placed", "confirmed", "succeeded", "success")) {
            assertFalse(issued.replace("placement", "").contains(claim), claim);
        }
    }

    // Result wording

    @Test
    void failureResultsFollowExistingResultConvention() {
        for (Status failure : List.of(Status.BLOCK_NOT_IN_INVENTORY, Status.INVALID_SOURCE_SLOT,
                Status.INVALID_SELECTED_HOTBAR_SLOT, Status.MENU_UNAVAILABLE, Status.TRANSFER_NOT_APPLIED,
                Status.SELECTED_ITEM_MISMATCH)) {
            String result = PlacementPreparation.failureResult(failure, BLOCK);
            assertTrue(result.startsWith("BLOCK_PLACE_FAILED_"), result);
            assertTrue(result.indexOf(':') > 0, result);
        }
    }

    @Test
    void missingBlockKeepsExistingResultWording() {
        assertEquals("BLOCK_PLACE_FAILED_MATERIAL_MISSING: minecraft:crafting_table is not in inventory;"
                + " gather required materials before placing",
                PlacementPreparation.failureResult(Status.BLOCK_NOT_IN_INVENTORY, BLOCK));
    }
}
