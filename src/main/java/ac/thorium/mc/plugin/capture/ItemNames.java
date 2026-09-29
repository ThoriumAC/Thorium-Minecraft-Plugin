package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EquipmentSlot;
import ac.thorium.mc.proto.SlotOut;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentType;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.Equipment;

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
        return b.setItem(of(it)).setCount(it.getAmount()).setEfficiency(l[0]).setAquaAffinity(l[1] > 0).setDepthStrider(l[2]).setSoulSpeed(l[3]);
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
