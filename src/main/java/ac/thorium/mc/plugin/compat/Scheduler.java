package ac.thorium.mc.plugin.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Runs work on the right thread on Bukkit/Spigot/Paper (main thread) and Folia (region/entity/global schedulers). */
public final class Scheduler {
    private final Plugin plugin;
    private final boolean folia;
    private final Object globalScheduler, asyncScheduler;
    private final Method entityGetScheduler, entityRun, entityRunAtFixedRate;
    private final Method globalRun, globalRunAtFixedRate, globalCancelTasks;
    private final Method asyncRunNow, asyncCancelTasks;
    private final Method scheduledTaskCancel;

    private Scheduler(Plugin plugin, boolean folia) {
        this.plugin = plugin;
        this.folia = folia;
        if (folia) {
            Method getGlobal = Reflect.method(Bukkit.class, "getGlobalRegionScheduler");
            Method getAsync = Reflect.method(Bukkit.class, "getAsyncScheduler");
            globalScheduler = Reflect.invoke(getGlobal, null);
            asyncScheduler = Reflect.invoke(getAsync, null);
            // Look methods up on the public interfaces (impl classes may be package-private).
            Class<?> grs = getGlobal.getReturnType();
            Class<?> as = getAsync.getReturnType();
            globalRun = Reflect.method(grs, "run", Plugin.class, Consumer.class);
            globalRunAtFixedRate = Reflect.method(grs, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
            globalCancelTasks = Reflect.method(grs, "cancelTasks", Plugin.class);
            asyncRunNow = Reflect.method(as, "runNow", Plugin.class, Consumer.class);
            asyncCancelTasks = Reflect.method(as, "cancelTasks", Plugin.class);
            entityGetScheduler = Reflect.method(org.bukkit.entity.Entity.class, "getScheduler");
            Class<?> es = entityGetScheduler.getReturnType();
            entityRun = Reflect.method(es, "run", Plugin.class, Consumer.class, Runnable.class);
            entityRunAtFixedRate = Reflect.method(es, "runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
            scheduledTaskCancel = Reflect.method("io.papermc.paper.threadedregions.scheduler.ScheduledTask", "cancel");
        } else {
            globalScheduler = asyncScheduler = null;
            entityGetScheduler = entityRun = entityRunAtFixedRate = null;
            globalRun = globalRunAtFixedRate = globalCancelTasks = null;
            asyncRunNow = asyncCancelTasks = scheduledTaskCancel = null;
        }
    }

    public static Scheduler create(Plugin plugin) {
        boolean folia = Reflect.classPresent("io.papermc.paper.threadedregions.RegionizedServer")
                && Reflect.method(Bukkit.class, "getGlobalRegionScheduler") != null
                && Reflect.method(org.bukkit.entity.Entity.class, "getScheduler") != null;
        return new Scheduler(plugin, folia);
    }

    public boolean isFolia() { return folia; }

    private static Consumer<Object> wrap(Runnable r) { return t -> r.run(); }

    public void runForPlayer(Player p, Runnable r) {
        if (folia) {
            Object es = Reflect.invoke(entityGetScheduler, p);
            Reflect.invoke(entityRun, es, plugin, wrap(r), null);
        } else if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    public void runGlobal(Runnable r) {
        if (folia) Reflect.invoke(globalRun, globalScheduler, plugin, wrap(r));
        else if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    public Object runGlobalTimer(Runnable r, long delayTicks, long periodTicks) {
        if (folia) return Reflect.invoke(globalRunAtFixedRate, globalScheduler, plugin, wrap(r), Math.max(1, delayTicks), Math.max(1, periodTicks));
        return Bukkit.getScheduler().runTaskTimer(plugin, r, delayTicks, periodTicks);
    }

    public Object runPlayerTimer(Player p, Runnable r, long delayTicks, long periodTicks) {
        if (folia) {
            Object es = Reflect.invoke(entityGetScheduler, p);
            return Reflect.invoke(entityRunAtFixedRate, es, plugin, wrap(r), null, Math.max(1, delayTicks), Math.max(1, periodTicks));
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, r, delayTicks, periodTicks);
    }

    public void runAsync(Runnable r) {
        if (folia) Reflect.invoke(asyncRunNow, asyncScheduler, plugin, wrap(r));
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    public void cancel(Object handle) {
        if (handle == null) return;
        if (handle instanceof BukkitTask) ((BukkitTask) handle).cancel();
        else Reflect.invoke(scheduledTaskCancel, handle);
    }

    public void cancelAll() {
        if (folia) {
            Reflect.invoke(globalCancelTasks, globalScheduler, plugin);
            Reflect.invoke(asyncCancelTasks, asyncScheduler, plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }
}
