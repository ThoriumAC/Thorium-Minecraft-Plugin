package ac.thorium.mc.plugin.enforce;

import ac.thorium.mc.proto.Verdict;

public final class EnforcementDecision {
    private EnforcementDecision() {}

    public static Outcome decide(Verdict v, boolean enforce, boolean banCommandConfigured) {
        if (v.getShadow()) return Outcome.LOG_ONLY;
        switch (v.getAction()) {
            case ACTION_FLAG: return Outcome.STAFF_ALERT;
            case ACTION_WARN: return enforce ? Outcome.WARN_PLAYER : Outcome.STAFF_ALERT;
            case ACTION_KICK: return enforce ? Outcome.KICK : Outcome.STAFF_ALERT;
            case ACTION_BAN: return enforce ? (banCommandConfigured ? Outcome.BAN_COMMAND : Outcome.BAN_BUKKIT) : Outcome.STAFF_ALERT;
            default: return Outcome.LOG_ONLY;
        }
    }

    public static boolean notifiesStaff(Outcome o) { return o != Outcome.LOG_ONLY; }
}
