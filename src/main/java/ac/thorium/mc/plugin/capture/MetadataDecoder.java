package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EntityMetadata;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;

import java.util.List;

public final class MetadataDecoder {
    private MetadataDecoder() {}

    private static final int IDX_FLAGS = 0, IDX_POSE = 6, IDX_BABY = 16, IDX_PEEK = 17;

    public static EntityMetadata.Builder decode(int id, List<EntityData<?>> data) {
        if (data == null || data.isEmpty()) return null;
        EntityMetadata.Builder b = EntityMetadata.newBuilder().setId(id);
        boolean any = false;
        for (EntityData<?> d : data) {
            if (d == null) continue;
            Object v = d.getValue();
            if (d.getIndex() == IDX_FLAGS && v instanceof Number) {
                int f = ((Number) v).intValue();
                b.setHasFlags(true).setSneaking((f & 0x02) != 0).setSprinting((f & 0x08) != 0).setSwimming((f & 0x10) != 0)
                 .setInvisible((f & 0x20) != 0).setGliding((f & 0x80) != 0);
            } else if (d.getIndex() == IDX_POSE && v != null && poseOrdinal(v) >= 0) {
                int pose = poseOrdinal(v);
                b.setHasPose(true).setPose(pose).setRiptiding(pose == 8);
            } else if (d.getIndex() == IDX_BABY && v instanceof Integer) {
                b.setHasSize(true).setSize((Integer) v);
            } else if (d.getIndex() == IDX_BABY && v instanceof Boolean) {
                b.setHasBaby(true).setBaby((Boolean) v);
            } else if (d.getIndex() == IDX_PEEK && v instanceof Byte) {
                b.setHasPeek(true).setPeek((Byte) v);
            } else continue;
            any = true;
        }
        return any ? b : null;
    }

    private static int poseOrdinal(Object v) {
        if (v instanceof Enum) return ((Enum<?>) v).ordinal();
        if (v instanceof Number) return ((Number) v).intValue();
        return -1;
    }
}
