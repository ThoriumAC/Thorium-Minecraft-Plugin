package ac.thorium.mc.plugin.capture;

/** Damage as the stats site counts it: never more than the victim had left, so /kill and overkill hits stay sane. */
final class DamageCap {
    private DamageCap() {}

    static double hearts(double damage, double health) {
        if (!(damage > 0)) return 0;
        return (health >= 0 ? Math.min(damage, health) : damage) / 2.0;
    }
}
