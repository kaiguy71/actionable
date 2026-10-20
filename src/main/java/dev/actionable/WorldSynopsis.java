package dev.actionable;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

final class WorldSynopsis {
    private static final int BIOME_SCAN_RADIUS = 128;
    private static final int BIOME_SCAN_STEP = 16;

    private WorldSynopsis() {
    }

    static String capture(Minecraft client) {
        LocalPlayer player = java.util.Objects.requireNonNull(client.player, "A player is required for a world snapshot");
        ClientLevel level = java.util.Objects.requireNonNull(client.level, "A loaded world is required for a world snapshot");
        StringBuilder synopsis = new StringBuilder(512);
        BlockPos playerPos = player.blockPosition();
        synopsis.append("dimension=").append(level.dimension().identifier())
                .append("; player_block_position_xyz=")
                .append(playerPos.getX()).append(',').append(playerPos.getY()).append(',').append(playerPos.getZ())
                .append("; coordinate_axes=+X:east,+Y:up,+Z:south;yaw_0:south,yaw_90:west,yaw_-90:east")
                .append("; player_position_xyz=")
                .append(String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f",
                        player.getX(), player.getY(), player.getZ()))
                .append("; facing_yaw_degrees=").append(String.format(java.util.Locale.ROOT, "%.1f", player.getYRot()))
                .append("; facing_pitch_degrees=").append(String.format(java.util.Locale.ROOT, "%.1f", player.getXRot()))
                .append("; aimed_at=").append(aimedAt(client.hitResult, level, player))
                .append("; current_biome=").append(biomeId(level.getBiome(playerPos)))
                .append("; nearby_loaded_biomes_sampled=").append(nearbyBiomes(level, playerPos, player.getYRot()))
                .append("; player_cell=").append(blockId(level.getBlockState(playerPos).getBlock()))
                .append("; block_below_player=").append(blockId(level.getBlockState(playerPos.below()).getBlock()))
                .append("; nearby_placement_candidates=").append(placementSurface(level, playerPos))
                .append("; nearby_resource_hints=").append(nearbyResources(level, playerPos))
                .append("; nearby_fluid_sources=").append(fluidSources(level, playerPos))
                .append("; health=").append(Math.round(player.getHealth()))
                .append("; food=").append(player.getFoodData().getFoodLevel())
                .append("; air=").append(player.getAirSupply()).append('/').append(player.getMaxAirSupply())
                .append("; submerged=").append(player.isUnderWater())
                .append("; armor=").append(player.getArmorValue())
                .append("; ").append(WorldProbe.safety(client).describe())
                .append("; nearest_crafting_table=").append(nearestTable(level, playerPos))
                .append("; house_structure=").append(WorldProbe.describeHouse(level))
                .append("; ").append(InventoryAccess.describe(player))
                .append("; inventory=").append(inventorySlots(player))
                .append("; totals=").append(topEntries(WorldProbe.inventory(player), 24));

