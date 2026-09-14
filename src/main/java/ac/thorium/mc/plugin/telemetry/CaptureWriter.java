package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.UpStream;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Dev recorder: every UpStream frame as [uint32 big-endian length][bytes], for engine replay fixtures. */
public final class CaptureWriter {
    private final File file;
    private final DataOutputStream out;
    private final AtomicLong frames = new AtomicLong();
    private volatile boolean closed;

    public CaptureWriter(File file) throws IOException {
        this.file = file;
        File dir = file.getParentFile();
        if (dir != null) dir.mkdirs();
        this.out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), 1 << 16));
    }

    public void write(UpStream frame) {
        if (closed) return;
        byte[] b = frame.toByteArray();
        synchronized (out) {
            try { out.writeInt(b.length); out.write(b); frames.incrementAndGet(); } catch (IOException ignored) { closed = true; }
        }
    }

    public void close() {
        closed = true;
        synchronized (out) { try { out.close(); } catch (IOException ignored) {} }
    }

    public File file() { return file; }
    public long frames() { return frames.get(); }
}
