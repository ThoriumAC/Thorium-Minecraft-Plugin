package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EntityMove;
import ac.thorium.mc.proto.Vec3;

import java.util.function.Consumer;

public final class MoveCoalescer {
    private static final int CAP = 256;
    private final int[] ids = new int[CAP];
    private final double[] x = new double[CAP], y = new double[CAP], z = new double[CAP];
    private final float[] yaw = new float[CAP], pitch = new float[CAP];
    private final boolean[] look = new boolean[CAP], ground = new boolean[CAP];
    private int n;

    public void offer(int id, double px, double py, double pz, boolean hasLook, float pyaw, float ppitch, boolean onGround) {
        int i = indexOf(id);
        if (i < 0) { if (n == CAP) return; i = n++; ids[i] = id; look[i] = false; }
        x[i] = px; y[i] = py; z[i] = pz; ground[i] = onGround;
        if (hasLook) { look[i] = true; yaw[i] = pyaw; pitch[i] = ppitch; }
    }

    public void drain(Consumer<EntityMove> out) {
        for (int i = 0; i < n; i++) {
            EntityMove.Builder b = EntityMove.newBuilder().setId(ids[i]).setPosition(Vec3.newBuilder().setX(x[i]).setY(y[i]).setZ(z[i])).setOnGround(ground[i]).setHasLook(look[i]);
            if (look[i]) b.setYaw(yaw[i]).setPitch(pitch[i]);
            out.accept(b.build());
        }
        n = 0;
    }

    private int indexOf(int id) { for (int i = 0; i < n; i++) if (ids[i] == id) return i; return -1; }
}
