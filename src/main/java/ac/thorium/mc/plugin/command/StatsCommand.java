package ac.thorium.mc.plugin.command;

import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.EngineConnection;
import ac.thorium.mc.proto.Activity;
import ac.thorium.mc.proto.ActivityEvent;
import ac.thorium.mc.proto.UpStream;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public final class StatsCommand implements CommandExecutor, TabCompleter {
    private final Supplier<EngineConnection> connection;
    private final Supplier<Telemetry> telemetry;

    public StatsCommand(Supplier<EngineConnection> connection, Supplier<Telemetry> telemetry) {
        this.connection = connection; this.telemetry = telemetry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
        if (args.length != 1 || !(args[0].equalsIgnoreCase("hide") || args[0].equalsIgnoreCase("show"))) {
            sender.sendMessage("§7Usage: /" + label + " <hide|show>");
            return true;
        }
        boolean hide = args[0].equalsIgnoreCase("hide");
        EngineConnection c = connection.get();
        Telemetry t = telemetry.get();
        boolean sent = c != null && t != null && c.send(UpStream.newBuilder().setActivity(Activity.newBuilder().addEvents(
                ActivityEvent.newBuilder().setPlayer(t.ref((Player) sender)).setAtMs(System.currentTimeMillis())
                        .setKind(hide ? "stats_hide" : "stats_show"))).build());
        if (!sent) sender.sendMessage("§cCouldn't reach Thorium right now. Try again shortly.");
        else sender.sendMessage(hide ? "§aYou're hidden from this network's public stats." : "§aYou're visible on this network's public stats again.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) for (String s : Arrays.asList("hide", "show")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        return out;
    }
}
