package dev.actionable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Chooses an escape endpoint with dry footing, headroom and greater separation from the threat. */
final class EscapeRoutes {
    record Site(int x, int y, int z, boolean dry, boolean clear, boolean supported) { }
    private EscapeRoutes() { }

    static Optional<Site> choose(List<Site> sites, int x, int y, int z, double threatX, double threatZ) {
        double current = Math.hypot(x - threatX, z - threatZ);
        return sites.stream().filter(s -> s.dry() && s.clear() && s.supported() && Math.abs(s.y() - y) <= 3)
                .filter(s -> Math.hypot(s.x() - threatX, s.z() - threatZ) > current + 2)
                .max(Comparator.comparingDouble(s -> Math.hypot(s.x() - threatX, s.z() - threatZ)
                        - Math.hypot(s.x() - x, s.z() - z) * 0.2));
    }
}
