package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EquipmentSlot;
import ac.thorium.mc.proto.ItemPatch;
import ac.thorium.mc.proto.SlotOut;
import ac.thorium.mc.proto.ToolRule;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.PatchableComponentMap;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemAttackRange;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemConsumable;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTool;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemUseEffects;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentType;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;

import java.util.Map;
import java.util.Optional;

public final class ItemNames {
    private ItemNames() {}

    public static String of(ItemStack item) {
        if (item == null || item.isEmpty() || item.getType() == null) return "";
        return String.valueOf(item.getType().getName());
    }

    public static EquipmentSlot.Builder slot(Equipment e) {
        EquipmentSlot.Builder b = EquipmentSlot.newBuilder().setSlot(e.getSlot() == null ? 0 : e.getSlot().ordinal());
        ItemStack it = e.getItem();
        if (it == null || it.isEmpty()) return b;
        int[] l = enchants(it);
        return b.setItem(of(it)).setCount(it.getAmount()).setEfficiency(l[0]).setAquaAffinity(l[1] > 0).setDepthStrider(l[2]).setSoulSpeed(l[3]);
    }

    public static SlotOut.Builder slotOut(int slot, ItemStack it) {
        SlotOut.Builder b = SlotOut.newBuilder().setSlot(slot).setEnchantsKnown(true);
        if (it == null || it.isEmpty()) return b;
        int[] l = enchants(it);
        b.setItem(of(it)).setCount(it.getAmount()).setEfficiency(l[0]).setAquaAffinity(l[1] > 0).setDepthStrider(l[2]).setSoulSpeed(l[3]);
        ItemPatch patch = patch(it);
        return patch == null ? b : b.setPatch(patch);
    }

    // The stack's component patch as it goes to the client, for the components
    // that change movement or mining; null when it touches none of them.
    static ItemPatch patch(ItemStack it) {
        try {
            PatchableComponentMap m = it.getComponents();
            return m == null ? null : patch(m.getPatches());
        } catch (Throwable ignored) {
            return null;
        }
    }

    static ItemPatch patch(Map<ComponentType<?>, Optional<?>> p) {
        if (p.isEmpty()) return null;
        ItemPatch.Builder b = ItemPatch.newBuilder();
        Optional<?> v;
        if ((v = p.get(ComponentTypes.GLIDER)) != null) b.setGlider(v.isPresent() ? 1 : 2);
        if ((v = p.get(ComponentTypes.CONSUMABLE)) != null) {
            b.setConsumable(v.isPresent() ? 1 : 2);
            v.ifPresent(c -> b.setConsumeSeconds(((ItemConsumable) c).getConsumeSeconds()));
        }
        if ((v = p.get(ComponentTypes.USE_EFFECTS)) != null) {
            b.setUseEffects(v.isPresent() ? 1 : 2);
            v.ifPresent(u -> b.setUseSpeed(((ItemUseEffects) u).getSpeedMultiplier()));
        }
        if ((v = p.get(ComponentTypes.TOOL)) != null) {
            b.setTool(v.isPresent() ? 1 : 2);
            v.ifPresent(t -> {
                ItemTool tool = (ItemTool) t;
                b.setToolDefaultSpeed(tool.getDefaultMiningSpeed());
                for (ItemTool.Rule r : tool.getRules()) {
                    ToolRule.Builder rb = ToolRule.newBuilder();
                    if (r.getBlocks().getTagKey() != null) rb.setTag(r.getBlocks().getTagKey().toString());
                    else if (r.getBlocks().getEntities() != null) for (StateType.Mapped st : r.getBlocks().getEntities()) rb.addBlocks(st.getName().toString());
                    if (r.getSpeed() != null) rb.setHasSpeed(true).setSpeed(r.getSpeed());
                    if (r.getCorrectForDrops() != null) rb.setCorrect(r.getCorrectForDrops() ? 1 : 2);
                    b.addToolRules(rb);
                }
            });
        }
        if ((v = p.get(ComponentTypes.ATTACK_RANGE)) != null) {
            b.setAttackRange(v.isPresent() ? 1 : 2);
            v.ifPresent(r -> {
                ItemAttackRange a = (ItemAttackRange) r;
                b.setAttackMaxReach(a.getMaxRange()).setAttackMaxCreativeReach(a.getMaxCreativeRange()).setAttackHitboxMargin(a.getHitboxMargin());
            });
        }
        if ((v = p.get(ComponentTypes.PIERCING_WEAPON)) != null) b.setPiercingWeapon(v.isPresent() ? 1 : 2);
        if ((v = p.get(ComponentTypes.BLOCKS_ATTACKS)) != null) b.setBlocksAttacks(v.isPresent() ? 1 : 2);
        ItemPatch out = b.build();
        return out.equals(ItemPatch.getDefaultInstance()) ? null : out;
    }

    private static int[] enchants(ItemStack it) {
        return new int[]{level(it, EnchantmentTypes.BLOCK_EFFICIENCY), level(it, EnchantmentTypes.AQUA_AFFINITY),
                level(it, EnchantmentTypes.DEPTH_STRIDER), level(it, EnchantmentTypes.SOUL_SPEED)};
    }

    // The versioned overload also reads 1.20.5+ data components.
    private static int level(ItemStack it, EnchantmentType t) {
        try { return it.getEnchantmentLevel(t, version()); } catch (Throwable ignored) { return 0; }
    }

    private static volatile ClientVersion version;

    private static ClientVersion version() {
        ClientVersion v = version;
        if (v == null) version = v = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        return v;
    }
}
