package io.github.rewind.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents all recorded changes within a single server tick.
 * This is one "frame" in our rewind buffer.
 */
public class TickFrame {
    private final long gameTime;        // Server world time when this frame was recorded
    private final long realTimestamp;   // System.currentTimeMillis() when recorded
    
    private final List<BlockDelta> blockDeltas;
    private final List<BlockEntityDelta> blockEntityDeltas;
    private final List<EntityDelta> entityDeltas;
    
    private boolean sealed = false;     // Once sealed, no more changes can be added
    private int estimatedMemoryBytes = 0;

    public TickFrame(long gameTime) {
        this.gameTime = gameTime;
        this.realTimestamp = System.currentTimeMillis();
        this.blockDeltas = new ArrayList<>();
        this.blockEntityDeltas = new ArrayList<>();
        this.entityDeltas = new ArrayList<>();
    }

    /**
     * Add a block change to this frame.
     */
    public void addBlockDelta(BlockDelta delta) {
        if (sealed) {
            throw new IllegalStateException("Cannot add to sealed TickFrame");
        }
        blockDeltas.add(delta);
        estimatedMemoryBytes += delta.estimateMemoryBytes();
    }

    /**
     * Add a block entity change to this frame.
     */
    public void addBlockEntityDelta(BlockEntityDelta delta) {
        if (sealed) {
            throw new IllegalStateException("Cannot add to sealed TickFrame");
        }
        blockEntityDeltas.add(delta);
        estimatedMemoryBytes += delta.estimateMemoryBytes();
    }

    /**
     * Add an entity change to this frame.
     */
    public void addEntityDelta(EntityDelta delta) {
        if (sealed) {
            throw new IllegalStateException("Cannot add to sealed TickFrame");
        }
        entityDeltas.add(delta);
        estimatedMemoryBytes += delta.estimateMemoryBytes();
    }

    /**
     * Seal this frame - no more changes can be added.
     * Called at the end of each tick.
     */
    public void seal() {
        this.sealed = true;
        // Trim to size to save memory
        ((ArrayList<?>) blockDeltas).trimToSize();
        ((ArrayList<?>) blockEntityDeltas).trimToSize();
        ((ArrayList<?>) entityDeltas).trimToSize();
    }

    /**
     * Check if this frame has any recorded changes.
     */
    public boolean isEmpty() {
        return blockDeltas.isEmpty() && blockEntityDeltas.isEmpty() && entityDeltas.isEmpty();
    }

    /**
     * Get the number of total changes in this frame.
     */
    public int changeCount() {
        return blockDeltas.size() + blockEntityDeltas.size() + entityDeltas.size();
    }

    // Getters

    public long getGameTime() {
        return gameTime;
    }

    public long getRealTimestamp() {
        return realTimestamp;
    }

    public List<BlockDelta> getBlockDeltas() {
        return Collections.unmodifiableList(blockDeltas);
    }

    public List<BlockEntityDelta> getBlockEntityDeltas() {
        return Collections.unmodifiableList(blockEntityDeltas);
    }

    public List<EntityDelta> getEntityDeltas() {
        return Collections.unmodifiableList(entityDeltas);
    }

    public boolean isSealed() {
        return sealed;
    }

    public int getEstimatedMemoryBytes() {
        return estimatedMemoryBytes;
    }

    @Override
    public String toString() {
        return String.format("TickFrame[time=%d, blocks=%d, blockEntities=%d, entities=%d, ~%dKB]",
                gameTime,
                blockDeltas.size(),
                blockEntityDeltas.size(),
                entityDeltas.size(),
                estimatedMemoryBytes / 1024);
    }
}
