package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.PlayerRef;
import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;

public final class Names {
    private Names() {}

    public static int gamemode(String n) {
        if (n == null) return 0;
        switch (n) { case "CREATIVE": return 1; case "ADVENTURE": return 2; case "SPECTATOR": return 3; default: return 0; }
    }

    public static String dimension(String n) {
        if (n == null) return "";
        switch (n) { case "NORMAL": return "overworld"; case "NETHER": return "the_nether"; case "THE_END": return "the_end"; default: return n.toLowerCase(Locale.ROOT); }
    }

    public static String teleportCause(String n) { return n == null ? "unknown" : n.toLowerCase(Locale.ROOT); }

    public static PlayerRef ref(UUID u, String name, boolean cracked) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
        return PlayerRef.newBuilder().setUuid(ByteString.copyFrom(bb.array())).setUsername(name == null ? "" : name).setCracked(cracked).build();
    }

    public static UUID uuid(ByteString b) {
        if (b == null || b.size() != 16) return null;
        ByteBuffer bb = b.asReadOnlyByteBuffer();
        return new UUID(bb.getLong(), bb.getLong());
    }
}
