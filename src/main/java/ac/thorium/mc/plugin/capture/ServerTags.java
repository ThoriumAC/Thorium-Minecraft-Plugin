package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.Reflect;
import ac.thorium.mc.proto.BlockTag;
import ac.thorium.mc.proto.BlockTags;

import java.lang.reflect.Method;

// The server's block tags through Bukkit's tag API (1.13+), which carries the
// datapacks' edits the same as the tags every client receives.
public final class ServerTags {
    private ServerTags() {}

    private static final Method GET_TAGS = Reflect.method(org.bukkit.Bukkit.class, "getTags", String.class, Class.class);
    private static final Method VALUES = Reflect.method("org.bukkit.Tag", "getValues");
    private static final Method KEY = Reflect.method("org.bukkit.Keyed", "getKey");

    public static BlockTags blocks() {
        if (GET_TAGS == null || VALUES == null || KEY == null) return null;
        try {
            BlockTags.Builder b = BlockTags.newBuilder();
            for (Object tag : (Iterable<?>) GET_TAGS.invoke(null, "blocks", org.bukkit.Material.class)) {
                BlockTag.Builder t = BlockTag.newBuilder().setName(String.valueOf(KEY.invoke(tag)));
                for (Object m : (Iterable<?>) VALUES.invoke(tag)) t.addBlocks(String.valueOf(KEY.invoke(m)));
                b.addTags(t);
            }
            return b.build();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
