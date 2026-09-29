package ac.thorium.mc.plugin.enforce;

import ac.thorium.mc.proto.Action;
import ac.thorium.mc.proto.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnforcementDecisionTest {
    private static Verdict v(Action a, boolean staff) {
        return Verdict.newBuilder().setAction(a).setStaff(staff).build();
    }

    @Test
    void automaticVerdictsOnlyAlertWhenEnforcementIsOff() {
        assertEquals(Outcome.STAFF_ALERT, EnforcementDecision.decide(v(Action.ACTION_KICK, false), false, false, false));
        assertEquals(Outcome.STAFF_ALERT, EnforcementDecision.decide(v(Action.ACTION_BAN, false), false, true, false));
    }

    @Test
    void staffVerdictsEnforceRegardless() {
        assertEquals(Outcome.KICK, EnforcementDecision.decide(v(Action.ACTION_KICK, true), false, false, false));
        assertEquals(Outcome.BAN_BUKKIT, EnforcementDecision.decide(v(Action.ACTION_BAN, true), false, false, false));
        assertEquals(Outcome.BAN_COMMAND, EnforcementDecision.decide(v(Action.ACTION_BAN, true), false, true, false));
    }

    @Test
    void unbansAreStaffOnlyAndPreferTheCommand() {
        assertEquals(Outcome.UNBAN_BUKKIT, EnforcementDecision.decide(v(Action.ACTION_UNBAN, true), false, false, false));
        assertEquals(Outcome.UNBAN_COMMAND, EnforcementDecision.decide(v(Action.ACTION_UNBAN, true), false, true, true));
        assertEquals(Outcome.LOG_ONLY, EnforcementDecision.decide(v(Action.ACTION_UNBAN, false), true, false, false));
    }

    @Test
    void shadowStillWins() {
        Verdict shadow = Verdict.newBuilder().setAction(Action.ACTION_BAN).setStaff(true).setShadow(true).build();
        assertEquals(Outcome.LOG_ONLY, EnforcementDecision.decide(shadow, true, false, false));
    }
}
