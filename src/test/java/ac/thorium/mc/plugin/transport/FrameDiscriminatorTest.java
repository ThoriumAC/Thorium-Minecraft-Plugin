package ac.thorium.mc.plugin.transport;

import ac.thorium.mc.proto.DownStream;
import ac.thorium.mc.proto.HelloAck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameDiscriminatorTest {
    @Test
    void gatewayOpcodesAreControl() {
        for (int op = 0x00; op < 0x08; op++) {
            assertTrue(FrameDiscriminator.isGatewayControl(new byte[]{(byte) op, 1, 2}));
            assertEquals(op, FrameDiscriminator.opcode(new byte[]{(byte) op}));
        }
    }

    @Test
    void protobufFramesAreNotControl() {
        byte[] ack = DownStream.newBuilder().setHelloAck(HelloAck.newBuilder().setAccepted(true)).build().toByteArray();
        assertFalse(FrameDiscriminator.isGatewayControl(ack));
        assertFalse(FrameDiscriminator.isGatewayControl(new byte[]{(byte) 0x08}));
        assertFalse(FrameDiscriminator.isGatewayControl(new byte[]{(byte) 0xFF}));
    }

    @Test
    void emptyFrame() {
        assertFalse(FrameDiscriminator.isGatewayControl(new byte[0]));
        assertEquals(-1, FrameDiscriminator.opcode(new byte[0]));
    }

    @Test
    void resyncText() {
        assertTrue(FrameDiscriminator.isResyncText("{\"type\":\"resync\"}"));
        assertTrue(FrameDiscriminator.isResyncText("{ \"type\" : \"RESYNC\" }"));
        assertFalse(FrameDiscriminator.isResyncText("{\"type\":\"other\"}"));
        assertFalse(FrameDiscriminator.isResyncText(null));
    }
}
