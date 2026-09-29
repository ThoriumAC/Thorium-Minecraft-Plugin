package ac.thorium.mc.plugin.transport;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounds Java-WebSocket's unbounded outQueue when the gateway stops reading. */
class EngineBackpressureTest {
    private static List<ByteBuffer> queue(int count, int size) {
        List<ByteBuffer> q = new ArrayList<>();
        for (int i = 0; i < count; i++) q.add(ByteBuffer.allocate(size));
        return q;
    }

    @Test
    void anEmptyOrModestQueueTakesMoreFrames() {
        assertFalse(EngineConnection.backedUp(Collections.<ByteBuffer>emptyList()));
        assertFalse(EngineConnection.backedUp(queue(8, 192 * 1024)));
        // No connection: nothing to drop.
        assertFalse(EngineConnection.backedUp(null));
    }

    @Test
    void bytesCeilingStopsWorldFramesPilingUp() {
        // World chunk frames are up to 192 KiB each; 8 MiB of them is ~43.
        int frames = EngineConnection.MAX_QUEUED_BYTES / (192 * 1024) + 1;
        assertTrue(EngineConnection.backedUp(queue(frames, 192 * 1024)));
    }

    @Test
    void frameCeilingStopsSmallFramesPilingUp() {
        // Small frames hit the entry cap long before the byte cap.
        assertTrue(EngineConnection.backedUp(queue(EngineConnection.MAX_QUEUED_FRAMES, 32)));
        assertFalse(EngineConnection.backedUp(queue(EngineConnection.MAX_QUEUED_FRAMES - 1, 32)));
    }

    @Test
    void aPartlyDrainedBufferCountsOnlyWhatIsLeft() {
        List<ByteBuffer> q = queue(1, 64);
        q.get(0).position(64);   // already written by the socket's write thread
        assertFalse(EngineConnection.backedUp(q));
    }
}
