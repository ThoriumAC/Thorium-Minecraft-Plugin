package ac.thorium.mc.plugin.world;

import ac.thorium.mc.proto.SectionPos;
import ac.thorium.mc.proto.WorldPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Columns under players are re-read on a short interval, since /fill, /setblock,
 * WorldEdit and Block.setType fire no Bukkit block event.
 */
class WorldMirrorHotColumnTest {
    private WorldMirror m;

    @BeforeEach
    void setUp() {
        m = new WorldMirror();
        m.setPolicy(WorldPolicy.newBuilder().setEnabled(true).build());
    }

    private static SectionPos p(int x, int y, int z) {
        return SectionPos.newBuilder().setDimension("overworld").setX(x).setY(y).setZ(z).build();
    }

    private static SectionData data(String fill) {
        String[] a = new String[SectionData.BLOCKS];
        Arrays.fill(a, fill);
        return SectionData.of(a);
    }

    private static WorldMirror.ColumnPos col(int x, int z) {
        return new WorldMirror.ColumnPos("overworld", x, z);
    }

    /** Holds one section of one column, read at tick 0. */
    private void hold(SectionPos pos) {
        m.retarget(Collections.singletonList(pos), 0L);
        m.nextColumns(8);
        m.acceptSection(pos, data("minecraft:stone"), m.snapshotToken());
    }

    @Test
    void theColumnAPlayerStandsInIsRereadWithinSeconds() {
        SectionPos pos = p(0, 4, 0);
        hold(pos);

        // Well inside the two-minute general sweep, and well past the hot one.
        m.retarget(Collections.singletonList(pos), Collections.singletonList(col(0, 0)),
                WorldMirror.DEFAULT_HOT_INTERVAL_TICKS + 1);
        List<WorldMirror.ColumnPos> queued = m.nextColumns(8);
        assertTrue(queued.contains(col(0, 0)),
                "the column under a player was not re-read for " + WorldMirror.DEFAULT_HOT_INTERVAL_TICKS + " ticks");
    }

    @Test
    void aColumnJustReadIsLeftAlone() {
        SectionPos pos = p(0, 4, 0);
        hold(pos);
        m.retarget(Collections.singletonList(pos), Collections.singletonList(col(0, 0)), 5L);
        assertTrue(m.nextColumns(8).isEmpty(), "a column read five ticks ago was read again");
    }

    @Test
    void groundNobodyIsStandingOnKeepsTheSlowSweep() {
        SectionPos pos = p(0, 4, 0);
        hold(pos);
        // Nobody is in this column, so the hot interval does not apply to it.
        m.retarget(Collections.singletonList(pos), Collections.<WorldMirror.ColumnPos>emptyList(),
                WorldMirror.DEFAULT_HOT_INTERVAL_TICKS + 1);
        assertTrue(m.nextColumns(8).isEmpty(), "an unoccupied column was swept at the hot interval");
    }

    /** The sweep must not become a way to spend the whole snapshot budget. */
    @Test
    void theHotSweepIsBounded() {
        java.util.List<SectionPos> want = new java.util.ArrayList<SectionPos>();
        java.util.List<WorldMirror.ColumnPos> hot = new java.util.ArrayList<WorldMirror.ColumnPos>();
        for (int i = 0; i < 40; i++) {
            want.add(p(i, 4, 0));
            hot.add(col(i, 0));
        }
        m.retarget(want, 0L);
        m.nextColumns(64);
        for (int i = 0; i < 40; i++) m.acceptSection(p(i, 4, 0), data("minecraft:stone"), m.snapshotToken());

        m.retarget(want, hot, WorldMirror.DEFAULT_HOT_INTERVAL_TICKS + 1);
        assertEquals(WorldMirror.DEFAULT_HOT_COLUMNS, m.nextColumns(64).size(),
                "forty standing players queued more than the hot budget");
    }

    /** The unchanged-content shortcut must still mark the column as checked. */
    @Test
    void readingUnchangedGroundStillCountsAsReading() {
        SectionPos pos = p(0, 4, 0);
        hold(pos);
        long t = WorldMirror.DEFAULT_HOT_INTERVAL_TICKS + 1;
        m.retarget(Collections.singletonList(pos), Collections.singletonList(col(0, 0)), t);
        m.nextColumns(8);
        m.acceptSection(pos, data("minecraft:stone"), m.snapshotToken()); // identical content

        m.retarget(Collections.singletonList(pos), Collections.singletonList(col(0, 0)), t + 1);
        assertTrue(m.nextColumns(8).isEmpty(), "an unchanged column was not credited with having been read");
    }

    @Test
    void theSweepCanBeTurnedOff() {
        SectionPos pos = p(0, 4, 0);
        hold(pos);
        m.setHotVerification(0, 0);
        m.retarget(Collections.singletonList(pos), Collections.singletonList(col(0, 0)), 10_000L);
        // The general sweep may still pick it up; disable that too to isolate.
        m.setVerification(0, 0);
        m.retarget(Collections.singletonList(pos), Collections.singletonList(col(0, 0)), 10_001L);
        assertTrue(m.nextColumns(8).isEmpty(), "the sweep still ran with both intervals at zero");
    }
}
