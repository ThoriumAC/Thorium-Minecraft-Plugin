package ac.thorium.mc.plugin.capture;

final class DamageCap {
    private DamageCap() {}

    static double hearts(double damage, double health) {
        if (!(damage > 0)) return 0;
        return (health >= 0 ? Math.min(damage, health) : damage) / 2.0;
    }
}
