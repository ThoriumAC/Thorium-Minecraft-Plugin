package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.config.NetworkSettings;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.EngineConnection;
import ac.thorium.mc.proto.Activity;
import ac.thorium.mc.proto.ActivityEvent;
import ac.thorium.mc.proto.PlayerRef;
import ac.thorium.mc.proto.UpStream;
import ac.thorium.mc.proto.Vec3;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Server-confirmed player interactions for Advanced Analytics. Every handler runs at MONITOR
 * and ignores cancelled events, so only what actually happened is recorded. Nothing is queued
 * while the network has Advanced Analytics off. Chat and commands are never recorded.
 */
@SuppressWarnings("deprecation")
public final class ActivityEvents implements Listener {
    private static final int MAX_QUEUED = 20_000;
    private static final int MAX_PER_FRAME = 2_000;

    private final NetworkSettings settings;
    private final Supplier<EngineConnection> connection;
    private final Telemetry telemetry;
    private final ErrorGate gate;
    private final Queue<ActivityEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private Object timer;

    public ActivityEvents(NetworkSettings settings, Supplier<EngineConnection> connection, Telemetry telemetry, ErrorGate gate) {
        this.settings = settings; this.connection = connection; this.telemetry = telemetry; this.gate = gate;
    }

    public void start(Scheduler sched) { timer = sched.runGlobalTimer(() -> gate.run("activity:flush", this::flush), 20L, 20L); }

    public void stop(Scheduler sched) { if (timer != null) sched.cancel(timer); timer = null; queue.clear(); size.set(0); }

    void flush() {
        EngineConnection c = connection.get();
        while (!queue.isEmpty()) {
            List<ActivityEvent> batch = new ArrayList<>();
            ActivityEvent e;
            while (batch.size() < MAX_PER_FRAME && (e = queue.poll()) != null) batch.add(e);
            size.addAndGet(-batch.size());
            if (batch.isEmpty() || c == null) return;
            if (!c.send(UpStream.newBuilder().setActivity(Activity.newBuilder().addAllEvents(batch)).build())) return;
        }
    }

    private void record(Player p, String kind, String subject, String detail, Player target, Location at, double amount) {
        if (!settings.advancedAnalytics() || p == null) return;
        if (size.incrementAndGet() > MAX_QUEUED) { size.decrementAndGet(); return; }
        ActivityEvent.Builder b = ActivityEvent.newBuilder()
                .setPlayer(telemetry.ref(p)).setAtMs(System.currentTimeMillis()).setKind(kind)
                .setSubject(subject == null ? "" : subject).setDetail(detail == null ? "" : detail).setAmount(amount);
        if (target != null) b.setTarget(telemetry.ref(target));
        if (at != null && at.getWorld() != null) {
            b.setWorld(at.getWorld().getName()).setPosition(Vec3.newBuilder().setX(at.getX()).setY(at.getY()).setZ(at.getZ()));
        }
        queue.add(b.build());
    }

    public void custom(Player p, String kind, String key, double amount) {
        record(p, kind, key, "", null, p.getLocation(), amount);
    }

    private static Player playerOf(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Player) {
            return (Player) ((Projectile) damager).getShooter();
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        gate.run("activity:damage", () -> {
            double hearts = e.getFinalDamage() / 2.0;
            if (hearts <= 0) return;
            Player attacker = e instanceof EntityDamageByEntityEvent ? playerOf(((EntityDamageByEntityEvent) e).getDamager()) : null;
            Entity hurt = e.getEntity();
            Player victim = hurt instanceof Player ? (Player) hurt : null;
            if (victim != null) record(victim, "damage_taken", e.getCause().name(), "", attacker, hurt.getLocation(), hearts);
            if (attacker != null && attacker != victim) record(attacker, "damage_dealt", hurt.getType().name(), e.getCause().name(), victim, hurt.getLocation(), hearts);
        });
    }

