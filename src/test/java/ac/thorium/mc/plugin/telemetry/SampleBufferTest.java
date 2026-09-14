package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.*;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SampleBufferTest {
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();
    static PlayerRef ref(String name) { return PlayerRef.newBuilder().setUuid(ByteString.copyFrom(new byte[16])).setUsername(name).build(); }
    static Sample movement(long tick) { return Sample.newBuilder().setTick(tick).setMovement(MovementSample.newBuilder().setOnGround(true)).build(); }
    static Sample combat() { return Sample.newBuilder().setCombat(CombatSample.newBuilder().setAction(CombatAction.COMBAT_ACTION_ATTACK)).build(); }

    @Test void drainsOnePlayerBatchPerPlayerInOrder() {
        SampleBuffer buf = new SampleBuffer(400);
        assertNull(buf.drain(1));
        buf.add(A, ref("a"), movement(1)); buf.add(B, ref("b"), movement(1)); buf.add(A, ref("a"), movement(2));
        assertEquals(3, buf.pending());
        Batch b = buf.drain(1234);
        assertEquals(1234, b.getSentAtMs());
        assertEquals(2, b.getPlayersCount());
        PlayerBatch pa = b.getPlayers(0).getPlayer().getUsername().equals("a") ? b.getPlayers(0) : b.getPlayers(1);
        assertEquals(2, pa.getSamplesCount());
        assertEquals(1, pa.getSamples(0).getTick()); assertEquals(2, pa.getSamples(1).getTick());
        assertNull(buf.drain(2));
    }

    @Test void capsPerPlayerAndFilters() {
        SampleBuffer buf = new SampleBuffer(3);
        for (int i = 1; i <= 5; i++) buf.add(A, ref("a"), movement(i));
        assertEquals(2, buf.dropped());
        assertEquals(3, buf.drain(0).getPlayers(0).getSamples(0).getTick());
        buf.setEnabledCategories(Arrays.asList("combat"));
        assertFalse(buf.add(A, ref("a"), movement(1)));
        assertTrue(buf.add(A, ref("a"), combat()));
        assertEquals("movement", SampleBuffer.category(Sample.newBuilder().setRotation(RotationSample.newBuilder()).build()));
    }

    @Test void captureWriterFormat() throws Exception {
        java.io.File f = java.io.File.createTempFile("cap", ".bin");
        CaptureWriter w = new CaptureWriter(f);
        UpStream u = UpStream.newBuilder().setHeartbeat(Heartbeat.newBuilder().setTps(20)).build();
        w.write(u); w.write(u); w.close();
        assertEquals(2, w.frames());
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f))) {
            for (int i = 0; i < 2; i++) { byte[] b = new byte[in.readInt()]; in.readFully(b); assertEquals(20.0, UpStream.parseFrom(b).getHeartbeat().getTps(), 0); }
            assertEquals(-1, in.read());
        }
        f.delete();
    }

    @Test void movedCounterAndMeta() {
        MovedCounter c = new MovedCounter(3);
        assertFalse(c.consume()); c.mark();
        assertTrue(c.consume() && c.consume() && c.consume()); assertFalse(c.consume());
        ServerMeta m = MetaBuilder.build(PlayerContext.builder().gamemode(2).pingMs(83).onIce(true).speedAmplifier(2).build(), true);
        assertEquals(2, m.getGamemode()); assertEquals(83, m.getPingMs()); assertTrue(m.getOnIce()); assertTrue(m.getServerMoved()); assertEquals(2, m.getSpeedAmplifier());
        assertEquals(-1, MetaBuilder.build(PlayerContext.UNKNOWN, false).getPingMs());
    }
}
