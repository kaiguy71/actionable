package dev.actionable;

/** Vanilla survival limits that every melee attack must respect before an attack tick is issued (FR-3). */
final class ReachRule {
    static final double MAX_REACH = 4.5;
    /** Vanilla's attack-strength meter is per weapon; a hit below this fraction is a weak, wasted swing. */
    static final float COOLDOWN_READY = 0.9F;

    private ReachRule() { }

    static boolean inReach(double distanceToHitbox) {
        return distanceToHitbox >= 0 && distanceToHitbox <= MAX_REACH;
    }

    static boolean cooldownReady(float attackStrengthScale) {
        return attackStrengthScale >= COOLDOWN_READY;
    }

    static boolean mayAttack(double distanceToHitbox, float attackStrengthScale) {
        return inReach(distanceToHitbox) && cooldownReady(attackStrengthScale);
    }

    /** Eye-to-hitbox distance, the same measure the game uses for entity interaction. */
    static double distanceToHitbox(net.minecraft.world.entity.player.Player player,
            net.minecraft.world.entity.Entity target) {
        return Math.sqrt(target.getBoundingBox().distanceToSqr(player.getEyePosition()));
    }

    static boolean mayAttack(net.minecraft.world.entity.player.Player player,
            net.minecraft.world.entity.Entity target) {
        return mayAttack(distanceToHitbox(player, target), player.getAttackStrengthScale(0));
    }
}
