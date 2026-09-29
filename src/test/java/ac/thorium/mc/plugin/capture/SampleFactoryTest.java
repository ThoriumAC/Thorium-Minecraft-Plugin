package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SampleFactoryTest {
    @Test
    void inputCarriesAllSevenBits() {
        Sample s = SampleFactory.input(true, false, true, false, true, false, true).build();
        assertEquals(Sample.KindCase.INPUT, s.getKindCase());
        assertTrue(s.getInput().getForward());
        assertTrue(s.getInput().getLeft());
        assertTrue(s.getInput().getJump());
        assertTrue(s.getInput().getSprint());
        assertFalse(s.getInput().getShift());
    }

    @Test
    void combatV2HasNoServerTruth() {
        Sample s = SampleFactory.combat(CombatInteract.COMBAT_INTERACT_ATTACK, 42, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false).build();
        assertEquals(42, s.getCombat().getTargetEntityId());
        assertEquals(CombatInteract.COMBAT_INTERACT_ATTACK, s.getCombat().getInteract());
        assertFalse(s.getCombat().hasTargetPosition());
        assertEquals(0f, s.getCombat().getTargetWidth());
    }

    @Test
    void blockV2CarriesSequenceCursorAndNoBreakTicks() {
        Sample s = SampleFactory.block(BlockAction.BLOCK_ACTION_PLACE, 1, 2, 3, 4, 0f, 0f, 0, 0, 0, 17, 0, 0.5, 1.0, 0.5, false, false).build();
        assertEquals(17, s.getBlock().getSequence());
        assertEquals(1.0, s.getBlock().getCursor().getY());
        assertEquals(0, s.getBlock().getExpectedBreakTicks());
        assertEquals("", s.getBlock().getBlockId());
    }

    @Test
    void textNeverCarriesContent() {
        Sample s = SampleFactory.text(TextKind.TEXT_KIND_CHAT, "hello world".length(), 0, true).build();
        assertEquals(11, s.getText().getLength());
        assertTrue(s.getText().getSigned());
    }

    @Test
    void spectateEncodesUuidBytes() {
        UUID u = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Sample s = SampleFactory.spectate(u).build();
        assertEquals(16, s.getSpectate().getTarget().size());
    }
}
