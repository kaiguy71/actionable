package dev.actionable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chooses where mining is reasonable. Baritone's mine goes to the nearest matching block it knows of,
 * which can be deep inside a dark cave; these rules keep an unequipped player on exposed, lit deposits.
 */
final class MiningPolicy {
    /** Light at the exposed face that counts as lit without cave gear. */
    static final int SAFE_LIGHT = 8;
    /** Deepest a deposit may sit below the local surface without cave gear. */
    static final int MAX_SAFE_DEPTH = 6;
    /** Deposits farther than this are walked to before mining, so Baritone starts near the right one. */
    static final int WALK_FIRST_DISTANCE = 8;
    /** Places the client retreated from are avoided within this radius. */
    static final int AVOID_RADIUS = 12;

    private MiningPolicy() {
    }

    /**
     * A candidate block with an exposed face. {@code stand} is the air cell touching that face, and
     * light, sky and depth are measured there.
     */
    record Site(int x, int y, int z, int standX, int standY, int standZ, int light, boolean skyVisible,
            int depthBelowSurface, double distance) {
        String describe() {
            return x + "," + y + "," + z + "(d=" + Math.round(distance) + ",light=" + light
                    + ",sky=" + skyVisible + ",depth=" + depthBelowSurface + ")";
        }
    }

    static boolean safe(Site site, boolean caveReady) {
        if (caveReady) {
            return true;
        }
        // Surface-level sites are fine even when dark: a tree trunk under its canopy at night is not a cave.
        boolean surface = site.depthBelowSurface() <= 1;
        return (surface || site.skyVisible() || site.light() >= SAFE_LIGHT) && site.depthBelowSurface() <= MAX_SAFE_DEPTH;
    }

    static boolean nearAvoided(Site site, List<int[]> avoid) {
        for (int[] point : avoid) {
            double dx = site.x() - point[0];
            double dy = site.y() - point[1];
            double dz = site.z() - point[2];
            if (dx * dx + dy * dy + dz * dz <= AVOID_RADIUS * AVOID_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** The best acceptable site: closest, with a small preference for sites open to the sky. */
    static Optional<Site> choose(List<Site> sites, boolean caveReady, List<int[]> avoid) {
        return sites.stream()
                .filter(site -> safe(site, caveReady) && !nearAvoided(site, avoid))
                .min(Comparator.comparingDouble(site -> site.distance() + (site.skyVisible() ? 0 : 6)));
    }

    /** Survival reach: a block is minable when its center is within 4.5 blocks of the player's eyes. */
    static boolean inReach(int feetX, int feetY, int feetZ, int blockX, int blockY, int blockZ) {
        double dx = (blockX + 0.5) - (feetX + 0.5);
        double dy = (blockY + 0.5) - (feetY + 1.62);
        double dz = (blockZ + 0.5) - (feetZ + 0.5);
        return dx * dx + dy * dy + dz * dz <= 4.5 * 4.5;
    }

    /** Explains why no candidate was acceptable, naming the nearest one so the model can reason. */
    static String rejection(String block, List<Site> sites, List<String> missingForCaves) {
        Site nearest = sites.stream().min(Comparator.comparingDouble(Site::distance)).orElseThrow();
        return "MINE_FAILED_UNSAFE_SITE: every loaded " + block + " nearby is in the dark or deep underground"
                + " (nearest " + nearest.describe() + "). Without cave gear (missing "
                + String.join(", ", missingForCaves) + ") mine exposed blocks on the surface instead:"
                + " goto open ground, or choose another resource.";
    }
}
