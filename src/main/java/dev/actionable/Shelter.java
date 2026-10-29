package dev.actionable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;

/**
 * Reflexive defensive building: tower above a swarm of melee mobs, or wall in against massed archers.
 * Runs in code so it reacts within ticks; the LLM is only told the outcome.
 */
final class Shelter {
    enum Mode { NONE, TOWER, WALL }

    static final int SWARM_SIZE = 3;
    static final int ARCHER_GROUP = 2;
    static final int TOWER_HEIGHT = 4;
    static final int WALL_BLOCKS = 9;
    private static final int MELEE_RANGE = 9;
    private static final int ARCHER_RANGE = 24;
    private static final int MAX_TICKS = 1200;
    private static final int CALM_TICKS = 80;
    private static final List<String> MATERIALS = List.of("cobblestone", "cobbled_deepslate", "dirt", "netherrack",
            "stone", "andesite", "diorite", "granite", "tuff", "oak_planks", "spruce_planks",
            "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks");

    private Mode mode = Mode.NONE;
    private long startedAt;
    private long lastThreatAt;
    private int towered;
    private BlockPos towerCell;
    private String material = "";
    private final ArrayDeque<BlockPos> plan = new ArrayDeque<>();
    private final List<BlockPos> built = new ArrayList<>();
    private final List<BlockBreaker> breaking = new ArrayList<>();
    private int exitIndex = -1;

    boolean active() {
        return mode != Mode.NONE;
    }

    Mode mode() {
        return mode;
    }

    static int melee(Minecraft client) {
        return count(client, false);
    }

    static int archers(Minecraft client) {
        return count(client, true);
    }

