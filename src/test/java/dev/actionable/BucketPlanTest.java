package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BucketPlanTest {
    @Test
    void fillingAnExistingBucketNeverGeneratesAnIronMiningOrCraftingPlan() {
        assertEquals(List.of("fill water bucket"), ProgressPlanner.stepsForGoal("fill water bucket",
                Map.of("minecraft:bucket", 1), false, "minecraft:oak_log"));
        assertEquals(BucketPlan.Mode.DONE, BucketPlan.choose(true, false, List.of()).mode());
        assertEquals(BucketPlan.Mode.NEED_BUCKET, BucketPlan.choose(false, false, List.of()).mode());
    }
    @Test
    void fillsVisibleReachableSourceOrWalksToItsDryBank() {
        var source = new BucketPlan.Source(10, 62, 20, 11, 63, 20, 3, true, true, true);
        assertEquals(BucketPlan.Mode.FILL, BucketPlan.choose(false, true, List.of(source)).mode());
        var distant = new BucketPlan.Source(10, 62, 20, 11, 63, 20, 9, false, false, true);
        var decision = BucketPlan.choose(false, true, List.of(distant));
        assertEquals(BucketPlan.Mode.WALK, decision.mode());
        assertEquals(11, decision.source().standX());
    }
    @Test
    void neverWalksIntoAnUnreachableWaterCellWithoutADryBank() {
        var blocked = new BucketPlan.Source(10, 62, 20, 0, 0, 0, 1, true, false, false);
        assertEquals(BucketPlan.Mode.EXPLORE, BucketPlan.choose(false, true, List.of(blocked)).mode());
    }
}
