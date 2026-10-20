package dev.actionable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;

/** Reads the facts the pure safety, mining and progression rules need from the loaded world. */
final class WorldProbe {
    private static final int TABLE_SEARCH_RADIUS = 24;

    private WorldProbe() {
    }

    static Map<String, Integer> inventory(Player player) {
        Map<String, Integer> items = new TreeMap<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) {
                String item = java.util.Objects.requireNonNull(BuiltInRegistries.ITEM.getKey(stack.getItem())).toString();
                items.merge(item, stack.getCount(), Integer::sum);
            }
        }
        return items;
    }

    /** Surface height above a column, ignoring leaves so tree canopies do not count as a roof. */
    static int surfaceY(ClientLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** The house corner, set by the client, so safety and synopses know home is not a cave. */
    static volatile int[] home;

    static SafetyAssessment.Result safety(Minecraft client) {
        LocalPlayer player = java.util.Objects.requireNonNull(client.player);
        ClientLevel level = java.util.Objects.requireNonNull(client.level);
        BlockPos feet = player.blockPosition();
        int light = Math.max(level.getMaxLocalRawBrightness(feet), level.getMaxLocalRawBrightness(feet.above()));
        int hostiles = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Monster monster && monster.isAlive() && monster.distanceToSqr(player) <= 256) {
                hostiles++;
            }
        }
        // Dimensions without sky light (the Nether) have a bedrock roof, so depth below it means nothing.
        // Inside the house the roof hides the sky; that is shelter, not being underground.
        boolean sheltered = HouseDesign.nearHouse(home, feet.getX(), feet.getY(), feet.getZ()) || (home != null
                && StairMine.onStairs(feet.getX() - home[0], feet.getY() - home[1], feet.getZ() - home[2]));
        int depth = level.dimensionType().hasSkyLight() && !sheltered
                ? Math.max(0, surfaceY(level, feet.getX(), feet.getZ()) - feet.getY()) : 0;
        return SafetyAssessment.assess(new SafetyAssessment.Inputs(light, level.canSeeSky(feet.above()),
                depth, level.isDarkOutside(),
                Math.round(player.getHealth()), player.getFoodData().getFoodLevel(), player.getArmorValue(),
                hostiles, inventory(player)));
    }

    /** Candidates checked for a dry standing spot; enough for site choice without scanning every block. */
    private static final int MAX_SITE_CHECKS = 400;
    private static final int MAX_SITES = 64;

    /**
     * Loaded blocks of the given type with a face open to air that can be reached from dry land, nearest
     * first. Light, sky and depth are measured at the open face; the stand cell is a dry spot within
     * reach, so the player never mines from water (it bobbed in a lake trying to reach a shoreline log).
     */
    static List<MiningPolicy.Site> exposedSites(ClientLevel level, BlockPos origin, Block block, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!level.hasChunk((origin.getX() + dx) >> 4, (origin.getZ() + dz) >> 4)) {
                    continue;
                }
                for (int dy = -radius / 2; dy <= radius / 2; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.getBlockState(cursor).is(block)) {
                        candidates.add(cursor.immutable());
                    }
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        List<MiningPolicy.Site> sites = new ArrayList<>();
        int checked = 0;
        for (BlockPos pos : candidates) {
            if (sites.size() >= MAX_SITES || checked++ >= MAX_SITE_CHECKS) {
                break;
            }
            BlockPos open = openNeighbour(level, pos);
            BlockPos stand = open == null ? null : dryStandNear(level, pos);
            if (stand == null) {
                continue;
            }
            int surface = surfaceY(level, open.getX(), open.getZ());
            sites.add(new MiningPolicy.Site(pos.getX(), pos.getY(), pos.getZ(),
                    stand.getX(), stand.getY(), stand.getZ(), level.getMaxLocalRawBrightness(open),
                    level.canSeeSky(open), Math.max(0, surface - open.getY()), Math.sqrt(pos.distSqr(origin))));
        }
        return sites;
    }

    /**
     * The snag holding the player in place: a vine or similar in the player's own cells first (that is
     * what makes them bob), then one beside the feet or head. Null when there is none.
     */
    static BlockPos snagNear(ClientLevel level, BlockPos feet) {
        List<BlockPos> cells = new ArrayList<>(List.of(feet, feet.above(), feet.above(2)));
        for (Direction side : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            cells.add(feet.relative(side));
            cells.add(feet.above().relative(side));
        }
        for (BlockPos cell : cells) {
            String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(cell).getBlock()).toString();
            if (Recovery.isSnag(id)) {
                return cell;
            }
        }
        return null;
    }

    /** True when a player can stand in this cell on dry, solid ground with nothing in the way. */
    static boolean dryStandable(ClientLevel level, BlockPos feet) {
        BlockPos ground = feet.below();
        var groundState = level.getBlockState(ground);
        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(feet.above());
        return groundState.getFluidState().isEmpty() && groundState.isFaceSturdy(level, ground, Direction.UP)
                && feetState.isAir() && headState.isAir();
    }

    /** Nearest dry standing cell from which {@code target} is within reach, or null if only water reaches it. */
    static BlockPos dryStandNear(ClientLevel level, BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos feet : BlockPos.betweenClosed(target.offset(-3, -5, -3), target.offset(3, 1, 3))) {
            if (!MiningPolicy.inReach(feet.getX(), feet.getY(), feet.getZ(), target.getX(), target.getY(), target.getZ())
                    || !dryStandable(level, feet)) {
                continue;
            }
            double distance = feet.distSqr(target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = feet.immutable();
            }
        }
        return best;
    }

    /** A goto command to a free standing cell within reach of the target, never the target block itself. */
    static String gotoBeside(ClientLevel level, BlockPos target) {
        BlockPos stand = dryStandNear(level, target);
        if (stand == null) {
            stand = target.above();
        }
        return "goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ();
    }

    private static BlockPos openNeighbour(ClientLevel level, BlockPos pos) {
        // Prefer the cell above, where a player would usually stand, then the sides.
        for (Direction direction : new Direction[] {Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST, Direction.DOWN}) {
            BlockPos neighbour = pos.relative(direction);
            if (level.getBlockState(neighbour).isAir()) {
                return neighbour;
            }
        }
        return null;
    }

    /** Free cells on sturdy ground one or two blocks around the player, nearest ring first. */
    static List<BlockPos> placementCandidates(ClientLevel level, BlockPos origin) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos target = origin.offset(dx, 0, dz);
                    BlockPos ground = target.below();
                    if (level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
                            && level.getBlockState(target).canBeReplaced()
                            && level.getBlockState(target.above()).canBeReplaced()) {
                        candidates.add(target);
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Cells where a block can go near the player, nearest first: outward on the player's level, then
     * upward, then downward. A cell qualifies when it is empty or replaceable (never plain water), free
     * of the player and of any mob, and touches a solid, dry anchor face within reach.
     */
    static List<BlockPos> placementSpots(ClientLevel level, Player player) {
        BlockPos feet = player.blockPosition();
        net.minecraft.world.phys.Vec3 eyes = player.getEyePosition();
        List<BlockPos> air = new ArrayList<>();
        List<BlockPos> soft = new ArrayList<>();
        for (int dy : new int[] {0, 1, 2, 3, -1, -2, -3}) {
            for (int radius = 0; radius <= 3; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos cell = feet.offset(dx, dy, dz);
                        if (!placeableCell(level, player, cell, eyes)) {
                            continue;
                        }
                        (level.getBlockState(cell).isAir() ? air : soft).add(cell);
                    }
                }
            }
        }
        air.addAll(soft);
        return air;
    }

    static boolean placeableCell(ClientLevel level, Player player, BlockPos cell,
            net.minecraft.world.phys.Vec3 eyes) {
        var state = level.getBlockState(cell);
        if (!state.isAir() && !(state.canBeReplaced() && state.getFluidState().isEmpty())) {
            return false;
        }
        var above = level.getBlockState(cell.above());
        if (!above.isAir() && !above.canBeReplaced()) {
            return false;
        }
        var box = new net.minecraft.world.phys.AABB(cell);
        if (player.getBoundingBox().intersects(box)
                || !level.getEntities((Entity) null, box, entity -> true).isEmpty()) {
            return false;
        }
        for (Direction side : Direction.values()) {
            BlockPos anchor = cell.relative(side);
            var anchorState = level.getBlockState(anchor);
            if (!anchorState.getFluidState().isEmpty() || !anchorState.isFaceSturdy(level, anchor, side.getOpposite())) {
                continue;
            }
            net.minecraft.world.phys.Vec3 face = net.minecraft.world.phys.Vec3.atCenterOf(anchor).add(
                    side.getOpposite().getStepX() * 0.5, side.getOpposite().getStepY() * 0.5,
                    side.getOpposite().getStepZ() * 0.5);
            if (eyes.distanceToSqr(face) <= 20.25) {
                return true;
            }
        }
        return false;
    }

    /** Nearest bed block within {@code radius}, or null. */
    static BlockPos nearestBed(ClientLevel level, BlockPos origin, int radius) {
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -8, -radius), origin.offset(radius, 8, radius))) {
            if (level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                double distance = pos.distSqr(origin);
                if (distance < best) {
                    best = distance;
                    nearest = pos.immutable();
                }
            }
        }
        return nearest;
    }

    /**
     * Nearest dry cell to stand on: solid, non-fluid ground with two free, water-free cells above.
     * Used to get out of water before the model thinks, because the player sank while waiting.
     */
    static BlockPos nearestLand(ClientLevel level, BlockPos origin, int radius) {
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -4, -radius), origin.offset(radius, 4, radius))) {
            BlockPos ground = pos.below();
            var groundState = level.getBlockState(ground);
            if (!groundState.getFluidState().isEmpty() || !groundState.isFaceSturdy(level, ground, Direction.UP)) {
                continue;
            }
            var feet = level.getBlockState(pos);
            var head = level.getBlockState(pos.above());
            if (!feet.isAir() || !head.isAir()) {
                continue;
            }
            double distance = pos.distSqr(origin);
            if (distance < best) {
                best = distance;
                nearest = pos.immutable();
            }
        }
        return nearest;
    }

    /**
     * The next house cell needing a plank: a floor cell that is a hole or water, then a walkable cell
     * holding water. Null when the floor is sound and dry.
     */
    static BlockPos houseFloorProblem(ClientLevel level, int[] origin) {
        for (int[] cell : HouseDesign.floorCells()) {
            BlockPos pos = new BlockPos(origin[0] + cell[0], origin[1] + cell[1], origin[2] + cell[2]);
            var state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || !state.isFaceSturdy(level, pos, Direction.UP)) {
                return pos;
            }
        }
        for (int[] cell : HouseDesign.dryCells()) {
            BlockPos pos = new BlockPos(origin[0] + cell[0], origin[1] + cell[1], origin[2] + cell[2]);
            if (!level.getBlockState(pos).getFluidState().isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    static DoorRule.Probe doorProbe(ClientLevel level) {
        return (x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            var state = level.getBlockState(pos);
            if (state.isAir()) {
                return DoorRule.Cell.AIR;
            }
            if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                return DoorRule.Cell.DOOR;
            }
            return state.isCollisionShapeFullBlock(level, pos) ? DoorRule.Cell.SOLID : DoorRule.Cell.OTHER;
        };
    }

    /**
     * Doors in or around the house other than the one in the doorway, lower halves only. These were
     * placed by mistake and are taken back.
     */
    static List<BlockPos> strayDoors(ClientLevel level, int[] home) {
        List<BlockPos> doors = new ArrayList<>();
        BlockPos doorway = new BlockPos(home[0] + HouseDesign.DOOR_CELL[0], home[1] + HouseDesign.DOOR_CELL[1],
                home[2] + HouseDesign.DOOR_CELL[2]);
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(home[0] - 2, home[1] - 1, home[2] - 3),
                new BlockPos(home[0] + HouseDesign.WIDTH + 1, home[1] + HouseDesign.HEIGHT, home[2] + HouseDesign.LENGTH + 1))) {
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                    && state.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
                            == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                    && !pos.equals(doorway)) {
                doors.add(pos.immutable());
            }
        }
        return doors;
    }

    /** True when any interior cell of the house holds a block matching {@code test}. */
    static boolean houseHas(ClientLevel level, int[] origin,
            java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> test) {
        for (int y = 0; y < HouseDesign.HEIGHT - 1; y++) {
            for (int z = 1; z < HouseDesign.LENGTH - 1; z++) {
                for (int x = 1; x < HouseDesign.WIDTH - 1; x++) {
                    if (test.test(level.getBlockState(new BlockPos(origin[0] + x, origin[1] + y, origin[2] + z)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** How much of the starter house at {@code origin} is built, from 0 to 1. */
    static double houseCompletion(ClientLevel level, int[] origin) {
        if (!level.hasChunk(origin[0] >> 4, origin[2] >> 4)) {
            return 0;
        }
        return HouseDesign.completion((x, y, z) -> {
            var state = level.getBlockState(new BlockPos(origin[0] + x, origin[1] + y, origin[2] + z));
            return !state.isAir() && state.getFluidState().isEmpty();
        });
    }

    /** The planks type the player holds most of, or the planks of the preferred log. */
    static String mostHeldPlanks(Map<String, Integer> inventory, String preferredLog) {
        return inventory.entrySet().stream().filter(entry -> TechTree.matches(TechTree.PLANKS, entry.getKey()))
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(TechTree.planksFor(preferredLog));
    }

    static BlockPos nearestCraftingTable(ClientLevel level, BlockPos origin) {
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        Block table = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(TechTree.CRAFTING_TABLE));
        // Tall vertical range: Baritone often drops the player well below a table placed earlier.
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-TABLE_SEARCH_RADIUS, -16, -TABLE_SEARCH_RADIUS),
                origin.offset(TABLE_SEARCH_RADIUS, 16, TABLE_SEARCH_RADIUS))) {
            if (level.getBlockState(pos).is(table)) {
                double distance = pos.distSqr(origin);
                if (distance < best) {
                    best = distance;
                    nearest = pos.immutable();
                }
            }
        }
        return nearest;
    }

    /** True when a crafting table is close enough to use without walking. */
    static boolean tableInReach(Player player, BlockPos table) {
        return table != null && player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(table)) <= 20.25;
    }

    /** The log type the player holds most of, else the nearest log type seen, else oak. */
    static String preferredLog(Map<String, Integer> inventory, String nearbyResources) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            if (TechTree.matches(TechTree.LOGS, entry.getKey()) && entry.getValue() > bestCount
                    && !entry.getKey().contains("stripped_")) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (best != null) {
            return best;
        }
        java.util.regex.Matcher log = java.util.regex.Pattern.compile("(minecraft:[a-z_]+_(?:log|stem))")
                .matcher(nearbyResources);
        return log.find() ? log.group(1) : "minecraft:oak_log";
    }
}