    private static int count(Minecraft client, boolean archers) {
        if (client.player == null || client.level == null) {
            return 0;
        }
        int total = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Monster) || !entity.isAlive()) {
                continue;
            }
            boolean archer = entity instanceof AbstractSkeleton;
            double distance = entity.distanceToSqr(client.player);
            if (archers ? archer && distance <= ARCHER_RANGE * ARCHER_RANGE && client.player.hasLineOfSight(entity)
                    : !archer && distance <= MELEE_RANGE * MELEE_RANGE
                            && !"minecraft:creeper".equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                    .getKey(entity.getType()).toString())) {
                total++;
            }
        }
        return total;
    }

    static String material(Map<String, Integer> inventory, int needed) {
        for (String candidate : MATERIALS) {
            if (inventory.getOrDefault("minecraft:" + candidate, inventory.getOrDefault(candidate, 0)) >= needed) {
                return "minecraft:" + candidate;
            }
        }
        return null;
    }

    /** Decides whether a reflex should start; returns the mode to use or NONE. */
    Mode wanted(Minecraft client) {
        if (client.player == null || active()) {
            return Mode.NONE;
        }
        var inventory = WorldProbe.inventory(client.player);
        if (archers(client) >= ARCHER_GROUP && material(inventory, WALL_BLOCKS) != null) {
            return Mode.WALL;
        }
        if (melee(client) >= SWARM_SIZE && material(inventory, TOWER_HEIGHT + 1) != null) {
            return Mode.TOWER;
        }
        return Mode.NONE;
    }

    void begin(Minecraft client, Mode chosen, long tick) {
        var player = client.player;
        if (player == null) {
            return;
        }
        mode = chosen;
        startedAt = tick;
        lastThreatAt = tick;
        towered = 0;
        towerCell = null;
        exitIndex = -1;
        built.clear();
        plan.clear();
        breaking.clear();
        material = material(WorldProbe.inventory(player), chosen == Mode.WALL ? WALL_BLOCKS : TOWER_HEIGHT + 1);
        if (chosen == Mode.WALL) {
            BlockPos feet = player.blockPosition();
            for (int dy = 0; dy <= 1; dy++) {
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    plan.add(feet.relative(side).above(dy));
                }
            }
            plan.add(feet.above(2));
        }
    }

    /** Runs one tick. Returns a status string, or null once the reflex has finished. */
    String tick(Minecraft client, long tick, BiFunction<BlockPos, String, String> placer) {
        var player = client.player;
        if (player == null || mode == Mode.NONE) {
            return null;
        }
        boolean threatened = melee(client) + archers(client) > 0;
        if (threatened) {
            lastThreatAt = tick;
        }
        boolean over = tick - startedAt > MAX_TICKS || tick - lastThreatAt > CALM_TICKS;
        if (mode == Mode.TOWER) {
            return tickTower(client, tick, placer, over);
        }
        return tickWall(client, tick, placer, over);
    }

    private String tickTower(Minecraft client, long tick, BiFunction<BlockPos, String, String> placer, boolean over) {
        var player = client.player;
        if (over || towered >= TOWER_HEIGHT && tick - lastThreatAt > CALM_TICKS) {
            finish(client);
            return null;
        }
        if (towered >= TOWER_HEIGHT) {
            return "SHELTER_TOWER_HOLDING: " + towered + " blocks up, waiting for the mobs to leave";
        }
        var keys = client.options;
        if (player.onGround()) {
            towerCell = player.blockPosition();
            keys.keyJump.setDown(true);
            return "SHELTER_TOWER_JUMP";
        }
        keys.keyJump.setDown(false);
        if (towerCell != null && player.getY() > towerCell.getY() + 1.0
                && client.level.getBlockState(towerCell).isAir()) {
            String result = placer.apply(towerCell, material);
            if (result.startsWith("placement request sent")) {
                towered++;
                towerCell = null;
                return "SHELTER_TOWER_PLACED " + towered + "/" + TOWER_HEIGHT;
            }
            return "SHELTER_TOWER_" + result;
        }
        return "SHELTER_TOWER_RISING";
    }

    private String tickWall(Minecraft client, long tick, BiFunction<BlockPos, String, String> placer, boolean over) {
        if (exitIndex >= 0 || over) {
            return tickExit(client, tick);
        }
        BlockPos next = plan.peek();
        if (next == null) {
            return "SHELTER_WALL_HOLDING: enclosed, waiting for the archers to leave";
        }
        if (!client.level.getBlockState(next).isAir()) {
            plan.poll();
            return "SHELTER_WALL_CELL_ALREADY_SOLID " + next;
        }
        String result = placer.apply(next, material);
        if (result.startsWith("placement request sent")) {
            built.add(plan.poll());
            return "SHELTER_WALL_PLACED " + built.size() + "/" + WALL_BLOCKS;
        }
        if (result.startsWith("BLOCK_PLACE_FAILED_MATERIAL_MISSING") || result.startsWith("BLOCK_PLACE_FAILED_NO_SUPPORT")) {
            plan.poll();
        }
        return "SHELTER_WALL_" + result;
    }

    /** Opens a one-block gap (feet and head height) on the first wall side, then stops. */
    private String tickExit(Minecraft client, long tick) {
        if (exitIndex < 0) {
            exitIndex = 0;
            breaking.clear();
            if (!built.isEmpty()) {
                BlockPos feet = built.get(0);
                for (BlockPos cell : new BlockPos[] {feet, feet.above()}) {
                    var start = BlockBreaker.start(client, cell.getX(), cell.getY(), cell.getZ(), tick);
                    if (start.breaker() != null) {
                        breaking.add(start.breaker());
                    }
                }
            }
        }
        breaking.removeIf(breaker -> breaker.tick(client, tick, command -> { }, () -> { }) != null);
        if (breaking.isEmpty()) {
            finish(client);
            return null;
        }
        return "SHELTER_WALL_OPENING";
    }

    void finish(Minecraft client) {
        mode = Mode.NONE;
        plan.clear();
        built.clear();
        breaking.clear();
        towerCell = null;
        if (client.options != null) {
            client.options.keyJump.setDown(false);
        }
    }
}
