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
        int depth = level.dimensionType().hasSkyLight()
                ? Math.max(0, surfaceY(level, feet.getX(), feet.getZ()) - feet.getY()) : 0;
        return SafetyAssessment.assess(new SafetyAssessment.Inputs(light, level.canSeeSky(feet.above()),
                depth, level.isDarkOutside(),
                Math.round(player.getHealth()), player.getFoodData().getFoodLevel(), player.getArmorValue(),
                hostiles, inventory(player)));
    }

    /**
     * Loaded blocks of the given type with at least one face open to air, nearest first. Light, sky and
     * depth are measured at the open neighbour, where the player would stand to mine it.
     */
    static List<MiningPolicy.Site> exposedSites(ClientLevel level, BlockPos origin, Block block, int radius) {
        List<MiningPolicy.Site> sites = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!level.hasChunk((origin.getX() + dx) >> 4, (origin.getZ() + dz) >> 4)) {
                    continue;
                }
                for (int dy = -radius / 2; dy <= radius / 2; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.getBlockState(cursor).is(block)) {
                        continue;
                    }
                    BlockPos open = openNeighbour(level, cursor.immutable());
                    if (open == null) {
                        continue;
                    }
                    int surface = surfaceY(level, open.getX(), open.getZ());
                    sites.add(new MiningPolicy.Site(cursor.getX(), cursor.getY(), cursor.getZ(),
                            open.getX(), open.getY(), open.getZ(), level.getMaxLocalRawBrightness(open),
                            level.canSeeSky(open), Math.max(0, surface - open.getY()),
                            Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz)));
                }
            }
        }
        sites.sort(java.util.Comparator.comparingDouble(MiningPolicy.Site::distance));
        return sites;
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

    static BlockPos nearestCraftingTable(ClientLevel level, BlockPos origin) {
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        Block table = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(TechTree.CRAFTING_TABLE));
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-TABLE_SEARCH_RADIUS, -8, -TABLE_SEARCH_RADIUS),
                origin.offset(TABLE_SEARCH_RADIUS, 8, TABLE_SEARCH_RADIUS))) {
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