        Map<String, Integer> nearbyBlocks = new TreeMap<>();
        BlockPos origin = playerPos;
        for (int x = -4; x <= 4; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -4; z <= 4; z++) {
                    String id = BuiltInRegistries.BLOCK.getKey(
                            level.getBlockState(origin.offset(x, y, z)).getBlock()).toString();
                    nearbyBlocks.put(id, nearbyBlocks.getOrDefault(id, 0) + 1);
                }
            }
        }
        synopsis.append("; sampled_blocks=").append(topEntries(nearbyBlocks, 10));

        StringBuilder threats = new StringBuilder();
        int threatCount = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity monster && ThreatProbe.hostile(client, entity)
                    && monster.distanceToSqr(player) <= 256 && threatCount < 6) {
                if (threatCount++ > 0) {
                    threats.append(',');
                }
                threats.append(BuiltInRegistries.ENTITY_TYPE.getKey(monster.getType()))
                        .append("#entity_id=").append(monster.getId())
                        .append('@').append(monster.blockPosition().getX()).append(',')
                        .append(monster.blockPosition().getY()).append(',')
                        .append(monster.blockPosition().getZ())
                        .append("(d=").append(Math.round(monster.distanceTo(player)))
                        .append(",health=").append(Math.round(monster.getHealth()))
                        .append(",melee_reachable=").append(MeleeCombat.reachable(client, monster))
                        .append(ThreatProbe.targeting(client, monster) ? ",targeting_player" : "").append(')');
            }
        }
        synopsis.append("; nearby_hostile_mobs=").append(threatCount == 0 ? "none" : threats);
        return synopsis.toString();
    }

    private static String aimedAt(HitResult hit, ClientLevel level, LocalPlayer player) {
        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            return block + '@' + pos.getX() + ',' + pos.getY() + ',' + pos.getZ()
                    + "(d=" + Math.round(hit.getLocation().distanceTo(player.getEyePosition())) + ')';
        }
        return hit == null ? "none" : hit.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String nearbyBiomes(ClientLevel level, BlockPos origin, float playerYaw) {
        Map<String, BiomeSample> nearestByBiome = new TreeMap<>();
        for (int dx = -BIOME_SCAN_RADIUS; dx <= BIOME_SCAN_RADIUS; dx += BIOME_SCAN_STEP) {
            for (int dz = -BIOME_SCAN_RADIUS; dz <= BIOME_SCAN_RADIUS; dz += BIOME_SCAN_STEP) {
                BlockPos sample = origin.offset(dx, 0, dz);
                if (!level.hasChunk(sample.getX() >> 4, sample.getZ() >> 4)) {
                    continue;
                }
                String id = biomeId(level.getBiome(sample));
                double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
                BiomeSample previous = nearestByBiome.get(id);
                if (previous == null || distance < previous.distance()) {
                    nearestByBiome.put(id, new BiomeSample(dx, dz, distance));
                }
            }
        }

        return nearestByBiome.entrySet().stream()
                .sorted((first, second) ->
                        Double.compare(first.getValue().distance(), second.getValue().distance()))
                .limit(8)
                .map(entry -> {
                    BiomeSample sample = entry.getValue();
                    double bearing = Math.toDegrees(Math.atan2(-sample.dx(), sample.dz()));
                    double relativeAngle = wrapDegrees(bearing - playerYaw);
                    return entry.getKey() + "(distance=" + Math.round(sample.distance())
                            + "blocks,vector=" + sample.dx() + ",0," + sample.dz()
                            + ",angle_from_facing="
                            + String.format(java.util.Locale.ROOT, "%.1f", relativeAngle) + "deg)";
                })
                .toList()
                .toString();
    }

    private static String biomeId(net.minecraft.core.Holder<Biome> biome) {
        Optional<ResourceKey<Biome>> key = biome.unwrapKey();
        return key.map(resourceKey -> resourceKey.identifier().toString()).orElse("unregistered");
    }

    private static String placementSurface(ClientLevel level, BlockPos origin) {
        StringBuilder candidates = new StringBuilder("[");
        for (BlockPos target : WorldProbe.placementCandidates(level, origin).stream().limit(8).toList()) {
            var targetState = level.getBlockState(target);
            if (candidates.length() > 1) {
                candidates.append(',');
            }
            candidates.append(target.getX()).append(',').append(target.getY()).append(',').append(target.getZ())
                    .append("{target=").append(blockId(targetState.getBlock()))
                    .append(targetState.canBeReplaced() ? ",replaceable" : "")
                    .append(";above=").append(blockId(level.getBlockState(target.above()).getBlock()))
                    .append(";ground=").append(blockId(level.getBlockState(target.below()).getBlock())).append('}');
        }
        return candidates.append(']').toString();
    }

    private static String nearestTable(ClientLevel level, BlockPos origin) {
        BlockPos table = WorldProbe.nearestCraftingTable(level, origin);
        return table == null ? "none" : table.getX() + "," + table.getY() + "," + table.getZ()
                + "(d=" + Math.round(Math.sqrt(table.distSqr(origin))) + ")";
    }

    private static String blockId(net.minecraft.world.level.block.Block block) {
        return java.util.Objects.requireNonNull(
                BuiltInRegistries.BLOCK.getKey(java.util.Objects.requireNonNull(block))).toString();
    }

    private static String nearbyResources(ClientLevel level, BlockPos origin) {
        Map<String, ResourceSample> resources = new TreeMap<>();
        for (int dx = -16; dx <= 16; dx += 2) {
            for (int dy = -4; dy <= 12; dy += 2) {
                for (int dz = -16; dz <= 16; dz += 2) {
                    BlockPos sample = origin.offset(dx, dy, dz);
                    if (!level.hasChunk(sample.getX() >> 4, sample.getZ() >> 4)) {
                        continue;
                    }
                    String id = blockId(level.getBlockState(sample).getBlock());
                    if (!id.endsWith("_log") && !id.endsWith("_ore") && !java.util.Set.of(
                            "minecraft:gravel", "minecraft:obsidian", "minecraft:water", "minecraft:lava",
                            "minecraft:nether_portal").contains(id)) {
                        continue;
                    }
                    double distance = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                    ResourceSample previous = resources.get(id);
                    if (previous == null) {
                        resources.put(id, new ResourceSample(dx, dy, dz, distance, 1));
                    } else {
                        resources.put(id, new ResourceSample(previous.dx(), previous.dy(), previous.dz(),
                                previous.distance(), previous.count() + 1));
                        if (distance < previous.distance()) {
                            resources.put(id, new ResourceSample(dx, dy, dz, distance, previous.count() + 1));
                        }
                    }
                }
            }
        }
        return resources.entrySet().stream()
                .sorted((first, second) ->
                        Double.compare(first.getValue().distance(), second.getValue().distance()))
                .limit(8)
                .map(entry -> entry.getKey() + "(count~" + entry.getValue().count()
                        + ",nearest_vector=" + entry.getValue().dx() + ','
                        + entry.getValue().dy() + ',' + entry.getValue().dz()
                        + ",distance=" + Math.round(entry.getValue().distance()) + ')')
                .toList()
                .toString();
    }

    private static String fluidSources(ClientLevel level, BlockPos origin) {
        var water = new java.util.ArrayList<BlockPos>();
        var lava = new java.util.ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -4, -8), origin.offset(8, 4, 8))) {
            if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            var fluid = level.getFluidState(pos);
            if (!fluid.isSource()) continue;
            if (fluid.getType() == net.minecraft.world.level.material.Fluids.WATER) water.add(pos.immutable());
            if (fluid.getType() == net.minecraft.world.level.material.Fluids.LAVA) lava.add(pos.immutable());
        }
        java.util.function.Function<java.util.List<BlockPos>, String> describe = positions -> positions.stream()
                .sorted(java.util.Comparator.comparingDouble(pos -> pos.distSqr(origin))).limit(6)
                .map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ()).toList().toString();
        return "{water=" + describe.apply(water) + ",lava=" + describe.apply(lava) + "}";
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    /** Lists only what is held: slot numbers are 1-based, stacks show count, tools show durability. */
    static String inventorySlots(net.minecraft.world.entity.player.Player player) {
        var inv = player.getInventory();
        StringBuilder out = new StringBuilder("{");
        appendArea(out, "hotbar", inv, 0, 9);
        appendArea(out, "main", inv, 9, 36);
        StringBuilder worn = new StringBuilder();
        String[] names = {"feet", "legs", "chest", "head"};
        for (int i = 0; i < 4; i++) {
            ItemStack stack = inv.getItem(36 + i);
            if (!stack.isEmpty()) {
                worn.append(worn.length() > 0 ? "," : "").append(names[i]).append(':').append(describe(stack));
            }
        }
        if (worn.length() > 0) {
            out.append(out.length() > 1 ? "," : "").append("\"worn\":\"").append(worn).append('"');
        }
        ItemStack offhand = inv.getItem(40);
        if (!offhand.isEmpty()) {
            out.append(out.length() > 1 ? "," : "").append("\"offhand\":\"").append(describe(offhand)).append('"');
        }
        out.append("}");
        return out.length() == 2 ? "empty" : out.toString();
    }

    private static void appendArea(StringBuilder out, String area, net.minecraft.world.entity.player.Inventory inv,
            int from, int to) {
        StringBuilder items = new StringBuilder();
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty()) {
                items.append(items.length() > 0 ? "," : "").append('"').append(slot - from + 1).append("\":\"")
                        .append(describe(stack)).append('"');
            }
        }
        if (items.length() > 0) {
            out.append(out.length() > 1 ? "," : "").append('"').append(area).append("\":{").append(items).append('}');
        }
    }

    private static String describe(ItemStack stack) {
        StringBuilder text = new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        if (stack.getCount() > 1) {
            text.append(" x").append(stack.getCount());
        }
        if (stack.isDamageableItem()) {
            text.append(" dur=").append(stack.getMaxDamage() - stack.getDamageValue()).append('/')
                    .append(stack.getMaxDamage());
        }
        return text.toString();
    }

    private static String topEntries(Map<String, Integer> entries, int limit) {
        return entries.entrySet().stream()
                .sorted((first, second) -> {
                    int countOrder = Integer.compare(second.getValue(), first.getValue());
                    return countOrder != 0 ? countOrder : first.getKey().compareTo(second.getKey());
                })
                .limit(limit)
                .map(entry -> entry.getKey() + ':' + entry.getValue())
                .toList()
                .toString();
    }

    private record BiomeSample(int dx, int dz, double distance) {
    }

    private record ResourceSample(int dx, int dy, int dz, double distance, int count) {
    }
}
