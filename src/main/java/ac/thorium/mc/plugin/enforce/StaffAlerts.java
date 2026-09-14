package ac.thorium.mc.plugin.enforce;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StaffAlerts {
    public static final String PERMISSION = "thorium.alerts";
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    /** Returns the new enabled state. */
    public boolean toggle(UUID staff) { if (muted.remove(staff)) return true; muted.add(staff); return false; }
    public boolean enabled(UUID staff) { return !muted.contains(staff); }
    public void forget(UUID staff) { muted.remove(staff); }

    public List<Player> recipients(Collection<? extends Player> online) {
        List<Player> out = new ArrayList<>();
        for (Player p : online) if (p.hasPermission(PERMISSION) && enabled(p.getUniqueId())) out.add(p);
        return out;
    }
}
