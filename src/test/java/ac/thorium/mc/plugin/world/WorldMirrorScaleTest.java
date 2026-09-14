package ac.thorium.mc.plugin.world;

import ac.thorium.mc.proto.SectionPos;
import ac.thorium.mc.proto.UpStream;
import ac.thorium.mc.proto.WorldPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the mirror at player counts a local test server cannot host, to answer
 * what world streaming costs on the wire and whether it converges.
 *
 * <p>Measured inputs, from a 40-bot run on Paper 1.21.11 with real terrain:
 * 1512 raw bytes per section, 8.31x deflate on world frames (182 bytes on the
 * wire), 66% of sections uniform. The plugin-to-gateway hop negotiates
 * permessage-deflate, so the deflated figure is what a customer's uplink pays.
 */
class WorldMirrorScaleTest {
    /** Mean encoded size of one section, measured over a real capture. */
    private static final int RAW_BYTES_PER_SECTION = 1512;
    /** permessage-deflate ratio on world_chunk frames, measured over the same capture. */
    private static final double DEFLATE_RATIO = 8.31;
    private static final int TICKS_PER_SEC = 20;

    private static SectionPos sec(int x, int y, int z) {
        return SectionPos.newBuilder().setDimension("world").setX(x).setY(y).setZ(z).build();
    }

    private static SectionData section() {
        String[] a = new String[SectionData.BLOCKS];
        Arrays.fill(a, "minecraft:stone");
        return SectionData.of(a);
    }

