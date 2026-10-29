package dev.actionable;

import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.item.PrimedTnt;

/** Reads client-observable threats without depending on unsynchronized server AI target fields. */
final class ThreatProbe {
    private static final AggressorMemory attackers = new AggressorMemory();
    private static final ThreatMotion motion = new ThreatMotion();
    private ThreatProbe() { }

    static void recordAttacker(java.util.UUID attacker) {
        attackers.record(attacker, System.currentTimeMillis());
    }

    static void clear() { attackers.clear(); motion.clear(); }

    static boolean targeting(Minecraft client, Entity entity) {
        return (entity instanceof Mob mob && mob.getTarget() == client.player)
                || attackers.contains(entity.getUUID(), System.currentTimeMillis());
    }

    static boolean hostile(Minecraft client, Entity entity) {
        if (!(entity instanceof LivingEntity) || !entity.isAlive()) return false;
        String kind = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        boolean targeting = targeting(client, entity);
        boolean aggressive = entity instanceof Mob mob && mob.isAggressive();
        return ThreatRules.hostile(kind, entity instanceof Enemy, targeting, aggressive);
    }

    static ThreatRules.Decision scan(Minecraft client) {
        var player = client.player;
        if (player == null || client.level == null || !player.isAlive())
            return ThreatRules.choose(20, java.util.List.of());
        var threats = new ArrayList<ThreatRules.Threat>();
        if (player.isInLava() || player.isOnFire() || player.isInWall()) {
            threats.add(new ThreatRules.Threat(-1, player.isInLava() ? "lava" : player.isInWall() ? "suffocation" : "fire",
                    player.getX(), player.getY(), player.getZ(), 0, false, true, true));
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            double distance = entity.distanceTo(player);
            if (!entity.isAlive() || distance > 16 || entity == player) continue;
            String kind = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            boolean hazard = entity instanceof PrimedTnt;
            if (entity instanceof Projectile projectile && projectile.getOwner() != player) {
                var velocity = projectile.getDeltaMovement();
                hazard |= ThreatRules.approaching(player.getX() - entity.getX(),
                        player.getEyeY() - entity.getY(), player.getZ() - entity.getZ(), velocity.x, velocity.y, velocity.z);
            }
            if (hostile(client, entity) || hazard) {
                boolean melee = !hazard && MeleeCombat.reachable(client, entity);
                threats.add(new ThreatRules.Threat(entity.getId(), kind, entity.getX(), entity.getY(), entity.getZ(),
                        distance, melee, player.hasLineOfSight(entity), hazard, targeting(client, entity),
                        motion.closing(entity.getUUID(), client.level.getGameTime(), entity.getX(), entity.getZ(),
                                player.getX(), player.getZ()),
                        entity instanceof Creeper creeper && (creeper.getSwellDir() > 0 || creeper.isIgnited()),
                        entity.getY() - player.getY()));
            }
        }
        return ThreatRules.choose(player.getHealth(), threats);
    }
}
