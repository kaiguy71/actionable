package dev.actionable;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;

/**
 * Per-mob fighting styles, run every tick by code. Skeletons are rushed and killed with jump crits before their
 * bows do much damage. Creepers are crit once, then the player backs away while the attack recharges so the creeper
 * never gets its fuse to finish, and steps back in for the next hit.
 */
final class MobFighter {
    enum Style { SKELETON, CREEPER }

    /** Skeletons and creepers are fought from this far; closer mobs use the plain melee defense. */
    static final double ENGAGE_RANGE = 20;
    private static final double SKELETON_SPRINT_RANGE = 5;
    private static final double CREEPER_STRIKE_RANGE = 3.0;
    private static final double CREEPER_SAFE_SWELL = 0.6;
    private static final float READY = 0.92F;

    private boolean retreating;

    static Style styleOf(Entity entity) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return switch (id) {
            case "minecraft:skeleton", "minecraft:stray", "minecraft:bogged" -> Style.SKELETON;
            case "minecraft:creeper" -> Style.CREEPER;
            default -> null;
        };
    }

    static boolean engageable(Minecraft client, Entity entity) {
        return client.player != null && entity.isAlive() && styleOf(entity) != null
                && entity.distanceToSqr(client.player) <= ENGAGE_RANGE * ENGAGE_RANGE
                && client.player.hasLineOfSight(entity);
    }

    static Entity nearest(Minecraft client) {
        if (client.player == null || client.level == null) {
            return null;
        }
        Entity best = null;
        double distance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            double candidate = entity.distanceToSqr(client.player);
            if (candidate < distance && engageable(client, entity)) {
                best = entity;
                distance = candidate;
            }
        }
        return best;
    }

    /** Runs one tick of the matching style against the target; returns a short status for the model. */
    String tick(Minecraft client, Entity target) {
        var player = client.player;
        if (player == null || client.gameMode == null) {
            release(client);
            return "FIGHT_UNAVAILABLE";
        }
        Style style = styleOf(target);
        if (style == null) {
            release(client);
            return "FIGHT_INVALID_TARGET";
        }
        selectWeapon(client);
        return style == Style.SKELETON ? skeleton(client, target) : creeper(client, (Creeper) target);
    }

    private String skeleton(Minecraft client, Entity target) {
        var player = client.player;
        double distance = player.distanceTo(target);
        faceTarget(client, target);
        boolean inReach = player.isWithinEntityInteractionRange(target, 0);
        if (inReach) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            return crit(client, target, "skeleton");
        }
        // Close the gap fast: sprint until near, then walk so the sprint does not cancel the crit.
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(distance > SKELETON_SPRINT_RANGE);
        client.options.keyJump.setDown(player.onGround() && distance <= SKELETON_SPRINT_RANGE
                && player.getAttackStrengthScale(0) >= READY);
        return "FIGHT_SKELETON_RUSH distance=" + Math.round(distance);
    }

    private String creeper(Minecraft client, Creeper target) {
        var player = client.player;
        double distance = player.distanceTo(target);
        float strength = player.getAttackStrengthScale(0);
        if (target.getSwelling(1.0F) > CREEPER_SAFE_SWELL) {
            retreating = true;
        }
        if (retreating) {
            if (strength >= READY && distance >= CREEPER_STRIKE_RANGE + 0.5
                    && target.getSwelling(1.0F) <= CREEPER_SAFE_SWELL) {
                retreating = false;
            } else {
                backAway(client, target);
                return "FIGHT_CREEPER_BACKING_AWAY distance=" + Math.round(distance) + " attack_ready="
                        + (strength >= READY);
            }
        }
        faceTarget(client, target);
        if (player.isWithinEntityInteractionRange(target, 0)) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            String result = crit(client, target, "creeper");
            if (result.startsWith("FIGHT_HIT")) {
                retreating = true;
            }
            return result;
        }
        client.options.keySprint.setDown(false);
        client.options.keyUp.setDown(true);
        return "FIGHT_CREEPER_APPROACH distance=" + Math.round(distance);
    }

    /** Jump when ready, then hit on the way down: a falling, non-sprinting strike is a critical hit. */
    private String crit(Minecraft client, Entity target, String name) {
        var player = client.player;
        if (player.getAttackStrengthScale(0) < READY) {
            client.options.keyJump.setDown(false);
            return "FIGHT_WAIT_COOLDOWN";
        }
        if (player.onGround()) {
            client.options.keyJump.setDown(true);
            return "FIGHT_JUMPING_FOR_CRIT";
        }
        client.options.keyJump.setDown(false);
        if (player.getDeltaMovement().y >= 0) {
            return "FIGHT_RISING";
        }
        player.setSprinting(false);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        client.gameMode.attack(player, target);
        player.swingAndResetAttackStrength(net.minecraft.world.InteractionHand.MAIN_HAND,
                player.getMainHandItem().getAttackAnimation(), false);
        return "FIGHT_HIT " + name + " entity_id=" + target.getId() + " (crit); observe target health";
    }

    private void backAway(Minecraft client, Entity target) {
        var player = client.player;
        Vec3 away = player.position().scale(2).subtract(target.position());
        player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(away.x, player.getEyeY(), away.z));
        client.options.keyJump.setDown(false);
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(true);
    }

    private static void faceTarget(Minecraft client, Entity target) {
        client.player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
    }

    private static void selectWeapon(Minecraft client) {
        var inventory = client.player.getInventory();
        int bestSlot = -1;
        int bestScore = 0;
        for (int slot = 0; slot < 9; slot++) {
            String id = BuiltInRegistries.ITEM.getKey(inventory.getItem(slot).getItem()).toString();
            int score = MeleeCombat.weaponScore(id);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0 && inventory.getSelectedSlot() != bestSlot) {
            inventory.setSelectedSlot(bestSlot);
        }
    }

    /** Lets go of every movement key the fight pressed. */
    void release(Minecraft client) {
        retreating = false;
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
    }
}
