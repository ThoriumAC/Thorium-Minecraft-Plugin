package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.WorldBorder;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerInitializeWorldBorder;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWorldBorderCenter;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWorldBorderSize;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayWorldBorderLerpSize;

public final class WorldBorderDecoder {
    private WorldBorderDecoder() {}

    public static WorldBorder.Builder decode(PacketType.Play.Server t, PacketSendEvent event) {
        WorldBorder.Builder b = WorldBorder.newBuilder();
        if (t == PacketType.Play.Server.INITIALIZE_WORLD_BORDER) {
            WrapperPlayServerInitializeWorldBorder w = new WrapperPlayServerInitializeWorldBorder(event);
            return b.setCx(w.getX()).setCz(w.getZ()).setSize(w.getOldDiameter()).setNewSize(w.getNewDiameter()).setLerpMs(w.getSpeed());
        }
        if (t == PacketType.Play.Server.WORLD_BORDER_CENTER) {
            WrapperPlayServerWorldBorderCenter w = new WrapperPlayServerWorldBorderCenter(event);
            return b.setCx(w.getX()).setCz(w.getZ());
        }
        if (t == PacketType.Play.Server.WORLD_BORDER_SIZE) {
            WrapperPlayServerWorldBorderSize w = new WrapperPlayServerWorldBorderSize(event);
            return b.setSize(w.getDiameter()).setNewSize(w.getDiameter());
        }
        WrapperPlayWorldBorderLerpSize w = new WrapperPlayWorldBorderLerpSize(event);
        return b.setSize(w.getOldDiameter()).setNewSize(w.getNewDiameter()).setLerpMs(w.getSpeed());
    }
}
