package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Reflect;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.compat.ServerCompat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Refreshes an immutable PlayerContext for every tracked player once per tick on the player's owning thread. */
public final class ContextTracker {
    private static final Method IS_GLIDING = Reflect.method(Player.class, "isGliding");
    private static final Method IS_RIPTIDING = Reflect.method(Player.class, "isRiptiding");
    private static final Enchantment FROST_WALKER = frostWalker();

    private static Enchantment frostWalker() {
        try { return Enchantment.getByName("FROST_WALKER"); } catch (Throwable t) { return null; }
    }

    private final Scheduler sched;
    private final ServerCompat compat;
    private final ErrorGate gate;
    private final LongSupplier tick;
    /** True while the engine mirrors the world and derives the block flags itself. */
    private final BooleanSupplier worldStreamed;
    private final Map<UUID, PlayerContext> contexts = new ConcurrentHashMap<>();
    /** Server-thread nanos spent building contexts, and how many were built. */
    private final java.util.concurrent.atomic.AtomicLong snapNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong snapCount = new java.util.concurrent.atomic.AtomicLong();
    private final Map<UUID, Object> tasks = new ConcurrentHashMap<>();

    public ContextTracker(Scheduler sched, ServerCompat compat, ErrorGate gate, LongSupplier tick, BooleanSupplier worldStreamed) {
        this.sched = sched; this.compat = compat; this.gate = gate; this.tick = tick; this.worldStreamed = worldStreamed;
    }

    public void start(Player p) {
        UUID id = p.getUniqueId();
        stop(p);
        contexts.put(id, PlayerContext.UNKNOWN);
        Object handle = sched.runPlayerTimer(p, () -> gate.run("context", () -> {
            if (!p.isOnline()) { stop(p); return; }
            long t0 = System.nanoTime();
            PlayerContext ctx = snapshot(p);
            snapNanos.addAndGet(System.nanoTime() - t0);
            snapCount.incrementAndGet();
            contexts.put(id, ctx);
        }), 1, 1);
        if (handle != null) tasks.put(id, handle);
    }

    public void stop(Player p) {
        UUID id = p.getUniqueId();
        Object h = tasks.remove(id);
        if (h != null) sched.cancel(h);
        contexts.remove(id);
    }

    public void stopAll() {
        for (Object h : tasks.values()) sched.cancel(h);
        tasks.clear();
        contexts.clear();
    }

    public PlayerContext get(UUID uuid) { PlayerContext c = contexts.get(uuid); return c == null ? PlayerContext.UNKNOWN : c; }

    /** Mean nanoseconds of server-thread time per context refresh; 0 if none yet. */
    public long contextNanos() {
        long n = snapCount.get();
        return n == 0 ? 0 : snapNanos.get() / n;
    }

    public long contextCount() { return snapCount.get(); }

    /** A living entity or vehicle close enough to push the player client-side. */
    private static boolean pushableNearby(Player p) {
        try {
            for (Entity e : p.getNearbyEntities(0.9, 1.2, 0.9)) {
                if (e instanceof LivingEntity || e instanceof Vehicle) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Bukkit reads; must run on the player's thread. */
    public PlayerContext snapshot(Player p) {
        PlayerContext.Builder b = PlayerContext.builder();
        Location loc = p.getLocation();
        b.tick(tick.getAsLong()).x(loc.getX()).y(loc.getY()).z(loc.getZ()).yaw(loc.getYaw()).pitch(loc.getPitch());
        b.gamemode(Names.gamemode(p.getGameMode().name())).dimension(Names.dimension(p.getWorld().getEnvironment().name()));
        // The mirror keys sections by world name, so the engine needs it to look the player up.
        b.world(p.getWorld().getName());
        b.pingMs(compat.ping(p)).tps(compat.tps());
        b.inVehicle(p.isInsideVehicle()).flyingAllowed(p.getAllowFlight()).isFlying(p.isFlying()).dead(p.isDead()).sleeping(p.isSleeping());
        b.gliding(Reflect.bool(IS_GLIDING, p, false)).riptiding(Reflect.bool(IS_RIPTIDING, p, false));
        for (PotionEffect e : p.getActivePotionEffects()) {
            String n = e.getType() == null ? null : e.getType().getName();
            if (n == null) continue;
            n = n.toUpperCase(Locale.ROOT);
            if (n.equals("SPEED")) b.speedAmplifier(e.getAmplifier() + 1);
            else if (n.equals("JUMP") || n.equals("JUMP_BOOST")) b.jumpAmplifier(e.getAmplifier() + 1);
            else if (n.equals("LEVITATION")) b.levitation(true);
            else if (n.equals("SLOW_FALLING")) b.slowFalling(true);
        }
        ItemStack chest = p.getInventory().getChestplate();
        b.hasElytra(chest != null && chest.getType() != null && "ELYTRA".equals(chest.getType().name()));
        ItemStack boots = p.getInventory().getBoots();
        b.frostWalker(FROST_WALKER != null && boots != null && boots.containsEnchantment(FROST_WALKER));

        if (worldStreamed.getAsBoolean()) {
            // The engine has the blocks; every flag below is derived there instead.
            // Only the entity probe stays, since no block can answer it.
            b.pushableNearby(pushableNearby(p));
            return b.build();
        }

        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block aboveHead = feet.getRelative(0, 2, 0);
        String feetName = feet.getType().name(), headName = head.getType().name();
        b.inWater(BlockClassifier.isWater(feetName, feet.isLiquid()) || BlockClassifier.isWater(headName, head.isLiquid()));
        b.inLava(BlockClassifier.isLava(feetName, feet.isLiquid()) || BlockClassifier.isLava(headName, head.isLiquid()));
        b.onLadder(BlockClassifier.isClimbable(feetName)).inWeb(BlockClassifier.isWeb(feetName) || BlockClassifier.isWeb(headName));
        b.blockAboveSolid(aboveHead.getType().isSolid());
        boolean below = false, ice = false, slime = false, soul = false, liquid = false;
        double[] offs = {-0.3, 0.3};
        int uy = (int) Math.floor(loc.getY() - 0.001);
        for (double dx : offs) for (double dz : offs) {
            Block ub = loc.getWorld().getBlockAt((int) Math.floor(loc.getX() + dx), uy, (int) Math.floor(loc.getZ() + dz));
            Material m = ub.getType();
            String n = m.name();
            below |= m.isSolid();
            liquid |= ub.isLiquid();
            ice |= BlockClassifier.isIce(n); slime |= BlockClassifier.isSlime(n); soul |= BlockClassifier.isSoulSand(n);
        }
        b.blockBelowSolid(below).blockBelowLiquid(liquid).onIce(ice).onSlime(slime).onSoulSand(soul);
        // A solid block within 0.6 of the hitbox edge at feet or head height: the client may clip
        // its motion against it, so the engine's movement prediction skips those ticks.
        boolean wall = false;
        int fy = (int) Math.floor(loc.getY());
        for (double dx = -0.9; dx <= 0.9 && !wall; dx += 0.9) for (double dz = -0.9; dz <= 0.9 && !wall; dz += 0.9) {
            if (dx == 0 && dz == 0) continue;
            int bx = (int) Math.floor(loc.getX() + dx), bz = (int) Math.floor(loc.getZ() + dz);
            wall = loc.getWorld().getBlockAt(bx, fy, bz).getType().isSolid() || loc.getWorld().getBlockAt(bx, fy + 1, bz).getType().isSolid();
        }
        b.nearWall(wall);
        b.pushableNearby(pushableNearby(p));
        return b.build();
    }
}
