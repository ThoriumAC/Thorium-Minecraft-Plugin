package ac.thorium.mc.plugin.world;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.proto.SectionPos;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * Turns block changes into mirror updates.
 *
 * <p>Two kinds of handler. Where the new state is known cheaply and exactly at
 * MONITOR — a place, a break, an explosion — the change is recorded precisely.
 * Where it is not, or where one event moves many blocks (pistons, liquid flow,
 * growth), the affected sections are invalidated instead and re-read on the
 * sampler's next pass. Guessing a state there would poison the mirror, and the
 * engine trusts these blocks for collision.
 *
 * <p>Deliberately absent: {@code BlockPhysicsEvent}. It fires thousands of times
 * a second on a busy server and listening to it would cost more TPS than the
 * whole mirror saves. What physics actually changes — falling sand and gravel
 * landing, and the like — is caught by {@link EntityChangeBlockEvent} instead,
 * which is rare and cheap. Anything still missed is corrected by the mirror's
 * bounded staleness sweep.
 */
public final class WorldBlockEvents implements Listener {
    private final WorldMirror mirror;
    private final ErrorGate gate;

    public WorldBlockEvents(WorldMirror mirror, ErrorGate gate) {
        this.mirror = mirror;
        this.gate = gate;
    }

    private void set(Block b, String state) {
        String dim = b.getWorld().getName();
        mirror.recordChange(WorldSampler.sectionOf(dim, b.getX(), b.getY(), b.getZ()),
                WorldSampler.offsetOf(b.getX(), b.getY(), b.getZ()), state);
    }

    private void dirty(Block b) {
        String dim = b.getWorld().getName();
        mirror.invalidate(WorldSampler.sectionOf(dim, b.getX(), b.getY(), b.getZ()));
    }

    // ---- exact ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        gate.run("world:place", () -> set(e.getBlockPlaced(), SnapshotReader.stateOf(e.getBlockPlaced())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        // At MONITOR the block is still standing; it becomes air right after.
        gate.run("world:break", () -> set(e.getBlock(), SnapshotReader.AIR));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        gate.run("world:explode", () -> {
            for (Block b : e.blockList()) set(b, SnapshotReader.AIR);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        gate.run("world:explode", () -> {
            for (Block b : e.blockList()) set(b, SnapshotReader.AIR);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        gate.run("world:burn", () -> set(e.getBlock(), SnapshotReader.AIR));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent e) {
        gate.run("world:decay", () -> set(e.getBlock(), SnapshotReader.AIR));
    }

    // ---- coarse: invalidate and re-read ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        gate.run("world:piston", () -> pistonDirty(e.getBlock(), e.getBlocks()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        gate.run("world:piston", () -> pistonDirty(e.getBlock(), e.getBlocks()));
    }

    private void pistonDirty(Block piston, java.util.List<Block> moved) {
        dirty(piston);
        if (moved == null) return;
        for (Block b : moved) {
            dirty(b);
            // A moved block lands one over, which may be the next section along.
            for (SectionPos p : WorldSampler.sectionsSpanning(b.getWorld().getName(), b.getX(), b.getY() - 1, b.getY() + 1, b.getZ())) {
                mirror.invalidate(p);
            }
        }
    }

    /** Falling blocks landing or breaking, endermen moving a block, sheep eating grass. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        gate.run("world:entityblock", () -> dirty(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent e) {
        gate.run("world:flow", () -> dirty(e.getToBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { gate.run("world:fade", () -> dirty(e.getBlock())); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { gate.run("world:form", () -> dirty(e.getBlock())); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) { gate.run("world:grow", () -> dirty(e.getBlock())); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { gate.run("world:spread", () -> dirty(e.getBlock())); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        // The mirror must not keep serving a chunk the server no longer has resident.
        gate.run("world:unload", () -> {
            String dim = e.getWorld().getName();
            int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
            int minSy = SnapshotReader.minSectionY(e.getWorld());
            int maxSy = SnapshotReader.maxSectionY(e.getWorld());
            for (int sy = minSy; sy <= maxSy; sy++) {
                mirror.invalidate(SectionPos.newBuilder().setDimension(dim).setX(cx).setY(sy).setZ(cz).build());
            }
        });
    }
}
