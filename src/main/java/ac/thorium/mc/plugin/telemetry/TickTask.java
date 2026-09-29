package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.compat.ServerCompat;
import org.bukkit.entity.Player;

public final class TickTask {
    private final Scheduler sched;
    private final ServerCompat compat;
    private final ErrorGate gate;
    private final Telemetry telemetry;
    private final SampleBuffer buffer;
    private Object handle;

    public TickTask(Scheduler sched, ServerCompat compat, ErrorGate gate, Telemetry telemetry, SampleBuffer buffer) {
        this.sched = sched; this.compat = compat; this.gate = gate; this.telemetry = telemetry; this.buffer = buffer;
    }

    public void start() { handle = sched.runGlobalTimer(() -> gate.run("tick", this::tick), 1L, 1L); }

    public void stop() { if (handle != null) sched.cancel(handle); handle = null; }

    private void tick() {
        telemetry.advanceTick();
        compat.onTick(System.nanoTime());
        if (!telemetry.connected()) return;
        for (Player p : telemetry.trackedPlayers()) {
            if (sched.isFolia()) sched.runForPlayer(p, () -> gate.run("tick:player", () -> perPlayer(p)));
            else perPlayer(p);
        }
    }

    private void perPlayer(Player p) {
        if (!p.isOnline()) return;
        buffer.meta(p.getUniqueId(), MetaBuilder.build(p, compat));
        telemetry.tickTransaction(p);
    }
}
