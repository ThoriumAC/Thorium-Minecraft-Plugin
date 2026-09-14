package ac.thorium.mc.plugin.telemetry;

/** Block classification by Material.name(), version-agnostic (1.8 legacy names through modern names). */
public final class BlockClassifier {
    private BlockClassifier() {}
    private static String n(String s) { return s == null ? "" : s; }
    public static boolean isIce(String s) { return n(s).endsWith("ICE"); }
    public static boolean isSlime(String s) { return "SLIME_BLOCK".equals(s); }
    public static boolean isSoulSand(String s) { return "SOUL_SAND".equals(s) || "SOUL_SOIL".equals(s); }
    public static boolean isClimbable(String s) { String x = n(s); return x.equals("LADDER") || x.equals("VINE") || x.endsWith("_VINES") || x.equals("SCAFFOLDING"); }
    public static boolean isWeb(String s) { return "WEB".equals(s) || "COBWEB".equals(s); }
    public static boolean isWater(String s, boolean liquid) { String x = n(s); return (liquid && !x.contains("LAVA")) || x.equals("BUBBLE_COLUMN") || x.equals("KELP") || x.equals("KELP_PLANT") || x.endsWith("SEAGRASS"); }
    public static boolean isLava(String s, boolean liquid) { return liquid && n(s).contains("LAVA"); }
}
