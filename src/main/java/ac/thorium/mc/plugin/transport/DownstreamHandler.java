package ac.thorium.mc.plugin.transport;

import ac.thorium.mc.proto.HelloAck;
import ac.thorium.mc.proto.IngestPolicy;
import ac.thorium.mc.proto.Mitigate;
import ac.thorium.mc.proto.NetworkConfig;
import ac.thorium.mc.proto.PluginUpdate;
import ac.thorium.mc.proto.Verdict;

public interface DownstreamHandler {
    void onHelloAck(HelloAck ack);
    void onVerdict(Verdict verdict);
    void onMitigate(Mitigate mitigate);
    default void onConfig(NetworkConfig config) {}
    void onPolicy(IngestPolicy policy);
    void onPluginUpdate(PluginUpdate update);
    void onGatewayControl(int opcode, byte[] payload);
    void onStateChange(ConnectionState from, ConnectionState to);
    void onDisconnected();
}
