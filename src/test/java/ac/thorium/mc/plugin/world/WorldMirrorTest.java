package ac.thorium.mc.plugin.world;

import ac.thorium.mc.proto.SectionPos;
import ac.thorium.mc.proto.UpStream;
import ac.thorium.mc.proto.WorldChunk;
import ac.thorium.mc.proto.WorldDelta;
import ac.thorium.mc.proto.WorldPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldMirrorTest {
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

    private List<UpStream> drain() { return m.drain(1000L, 42L, 0); }

    private static WorldChunk chunkOf(List<UpStream> frames) {
        for (UpStream u : frames) if (u.hasWorldChunk()) return u.getWorldChunk();
        return null;
    }

    private static WorldDelta deltaOf(List<UpStream> frames) {
        for (UpStream u : frames) if (u.hasWorldDelta()) return u.getWorldDelta();
        return null;
    }

    @Test
    void disabledByDefault() {
        WorldMirror off = new WorldMirror();
        assertFalse(off.enabled());
        assertEquals(0, off.retarget(Collections.singletonList(p(0, 0, 0))));
        assertTrue(off.drain(0, 0, 0).isEmpty());
    }

    @Test
    void policyAppliesLimits() {
        m.setPolicy(WorldPolicy.newBuilder().setEnabled(true).setRadiusChunks(7).setColumnsPerTick(5).build());
        assertEquals(7, m.radiusChunks());
        assertEquals(5, m.columnsPerTick());
        // Absurd values are clamped, not trusted.
        m.setPolicy(WorldPolicy.newBuilder().setEnabled(true).setRadiusChunks(9999).setColumnsPerTick(9999).build());
        assertEquals(16, m.radiusChunks());
        assertEquals(64, m.columnsPerTick());
    }

    @Test
    void enablingArmsAFullSync() {
        assertTrue(m.syncing());
        List<UpStream> frames = drain();
        WorldChunk c = chunkOf(frames);
        assertNotNull(c);
        assertTrue(c.getFullSyncStart());
    }

    @Test
    void retargetQueuesColumnsForUnheldSections() {
        // Three sections stacked in one column plus one in another: two columns.
        assertEquals(2, m.retarget(Arrays.asList(p(0, 0, 0), p(0, 1, 0), p(0, 2, 0), p(5, 0, 5))));
        List<WorldMirror.ColumnPos> got = m.nextColumns(10);
        assertEquals(2, got.size());
        assertEquals(0, m.pendingColumnCount());
    }

    @Test
    void nextColumnsHonoursTheBudget() {
        m.retarget(Arrays.asList(p(0, 0, 0), p(1, 0, 0), p(2, 0, 0), p(3, 0, 0)));
        assertEquals(2, m.nextColumns(2).size());
        assertEquals(2, m.pendingColumnCount());
        assertEquals(2, m.nextColumns(99).size());
        assertEquals(0, m.pendingColumnCount());
    }

    @Test
    void wantedInFiltersToTheColumn() {
        m.retarget(Arrays.asList(p(0, 0, 0), p(0, 3, 0), p(1, 0, 0)));
        List<SectionPos> in = m.wantedIn(new WorldMirror.ColumnPos("overworld", 0, 0));
        assertEquals(2, in.size());
        for (SectionPos s : in) assertEquals(0, s.getX());
    }

    @Test
    void acceptedSectionIsSentOnce() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        assertTrue(m.acceptSection(p(0, 0, 0), data("minecraft:stone")));
        WorldChunk c = chunkOf(drain());
        assertNotNull(c);
        assertEquals(1, c.getSectionsCount());
        assertEquals("minecraft:stone", c.getSections(0).getPalette(0));
        // Nothing new to say the second time around.
        assertNull(chunkOf(drain()));
    }

    @Test
    void unchangedResnapshotCostsNothing() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        assertFalse(m.acceptSection(p(0, 0, 0), data("minecraft:stone")));
        assertEquals(0, m.outboxSize());
    }

    @Test
    void changedResnapshotResends() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        assertTrue(m.acceptSection(p(0, 0, 0), data("minecraft:dirt")));
        WorldChunk c = chunkOf(drain());
        assertNotNull(c);
        assertEquals("minecraft:dirt", c.getSections(0).getPalette(0));
    }

    @Test
    void unwantedSectionIsNotAccepted() {
        assertFalse(m.acceptSection(p(9, 9, 9), data("minecraft:stone")));
    }

    @Test
    void blockChangeBecomesADelta() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.recordChange(p(0, 0, 0), SectionData.offset(1, 2, 3), "minecraft:dirt");
        WorldDelta d = deltaOf(drain());
        assertNotNull(d);
        assertEquals(1, d.getSectionsCount());
        assertEquals(SectionData.offset(1, 2, 3), d.getSections(0).getChanges(0).getOffset());
        assertEquals("minecraft:dirt", d.getSections(0).getChanges(0).getBlock());
        assertEquals(1000L, d.getClientTimeMs());
        assertEquals(42L, d.getTick());
    }

    @Test
    void changeToAnUnheldSectionIsIgnored() {
        m.recordChange(p(0, 0, 0), 0, "minecraft:dirt");
        assertNull(deltaOf(drain()));
    }

    @Test
    void repeatedChangesToOneBlockCollapse() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.recordChange(p(0, 0, 0), 5, "minecraft:dirt");
        m.recordChange(p(0, 0, 0), 5, "minecraft:sand");
        WorldDelta d = deltaOf(drain());
        assertEquals(1, d.getSections(0).getChangesCount());
        assertEquals("minecraft:sand", d.getSections(0).getChanges(0).getBlock());
    }

    @Test
    void outOfRangeOffsetIsRejected() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.recordChange(p(0, 0, 0), -1, "x");
        m.recordChange(p(0, 0, 0), SectionData.BLOCKS, "x");
        assertNull(deltaOf(drain()));
    }

    @Test
    void snapshotSupersedesQueuedChanges() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.recordChange(p(0, 0, 0), 5, "minecraft:dirt");
        // A re-snapshot read the world after that change, so the queued delta is stale.
        m.acceptSection(p(0, 0, 0), data("minecraft:sand"));
        List<UpStream> frames = drain();
        assertNotNull(chunkOf(frames));
        assertNull(deltaOf(frames));
    }

    @Test
    void sectionsAreSentBeforeTheDeltasThatFollowThem() {
        m.retarget(Arrays.asList(p(0, 0, 0), p(1, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.recordChange(p(0, 0, 0), 5, "minecraft:dirt");
        m.acceptSection(p(1, 0, 0), data("minecraft:stone"));
        List<UpStream> frames = drain();
        assertEquals(2, frames.size());
        assertTrue(frames.get(0).hasWorldChunk(), "chunk frame must come first");
        assertTrue(frames.get(1).hasWorldDelta(), "delta frame must come second");
    }

    @Test
    void droppingASectionOutOfRangeTellsTheEngine() {
        m.retarget(Arrays.asList(p(0, 0, 0), p(1, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        m.acceptSection(p(1, 0, 0), data("minecraft:stone"));
        drain();
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        WorldDelta d = deltaOf(drain());
        assertNotNull(d);
        assertEquals(1, d.getDroppedCount());
        assertEquals(p(1, 0, 0), d.getDropped(0));
        assertEquals(1, m.heldSections());
    }

    @Test
    void aDroppedSectionIsResentWhenItComesBack() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.retarget(Collections.<SectionPos>emptyList());
        drain();
        assertEquals(1, m.retarget(Collections.singletonList(p(0, 0, 0))));
        assertTrue(m.acceptSection(p(0, 0, 0), data("minecraft:stone")));
    }

    @Test
    void fullSyncIsNotDoneBeforeTheFirstRetarget() {
        // The flush worker drains every 75 ms; the sampler retargets every 10 ticks.
        // The first drain therefore lands on an empty queue, and must not call that
        // a completed sync of zero sections.
        WorldChunk c = chunkOf(drain());
        assertNotNull(c);
        assertTrue(c.getFullSyncStart());
        assertFalse(c.getFullSyncDone(), "sync completed before anything was targeted");
        assertTrue(m.syncing());
    }

    @Test
    void fullSyncCompletesOnceEverythingIsOut() {
        m.retarget(Arrays.asList(p(0, 0, 0), p(1, 0, 0)));
        assertTrue(m.syncing());
        // Still columns waiting: not done yet.
        m.nextColumns(1);
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        WorldChunk first = chunkOf(drain());
        assertTrue(first.getFullSyncStart());
        assertFalse(first.getFullSyncDone());
        // Last column snapshotted and drained: done.
        m.nextColumns(1);
        m.acceptSection(p(1, 0, 0), data("minecraft:stone"));
        WorldChunk second = chunkOf(drain());
        assertTrue(second.getFullSyncDone());
        assertFalse(m.syncing());
    }

    @Test
    void byteBudgetSplitsSectionsAcrossFrames() {
        // Sections with big palettes so each is comfortably over a small budget.
        for (int i = 0; i < 4; i++) {
            SectionPos pos = p(i, 0, 0);
            m.retarget(Arrays.asList(p(0, 0, 0), p(1, 0, 0), p(2, 0, 0), p(3, 0, 0)));
            String[] a = new String[SectionData.BLOCKS];
            for (int j = 0; j < a.length; j++) a[j] = "minecraft:block_" + i + "_" + (j % 300);
            m.acceptSection(pos, SectionData.of(a));
        }
        WorldChunk c = chunkOf(m.drain(0, 0, 1024));
        assertEquals(1, c.getSectionsCount(), "one oversized section still goes out alone");
        assertTrue(m.outboxSize() > 0, "the rest stay queued");
    }

    @Test
    void changesAfterTheSnapshotTokenSurvive() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        // The owning thread stamps the read, then a change lands while the decode
        // is still on a worker thread. That change is not in the bytes being decoded.
        long token = m.snapshotToken();
        m.recordChange(p(0, 0, 0), 5, "minecraft:dirt");
        m.acceptSection(p(0, 0, 0), data("minecraft:sand"), token);

        List<UpStream> frames = drain();
        assertNotNull(chunkOf(frames));
        WorldDelta d = deltaOf(frames);
        assertNotNull(d, "a change recorded after the token must not be discarded");
        assertEquals("minecraft:dirt", d.getSections(0).getChanges(0).getBlock());
    }

    @Test
    void changesBeforeTheSnapshotTokenAreDiscarded() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.recordChange(p(0, 0, 0), 5, "minecraft:dirt");
        // Token taken after the change: the snapshot already contains it.
        long token = m.snapshotToken();
        m.acceptSection(p(0, 0, 0), data("minecraft:sand"), token);
        assertNull(deltaOf(drain()));
    }

    @Test
    void tokenKeepsOnlyTheNewerOfTwoChangesToOneBlock() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.recordChange(p(0, 0, 0), 5, "minecraft:dirt");
        long token = m.snapshotToken();
        m.recordChange(p(0, 0, 0), 9, "minecraft:sand");
        m.acceptSection(p(0, 0, 0), data("minecraft:gravel"), token);

        WorldDelta d = deltaOf(drain());
        assertNotNull(d);
        assertEquals(1, d.getSections(0).getChangesCount());
        assertEquals(9, d.getSections(0).getChanges(0).getOffset());
    }

    @Test
    void heldSectionsAreNotRereadUntilTheyGoStale() {
        m.setVerification(100, 8);
        m.retarget(Collections.singletonList(p(0, 0, 0)), 0);
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        // Fresh: nothing to do.
        assertEquals(0, m.retarget(Collections.singletonList(p(0, 0, 0)), 50));
        // Past the interval: queued for a re-read.
        assertEquals(1, m.retarget(Collections.singletonList(p(0, 0, 0)), 200));
    }

    @Test
    void reverifyingUnchangedTerrainSendsNothing() {
        m.setVerification(100, 8);
        m.retarget(Collections.singletonList(p(0, 0, 0)), 0);
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.retarget(Collections.singletonList(p(0, 0, 0)), 200);
        assertFalse(m.acceptSection(p(0, 0, 0), data("minecraft:stone")));
        assertNull(chunkOf(drain()));
    }

    @Test
    void reverifyingRefreshesStalenessEvenWhenUnchanged() {
        // Without this a never-changing section would be re-read on every sweep forever.
        m.setVerification(100, 8);
        m.retarget(Collections.singletonList(p(0, 0, 0)), 0);
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.retarget(Collections.singletonList(p(0, 0, 0)), 200);
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));   // identical content
        assertEquals(0, m.retarget(Collections.singletonList(p(0, 0, 0)), 250),
                "an unchanged re-read must still count as verification");
    }

    @Test
    void verificationIsBoundedPerRetarget() {
        m.setVerification(100, 3);
        List<SectionPos> want = new ArrayList<>();
        for (int i = 0; i < 20; i++) want.add(p(i, 0, 0));
        m.retarget(want, 0);
        for (SectionPos s : want) m.acceptSection(s, data("minecraft:stone"));
        drain();
        // All 20 are stale, but only the budget may be queued.
        assertEquals(3, m.retarget(want, 500));
    }

    @Test
    void verificationCanBeDisabled() {
        m.setVerification(0, 8);
        m.retarget(Collections.singletonList(p(0, 0, 0)), 0);
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        assertEquals(0, m.retarget(Collections.singletonList(p(0, 0, 0)), 100000));
    }

    @Test
    void invalidatedSectionIsRereadImmediately() {
        // Events that do fire must not wait for the staleness sweep.
        m.setVerification(100000, 8);
        m.retarget(Collections.singletonList(p(0, 0, 0)), 0);
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.invalidate(p(0, 0, 0));
        assertEquals(1, m.retarget(Collections.singletonList(p(0, 0, 0)), 1));
    }

    @Test
    void resetArmsAnotherFullSync() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        drain();
        m.reset();
        assertEquals(0, m.heldSections());
        assertTrue(m.syncing());
        assertTrue(chunkOf(drain()).getFullSyncStart());
    }

    @Test
    void disablingClearsEverything() {
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        m.setPolicy(WorldPolicy.newBuilder().setEnabled(false).build());
        assertFalse(m.enabled());
        assertEquals(0, m.heldSections());
        assertTrue(m.drain(0, 0, 0).isEmpty());
    }

    @Test
    void hasWorkTracksPendingOutput() {
        drain();                         // clear the armed full sync
        assertFalse(m.hasWork());
        m.retarget(Collections.singletonList(p(0, 0, 0)));
        m.acceptSection(p(0, 0, 0), data("minecraft:stone"));
        assertTrue(m.hasWork());
        drain();
        assertFalse(m.hasWork());
    }

    /**
     * The engine is told where a world ends, once, and again whenever it is about
     * to drop its model.
     *
     * <p>Without this the engine cannot tell a section it was never sent from one
     * that cannot exist, so a player under the world floor is a player nothing
     * judges - and that is precisely where a ground spoof stands.
     */
    @Test
    void boundsAreSentOnceAndResentOnAFullSync() {
        m.bounds("overworld", -64, 320);
        WorldChunk first = chunkOf(drain());
        assertNotNull(first, "no frame carried the bounds");
        assertEquals(1, first.getBoundsCount());
        assertEquals("overworld", first.getBounds(0).getDimension());
        assertEquals(-64, first.getBounds(0).getMinY());
        assertEquals(320, first.getBounds(0).getMaxY());

        // Unchanged bounds are read off every snapshot; they must not be resent.
        m.bounds("overworld", -64, 320);
        assertNull(chunkOf(drain()), "resent bounds that had not changed");

        // A fresh sync drops the engine's model, so it needs them again.
        m.reset();
        WorldChunk again = chunkOf(drain());
        assertNotNull(again);
        assertTrue(again.getFullSyncStart());
        assertEquals(1, again.getBoundsCount());

        // A second world is its own entry, and both go together.
        m.bounds("the_nether", 0, 256);
        WorldChunk both = chunkOf(drain());
        assertNotNull(both);
        assertEquals(2, both.getBoundsCount());
    }

    /**
     * Nonsense bounds are dropped rather than put on the wire. The frame itself
     * still goes: enabling the mirror armed a full sync, and that is not this
     * test's business.
     */
    @Test
    void boundsMustDescribeARealWorld() {
        m.bounds("", -64, 320);
        m.bounds("overworld", 320, 320);
        m.bounds(null, -64, 320);
        WorldChunk c = chunkOf(drain());
        assertNotNull(c);
        assertEquals(0, c.getBoundsCount(), "sent bounds for a world that cannot exist");
    }
}
