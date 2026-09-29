package ac.thorium.mc.plugin.transport;

import java.util.Locale;

public final class FrameDiscriminator {
    public static final int GATEWAY_HEARTBEAT = 0x01, GATEWAY_MOD_UPDATE = 0x02, GATEWAY_RECONNECT = 0x03, GATEWAY_MESSAGE = 0x04;

    private FrameDiscriminator() {}

    public static boolean isGatewayControl(byte[] frame) { return frame != null && frame.length > 0 && (frame[0] & 0xFF) < 0x08; }

    public static int opcode(byte[] frame) { return frame == null || frame.length == 0 ? -1 : frame[0] & 0xFF; }

    public static boolean isResyncText(String text) { return text != null && text.toLowerCase(Locale.ROOT).contains("resync"); }
}
