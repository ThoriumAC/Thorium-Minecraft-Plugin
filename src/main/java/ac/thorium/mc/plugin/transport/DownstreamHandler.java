package ac.thorium.mc.plugin.transport;

import ac.thorium.mc.proto.HelloAck;
import ac.thorium.mc.proto.IngestPolicy;
import ac.thorium.mc.proto.PluginUpdate;
import ac.thorium.mc.proto.Verdict;

public interface DownstreamHandler {
    void onHelloAck(HelloAck ack);
    void onVerdict(Verdict verdict);
    /** From HelloAck.policy and Control.policy. */
    void onPolicy(IngestPolicy policy);
    void onPluginUpdate(PluginUpdate update);
    void onGatewayControl(int opcode, byte[] payload);
    void onStateChange(ConnectionState from, ConnectionState to);
    /** Socket dropped after READY; buffered telemetry must be discarded. */
    void onDisconnected();
}
