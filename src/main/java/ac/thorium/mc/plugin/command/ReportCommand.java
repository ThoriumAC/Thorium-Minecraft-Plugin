package ac.thorium.mc.plugin.command;

import ac.thorium.mc.plugin.config.NetworkSettings;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.EngineConnection;
import ac.thorium.mc.proto.Report;
import ac.thorium.mc.proto.UpStream;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class ReportCommand implements CommandExecutor, TabCompleter {
    private final Supplier<NetworkSettings> settings;
    private final Supplier<EngineConnection> connection;
    private final Supplier<Telemetry> telemetry;

    public ReportCommand(Supplier<NetworkSettings> settings, Supplier<EngineConnection> connection, Supplier<Telemetry> telemetry) {
        this.settings = settings; this.connection = connection; this.telemetry = telemetry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
        NetworkSettings s = settings.get();
        if (s == null || !s.reportsEnabled()) { sender.sendMessage("§cReports are turned off on this server."); return true; }
        if (args.length < 1) { sender.sendMessage("§7Usage: /" + label + " <player> [reason]"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { sender.sendMessage("§cThat player is not online."); return true; }
        if (target.equals(sender)) { sender.sendMessage("§cYou can't report yourself."); return true; }
        String reason = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
        EngineConnection c = connection.get();
        Telemetry t = telemetry.get();
        boolean sent = c != null && t != null && c.send(UpStream.newBuilder().setReport(Report.newBuilder()
                .setReporter(t.ref((Player) sender)).setSuspect(t.ref(target)).setReason(reason)).build());
        sender.sendMessage(sent ? "§aReport sent. Thanks." : "§cCouldn't send the report right now. Try again shortly.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        String prefix = args[0].toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
        return out;
    }
}
