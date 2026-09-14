package ac.thorium.mc.plugin.enforce;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.telemetry.Names;
import ac.thorium.mc.proto.Verdict;
import org.bukkit.BanList;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Applies engine verdicts on the server. Never runs Bukkit calls on the network thread. */
public final class Enforcer implements Consumer<Verdict> {
    private final Server server;
    private final Scheduler sched;
    private final ServerCompat compat;
    private final ErrorGate gate;
    private final StaffAlerts alerts;
    private final PluginConfig cfg;
    private final Logger log;

    public Enforcer(Server server, Scheduler sched, ServerCompat compat, ErrorGate gate, StaffAlerts alerts, PluginConfig cfg, Logger log) {
        this.server = server; this.sched = sched; this.compat = compat; this.gate = gate; this.alerts = alerts; this.cfg = cfg; this.log = log;
    }

    @Override
    public void accept(Verdict v) {
        Outcome o = EnforcementDecision.decide(v, cfg.enforce, cfg.useBanCommand());
        log.info(MessageFormat.consoleLine(v, o));
        if (o == Outcome.LOG_ONLY) return;
        sched.runGlobal(() -> gate.run("enforce", () -> apply(v, o)));
    }

    private void apply(Verdict v, Outcome o) {
        String alertLine = MessageFormat.format(cfg.alertFormat, v);
        for (Player staff : alerts.recipients(server.getOnlinePlayers())) staff.sendMessage(alertLine);

        UUID id = Names.uuid(v.getPlayer().getUuid());
        Player target = id == null ? null : server.getPlayer(id);
        if (target == null) target = server.getPlayerExact(v.getPlayer().getUsername());
        if (o == Outcome.STAFF_ALERT) return;

        if (o == Outcome.BAN_COMMAND) {
            String cmd = cfg.banCommand.replace("%player%", v.getPlayer().getUsername()).replace("%reason%", v.getReason()).replace("%check%", v.getAlertType());
            server.dispatchCommand(server.getConsoleSender(), cmd);
            return;
        }
        if (o == Outcome.BAN_BUKKIT) {
            server.getBanList(BanList.Type.NAME).addBan(v.getPlayer().getUsername(), "Thorium: " + v.getAlertType() + " - " + v.getReason(), null, "Thorium");
        }
        if (target == null) return;
        Player p = target;
        sched.runForPlayer(p, () -> gate.run("enforce:player", () -> {
            switch (o) {
                case WARN_PLAYER: p.sendMessage(MessageFormat.format(cfg.warnFormat, v)); break;
                case KICK: case BAN_BUKKIT: compat.kick(p, MessageFormat.format(cfg.kickFormat, v)); break;
                default: break;
            }
        }));
    }
}