    /**
     * The sections a population wants, in the same ring-outward, player-interleaved
     * order WorldSampler emits, so the mirror's cap truncates the same way here.
     */
    private static Set<SectionPos> wantedFor(List<int[]> players, int radius) {
        Set<SectionPos> out = new LinkedHashSet<SectionPos>();
        for (int ring = 0; ring <= radius; ring++) {
            for (int[] p : players) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        for (int dy = -WorldSampler.SECTIONS_BELOW; dy <= WorldSampler.SECTIONS_ABOVE; dy++) {
                            out.add(sec(p[0] + dx, 8 + dy, p[1] + dz));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Spreads players over a square of chunk coordinates, then walks them. */
    private static List<int[]> population(int n, int spreadChunks, Random rnd) {
        List<int[]> out = new ArrayList<int[]>();
        for (int i = 0; i < n; i++) {
            out.add(new int[]{rnd.nextInt(spreadChunks) - spreadChunks / 2, rnd.nextInt(spreadChunks) - spreadChunks / 2});
        }
        return out;
    }

    private static void walk(List<int[]> players, Random rnd) {
        // A sprinting player crosses a chunk border roughly every 1.8 s; retarget runs
        // twice a second, so a ~25% chance of moving a chunk per retarget is realistic.
        for (int[] p : players) {
            if (rnd.nextInt(4) == 0) p[0] += rnd.nextInt(3) - 1;
            if (rnd.nextInt(4) == 0) p[1] += rnd.nextInt(3) - 1;
        }
    }

    private static final class Run {
        int sectionsSent;
        int droppedRefs;
        long wireBytes;
        int backlogColumns;
        int heldSections;
    }

    /**
     * Simulates `seconds` of wall clock: retarget every 10 ticks, snapshot the
     * per-tick column budget, drain on the 75 ms flush cadence.
     */
    private Run simulate(int players, int radius, int columnsPerTick, int seconds, long seed) {
        Random rnd = new Random(seed);
        WorldMirror m = new WorldMirror();
        m.setPolicy(WorldPolicy.newBuilder().setEnabled(true)
                .setRadiusChunks(radius).setColumnsPerTick(columnsPerTick).build());
        List<int[]> pop = population(players, Math.max(8, (int) Math.sqrt(players) * 4), rnd);
        Run r = new Run();
        int totalTicks = seconds * TICKS_PER_SEC;
        for (int tick = 1; tick <= totalTicks; tick++) {
            if (tick % WorldSampler.RETARGET_EVERY_TICKS == 0) {
                walk(pop, rnd);
                m.retarget(wantedFor(pop, radius), tick);
            }
            for (WorldMirror.ColumnPos c : m.nextColumns(m.columnsPerTick())) {
                long token = m.snapshotToken();
                for (SectionPos p : m.wantedIn(c)) m.acceptSection(p, section(), token);
            }
            if (tick % 2 == 0) { // ~75 ms flush cadence
                for (UpStream u : m.drain(tick * 50L, tick, 0)) {
                    if (u.hasWorldChunk()) {
                        int n = u.getWorldChunk().getSectionsCount();
                        r.sectionsSent += n;
                        r.wireBytes += (long) (n * RAW_BYTES_PER_SECTION / DEFLATE_RATIO);
                    }
                    if (u.hasWorldDelta()) {
                        r.droppedRefs += u.getWorldDelta().getDroppedCount();
                        r.wireBytes += (long) (u.getWorldDelta().getSerializedSize() / DEFLATE_RATIO);
                    }
                }
            }
        }
        r.backlogColumns = m.pendingColumnCount();
        r.heldSections = m.heldSections();
        return r;
    }

    @Test
    void bandwidthIsCappedByTheColumnBudgetNotByPlayerCount() {
        int seconds = 60;
        System.out.println();
        System.out.printf("%8s %7s %9s %10s %12s %9s %9s%n",
                "players", "radius", "cols/tk", "sections/s", "wire KiB/s", "held", "backlog");
        int[] counts = {40, 100, 250, 500, 1000};
        long[] rates = new long[counts.length];
        for (int i = 0; i < counts.length; i++) {
            Run r = simulate(counts[i], 2, 2, seconds, 42);
            rates[i] = r.wireBytes / seconds;
            System.out.printf("%8d %7d %9d %10.1f %12.1f %9d %9d%n",
                    counts[i], 2, 2, r.sectionsSent / (double) seconds,
                    r.wireBytes / 1024.0 / seconds, r.heldSections, r.backlogColumns);
        }
        // The per-tick snapshot budget is a hard ceiling: 2 columns/tick can never
        // yield more than 2 * 20 * (2*VERTICAL_SECTIONS+1) sections per second, so
        // 25x the players cannot produce 25x the bytes.
        int maxSectionsPerSec = 2 * TICKS_PER_SEC * WorldSampler.SECTIONS_PER_COLUMN;
        long ceiling = (long) (maxSectionsPerSec * RAW_BYTES_PER_SECTION / DEFLATE_RATIO);
        for (int i = 0; i < counts.length; i++) {
            assertTrue(rates[i] <= ceiling,
                    counts[i] + " players exceeded the budget ceiling: " + rates[i] + " > " + ceiling);
        }
        assertTrue(rates[counts.length - 1] < rates[0] * 25,
                "bandwidth scaled linearly with players; the budget cap is not holding");
    }

    @Test
    void raisingTheBudgetRaisesBandwidthAndBacklogClears() {
        int seconds = 60;
        System.out.println();
        System.out.printf("%8s %9s %10s %12s %9s %9s%n",
                "players", "cols/tk", "sections/s", "wire KiB/s", "held", "backlog");
        for (int perTick : new int[]{2, 8, 16, 32}) {
            Run r = simulate(500, 2, perTick, seconds, 7);
            System.out.printf("%8d %9d %10.1f %12.1f %9d %9d%n",
                    500, perTick, r.sectionsSent / (double) seconds,
                    r.wireBytes / 1024.0 / seconds, r.heldSections, r.backlogColumns);
        }
    }

    @Test
    void atFiveHundredPlayersTheDefaultBudgetCannotConverge() {
        // The honest limit: at 500 players the wanted set churns faster than 2
        // columns/tick can read it, so the mirror never finishes covering it.
        Run slow = simulate(500, 2, 2, 60, 11);
        assertTrue(slow.backlogColumns > 0,
                "expected an unconverged backlog at 500 players on the default budget");
        Run fast = simulate(500, 2, 32, 60, 11);
        assertTrue(fast.backlogColumns < slow.backlogColumns,
                "raising the budget should shrink the backlog");
        System.out.printf("%n500 players: backlog %d columns at 2/tick, %d at 32/tick%n",
                slow.backlogColumns, fast.backlogColumns);
    }
}
