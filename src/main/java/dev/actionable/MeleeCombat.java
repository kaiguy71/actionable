package dev.actionable;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;

/** Close-range melee only: no pathfinding, player targets, or neutral-mob provocation. */
final class MeleeCombat {
    private MeleeCombat() { }

    static boolean allowed(Entity entity) {
        if (!(entity instanceof Monster monster) || !entity.isAlive()) {
            return false;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return !id.equals("minecraft:creeper") && !id.equals("minecraft:enderman")
                && !id.equals("minecraft:zombified_piglin") && !id.equals("minecraft:piglin")
                && ((!id.equals("minecraft:spider") && !id.equals("minecraft:cave_spider"))
                    || (monster.getTarget() != null && monster.getTarget() == Minecraft.getInstance().player));
    }

    static boolean reachable(Minecraft client, Entity entity) {
        return client.player != null && allowed(entity)
                && client.player.isWithinEntityInteractionRange(entity, 0)
                && client.player.hasLineOfSight(entity);
    }

    static Entity nearest(Minecraft client) {
        if (client.player == null || client.level == null) {
            return null;
        }
        Entity nearest = null;
        double distance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            double candidate = entity.distanceToSqr(client.player);
            if (candidate < distance && reachable(client, entity)) {
                nearest = entity;
                distance = candidate;
            }
        }
        return nearest;
    }

    static String attack(Minecraft client, int entityId) {
        if (client.player == null || client.level == null || client.gameMode == null
                || !client.player.isAlive() || !client.mouseHandler.isMouseGrabbed() || !client.isWindowActive()) {
            return "ATTACK_FAILED_UNAVAILABLE";
        }
        if (client.player.getHealth() <= 6) {
            return "ATTACK_FAILED_LOW_HEALTH";
        }
        Entity target = client.level.getEntity(entityId);
        if (target == null || !allowed(target)) {
            return "ATTACK_FAILED_INVALID_TARGET";
        }
        if (!reachable(client, target)) {
            return "ATTACK_FAILED_OUT_OF_REACH_OR_OBSTRUCTED";
        }
        // Prefer a sword already in the hotbar; do not rearrange inventory during a fight.
        int bestSlot = -1;
        int bestScore = 0;
        for (int slot = 0; slot < 9; slot++) {
            String id = BuiltInRegistries.ITEM.getKey(client.player.getInventory().getItem(slot).getItem()).toString();
            int score = weaponScore(id);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0 && client.player.getInventory().getSelectedSlot() != bestSlot) {
            client.player.getInventory().setSelectedSlot(bestSlot);
            return "ATTACK_WAIT_WEAPON_SELECTED";
        }
        if (client.player.isUsingItem() || client.player.getAttackStrengthScale(0) < 0.9F) {
            return "ATTACK_WAIT_COOLDOWN";
        }
        client.player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        client.gameMode.attack(client.player, target);
        client.player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND,
                client.player.getMainHandItem().getAttackAnimation(), false);
        return "ATTACK_SENT entity_id=" + entityId + "; observe target health before claiming damage or a kill";
    }

    static int weaponScore(String id) {
        if (!id.startsWith("minecraft:") || (!id.endsWith("_sword") && !id.endsWith("_axe"))) {
            return 0;
        }
        int tier = id.contains("netherite_") ? 6 : id.contains("diamond_") ? 5
                : id.contains("iron_") ? 4 : id.contains("stone_") ? 3 : 1;
        return (id.endsWith("_sword") ? 10 : 0) + tier;
    }
}
