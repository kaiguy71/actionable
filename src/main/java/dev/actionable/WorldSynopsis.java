package dev.actionable;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;

final class WorldSynopsis {
    private WorldSynopsis() {
    }

    static String capture(Minecraft client) {
        var player = client.player;
        var level = client.level;
        StringBuilder synopsis = new StringBuilder(512);
        synopsis.append("dimension=").append(level.dimension().identifier())
                .append("; position=").append(player.blockPosition().getX()).append(',')
                .append(player.blockPosition().getY()).append(',')
                .append(player.blockPosition().getZ())
                .append("; health=").append(Math.round(player.getHealth()))
                .append("; inventory=").append(inventory(player));

        Map<String, Integer> nearbyBlocks = new TreeMap<>();
        BlockPos origin = player.blockPosition();
        for (int x = -4; x <= 4; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -4; z <= 4; z++) {
                    String id = BuiltInRegistries.BLOCK.getKey(
                            level.getBlockState(origin.offset(x, y, z)).getBlock()).toString();
                    nearbyBlocks.merge(id, 1, Integer::sum);
                }
            }
        }
        synopsis.append("; sampled_blocks=").append(topEntries(nearbyBlocks, 10));

        StringBuilder threats = new StringBuilder();
        int threatCount = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Monster monster && monster.isAlive()
                    && monster.distanceToSqr(player) <= 144 && threatCount < 6) {
                if (threatCount++ > 0) {
                    threats.append(',');
                }
                threats.append(BuiltInRegistries.ENTITY_TYPE.getKey(monster.getType()))
                        .append('@').append(monster.blockPosition().getX()).append(',')
                        .append(monster.blockPosition().getY()).append(',')
                        .append(monster.blockPosition().getZ());
            }
        }
        synopsis.append("; nearby_hostile_mobs=").append(threatCount == 0 ? "none" : threats);
        return synopsis.toString();
    }

    private static String inventory(net.minecraft.world.entity.player.Player player) {
        Map<String, Integer> items = new TreeMap<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) {
                items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        return topEntries(items, 12);
    }

    private static String topEntries(Map<String, Integer> entries, int limit) {
        return entries.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> entry.getKey() + ':' + entry.getValue())
                .toList()
                .toString();
    }
}
