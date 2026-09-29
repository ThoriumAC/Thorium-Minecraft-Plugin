package ac.thorium.mc.plugin;

import ac.thorium.mc.proto.DownStream;
import ac.thorium.mc.proto.Hello;
import ac.thorium.mc.proto.HelloAck;
import ac.thorium.mc.proto.ServerSoftware;
import ac.thorium.mc.proto.UpStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtoContractTest {
    @Test
    void upstreamHelloRoundTrips() throws Exception {
        UpStream up = UpStream.newBuilder().setHello(Hello.newBuilder()
                .setPluginVersion("0.1.0").setMcVersion("1.21.4").setProtocol(1)
                .setSoftware(ServerSoftware.SERVER_SOFTWARE_PAPER)).build();
        byte[] bytes = up.toByteArray();
        assertEquals(0x0A, bytes[0] & 0xFF);
        assertEquals("1.21.4", UpStream.parseFrom(bytes).getHello().getMcVersion());
    }

    @Test
    void downstreamHelloAckParses() throws Exception {
        byte[] bytes = DownStream.newBuilder().setHelloAck(HelloAck.newBuilder().setAccepted(true)).build().toByteArray();
        assertTrue(DownStream.parseFrom(bytes).getHelloAck().getAccepted());
    }
}
