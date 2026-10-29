package dev.actionable;

/** Structural shelter evidence, independent of Minecraft. Completion is never a percentage threshold. */
final class HouseStructure {
    record Cell(boolean loaded, boolean barrier, boolean supported, boolean dry, boolean passable, boolean fixture) {
        Cell(boolean loaded, boolean barrier, boolean supported, boolean dry, boolean passable) {
            this(loaded, barrier, supported, dry, passable, false);
        }
    }
    interface Probe { Cell at(int x, int y, int z); }
    record Door(boolean installed, boolean closed) { }
    record Result(boolean loaded, int missingShell, boolean floorReady, boolean dryInterior,
            boolean entryClear, boolean interiorClear, boolean doorReady, boolean doorClosed, int roomRepairs) {
        boolean shellReady() { return loaded && missingShell == 0; }
        boolean enclosed() { return shellReady() && floorReady && dryInterior && entryClear && interiorClear && doorReady && doorClosed; }
        String reason() {
            if (!loaded) return "unloaded: structure unknown";
            if (missingShell > 0) return "missing wall/roof barriers=" + missingShell;
            if (!floorReady) return "unsupported or wet floor";
            if (!dryInterior) return "fluid inside";
            if (!entryClear) return "entrance headroom blocked";
            if (!interiorClear) return "interior blocked by terrain/non-fixture blocks";
            if (!doorReady) return "door missing or malformed";
            if (!doorClosed) return "door open";
            return "enclosed";
        }
    }
    private HouseStructure() { }

    static Result inspect(Probe probe, Door door) {
        boolean loaded = true, floor = true, dry = true, entry = true, interior = true;
        int missing = 0, roomRepairs = 0;
        for (int y = 0; y < HouseDesign.HEIGHT; y++) {
            for (int z = 0; z < HouseDesign.LENGTH; z++) {
                for (int x = 0; x < HouseDesign.WIDTH; x++) {
                    Cell cell = probe.at(x, y, z);
                    loaded &= cell.loaded();
                    if (HouseDesign.solid(x, y, z) && !(cell.barrier() && cell.dry())) missing++;
                    if (y < HouseDesign.HEIGHT - 1 && x > 0 && x < HouseDesign.WIDTH - 1
                            && z > 0 && z < HouseDesign.LENGTH - 1) {
                        boolean clear = cell.passable() || cell.fixture();
                        interior &= clear;
                        if (!clear) roomRepairs++;
                    }
                }
            }
        }
        for (int[] pos : HouseDesign.floorCells()) {
            Cell cell = probe.at(pos[0], pos[1], pos[2]);
            loaded &= cell.loaded();
            floor &= cell.supported() && cell.dry();
            if (!(cell.supported() && cell.dry())) roomRepairs++;
        }
        for (int[] pos : HouseDesign.dryCells()) {
            Cell cell = probe.at(pos[0], pos[1], pos[2]);
            loaded &= cell.loaded();
            dry &= cell.dry();
            if (!cell.dry()) roomRepairs++;
        }
        for (int y = 0; y <= 1; y++) {
            Cell cell = probe.at(HouseDesign.DOOR_STAND[0], y, HouseDesign.DOOR_STAND[2]);
            entry &= cell.passable() && cell.dry();
            if (!(cell.passable() && cell.dry())) roomRepairs++;
        }
        return new Result(loaded, missing, floor, dry, entry, interior, door.installed(), door.closed(), roomRepairs);
    }
}
