package dev.actionable;

import java.util.Comparator;
import java.util.List;

/** Fill from reach when possible, otherwise navigate to a visible source's dry bank. */
final class BucketPlan {
    enum Mode { DONE, NEED_BUCKET, FILL, WALK, EXPLORE }
    record Source(int x, int y, int z, int standX, int standY, int standZ, double distance,
            boolean reachable, boolean visible, boolean dryBank) { }
    record Decision(Mode mode, Source source) { }
    private BucketPlan() { }
    /** Search new surface columns without depending on Baritone's explore command. */
    static String searchCommand(int x, int z, int attempt) {
        return HomeRange.surfaceGoto(HuntSearch.leg(new int[] {x, 0, z}, attempt));
    }
    static Decision choose(boolean filled, boolean empty, List<Source> sources) {
        if (filled) return new Decision(Mode.DONE, null);
        if (!empty) return new Decision(Mode.NEED_BUCKET, null);
        var fill = sources.stream().filter(s -> s.visible() && s.reachable())
                .min(Comparator.comparingDouble(Source::distance));
        if (fill.isPresent()) return new Decision(Mode.FILL, fill.get());
        var bank = sources.stream().filter(Source::dryBank).min(Comparator.comparingDouble(Source::distance));
        return bank.map(s -> new Decision(Mode.WALK, s)).orElseGet(() -> new Decision(Mode.EXPLORE, null));
    }
}
