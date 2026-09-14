package ac.thorium.mc.plugin.enforce;

import ac.thorium.mc.proto.Verdict;

import java.util.Locale;

public final class MessageFormat {
    private MessageFormat() {}

    public static String colour(String s) {
        if (s == null) return "";
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length - 1; i++) {
            if (c[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(c[i + 1]) >= 0) { c[i] = '§'; c[i + 1] = Character.toLowerCase(c[i + 1]); }
        }
        return new String(c);
    }

    public static String actionName(Verdict v) {
        String n = v.getAction().name();
        return n.startsWith("ACTION_") ? n.substring(7) : n;
    }

    public static String format(String template, Verdict v) {
        String s = template == null ? "" : template;
        s = s.replace("%player%", v.getPlayer().getUsername())
             .replace("%check%", v.getAlertType())
             .replace("%vl%", String.valueOf(Math.round(v.getVl())))
             .replace("%confidence%", String.valueOf(Math.round(v.getConfidence() * 100)))
             .replace("%reason%", v.getReason())
             .replace("%action%", actionName(v).toLowerCase(Locale.ROOT));
        return colour(s);
    }

    public static String consoleLine(Verdict v, Outcome o) {
        return String.format(Locale.ROOT, "[Thorium] %s %s %s conf=%.2f vl=%.1f shadow=%s -> %s",
                actionName(v), v.getAlertType(), v.getPlayer().getUsername(), v.getConfidence(), v.getVl(), v.getShadow(), o);
    }
}
