package ac.thorium.mc.plugin.command;

import ac.thorium.mc.plugin.ThoriumPlugin;
import ac.thorium.mc.plugin.enforce.StaffAlerts;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ThoriumCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN = "thorium.admin";
    private final ThoriumPlugin plugin;

    public ThoriumCommand(ThoriumPlugin plugin) { this.plugin = plugin; }

    public static List<String> complete(String partial, boolean admin, boolean alerts) {
        List<String> out = new ArrayList<>();
        if (alerts) out.add("alerts");
        if (admin) { out.add("capture"); out.add("reconnect"); out.add("status"); }
        List<String> filtered = new ArrayList<>();
        for (String s : out) if (s.startsWith(partial.toLowerCase(Locale.ROOT))) filtered.add(s);
        Collections.sort(filtered);
        return filtered;
    }

    public static List<String> statusLines(String version, String software, String mcVersion, boolean folia, boolean configured,
                                           String connectionLine, String telemetryLine, boolean enforce) {
        List<String> l = new ArrayList<>();
        l.add("§8[§cThorium§8] §fplugin " + version + (folia ? " §7(Folia)" : ""));
        l.add("§7server: §f" + software + " " + mcVersion);
        l.add(configured ? "§7connection: §f" + connectionLine : "§cnot configured §7(set server-token in config.yml)");
        if (configured) l.add("§7telemetry: §f" + telemetryLine);
        l.add("§7enforce: §f" + (enforce ? "on" : "off"));
        return l;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status": {
                if (!sender.hasPermission(ADMIN)) { sender.sendMessage("§cNo permission."); return true; }
                boolean configured = plugin.connection() != null;
                for (String line : statusLines(plugin.getDescription().getVersion(), plugin.compat().software().name().replace("SERVER_SOFTWARE_", ""),
                        plugin.compat().mcVersion(), plugin.scheduler().isFolia(), configured,
                        configured ? plugin.connection().statusLine() : "", configured ? plugin.telemetry().statusLine() : "", plugin.config().enforce)) {
                    sender.sendMessage(line);
                }
                return true;
            }
            case "alerts": {
                if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
                if (!sender.hasPermission(StaffAlerts.PERMISSION)) { sender.sendMessage("§cNo permission."); return true; }
                boolean on = plugin.staffAlerts().toggle(((Player) sender).getUniqueId());
                sender.sendMessage(on ? "§aThorium alerts enabled." : "§7Thorium alerts disabled.");
                return true;
            }
            case "capture": {
                if (!sender.hasPermission(ADMIN)) { sender.sendMessage("§cNo permission."); return true; }
                if (plugin.telemetry() == null) { sender.sendMessage("§cNot connected."); return true; }
                String name = args.length > 1 ? args[1].replaceAll("[^A-Za-z0-9_-]", "") : "";
                if (name.isEmpty() || name.equalsIgnoreCase("stop")) {
                    ac.thorium.mc.plugin.telemetry.CaptureWriter c = plugin.telemetry().stopCapture();
                    sender.sendMessage(c == null ? "§7No capture running. Usage: /thorium capture <label>" : "§aSaved " + c.file().getName() + " (" + c.frames() + " frames)");
                    return true;
                }
                java.io.File f = new java.io.File(plugin.getDataFolder(), "captures/" + name + "-" + System.currentTimeMillis() / 1000 + ".bin");
                try { plugin.telemetry().startCapture(f); sender.sendMessage("§aCapturing to " + f.getName() + " — /thorium capture stop when done"); }
                catch (java.io.IOException e) { sender.sendMessage("§cCould not open " + f + ": " + e.getMessage()); }
                return true;
            }
            case "reconnect":
                if (!sender.hasPermission(ADMIN)) { sender.sendMessage("§cNo permission."); return true; }
                plugin.reconnect();
                sender.sendMessage("§7Thorium: reconnecting…");
                return true;
            default:
                sender.sendMessage("§7Usage: /thorium <status|alerts|reconnect|capture <label>|capture stop>");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length != 1) return Collections.emptyList();
        return complete(args[0], sender.hasPermission(ADMIN), sender.hasPermission(StaffAlerts.PERMISSION));
    }
}