    private static String type(ItemStack it) { return it == null ? "" : it.getType().name(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        gate.run("activity:break", () -> record(e.getPlayer(), "block_break", e.getBlock().getType().name(), type(e.getPlayer().getItemInHand()), null, e.getBlock().getLocation(), 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        gate.run("activity:place", () -> record(e.getPlayer(), "block_place", e.getBlockPlaced().getType().name(), "", null, e.getBlockPlaced().getLocation(), 1));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        gate.run("activity:death", () -> {
            LivingEntity dead = e.getEntity();
            Player killer = dead.getKiller();
            Player victim = dead instanceof Player ? (Player) dead : null;
            if (killer != null) record(killer, "kill", dead.getType().name(), type(killer.getItemInHand()), victim, dead.getLocation(), 1);
            if (victim != null) {
                EntityDamageEvent last = victim.getLastDamageCause();
                record(victim, "death", last == null ? "" : last.getCause().name(), killer == null ? "" : type(killer.getItemInHand()), killer, victim.getLocation(), 1);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        gate.run("activity:shoot", () -> {
            if (e.getEntity() instanceof Player) {
                Entity arrow = e.getProjectile();
                record((Player) e.getEntity(), "shoot", type(e.getBow()), arrow == null ? "" : arrow.getType().name(), null, e.getEntity().getLocation(), e.getForce());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        gate.run("activity:projectile", () -> {
            Projectile pr = e.getEntity();
            // Arrows from a bow are already recorded as a shot.
            if (pr.getShooter() instanceof Player && !(pr instanceof org.bukkit.entity.Arrow)) {
                record((Player) pr.getShooter(), "projectile", pr.getType().name(), "", null, pr.getLocation(), 1);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        gate.run("activity:consume", () -> record(e.getPlayer(), "consume", type(e.getItem()), "", null, e.getPlayer().getLocation(), 1));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent e) {
        gate.run("activity:use", () -> {
            ItemStack it = e.getItem();
            if (it == null) return;
            // Blocks going down are recorded by onPlace; this is for items used on their own.
            if (e.getAction() == Action.RIGHT_CLICK_AIR || (e.getAction() == Action.RIGHT_CLICK_BLOCK && !it.getType().isBlock())) {
                record(e.getPlayer(), "item_use", it.getType().name(), e.getClickedBlock() == null ? "" : e.getClickedBlock().getType().name(), null, e.getPlayer().getLocation(), 1);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        gate.run("activity:drop", () -> {
            ItemStack it = e.getItemDrop().getItemStack();
            record(e.getPlayer(), "drop", type(it), "", null, e.getPlayer().getLocation(), it == null ? 0 : it.getAmount());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent e) {
        gate.run("activity:pickup", () -> {
            ItemStack it = e.getItem().getItemStack();
            record(e.getPlayer(), "pickup", type(it), "", null, e.getPlayer().getLocation(), it == null ? 0 : it.getAmount());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        gate.run("activity:craft", () -> {
            if (e.getWhoClicked() instanceof Player) {
                ItemStack it = e.getRecipe().getResult();
                record((Player) e.getWhoClicked(), "craft", type(it), e.isShiftClick() ? "shift" : "", null, e.getWhoClicked().getLocation(), it == null ? 0 : it.getAmount());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        gate.run("activity:fish", () -> {
            if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
                Entity caught = e.getCaught();
                String what = caught instanceof org.bukkit.entity.Item ? type(((org.bukkit.entity.Item) caught).getItemStack()) : caught == null ? "" : caught.getType().name();
                record(e.getPlayer(), "fish", what, "", null, e.getPlayer().getLocation(), 1);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        gate.run("activity:enchant", () -> record(e.getEnchanter(), "enchant", type(e.getItem()), e.getEnchantsToAdd().keySet().stream().map(org.bukkit.enchantments.Enchantment::getName).collect(java.util.stream.Collectors.joining(",")), null, e.getEnchanter().getLocation(), e.getExpLevelCost()));
    }
}
