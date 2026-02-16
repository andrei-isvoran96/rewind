package io.github.rewind.core;

import io.github.rewind.data.TickFrame;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Central manager for the timeline rewind system.
 * Maintains a ring buffer of TickFrames and coordinates recording/rewinding.
 * 
 * Thread safety: All operations should be called from the server thread only.
 */
public class TimelineManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rewind");
    
    // Configuration
    public static final int DEFAULT_MAX_FRAMES = 600;  // 30 seconds at 20 TPS
    public static final int TICKS_PER_SECOND = 20;
    public static final long MAX_MEMORY_BYTES = 50 * 1024 * 1024; // 50MB hard cap
    
    // Singleton instance (per server lifecycle)
    private static TimelineManager instance;
    
    // Ring buffer
    private final TickFrame[] frames;
    private final int maxFrames;
    private int writeHead = 0;      // Next position to write
    private int frameCount = 0;     // Current number of valid frames
    
    // Current frame being recorded (not yet in buffer)
    private TickFrame currentFrame;
    
    // State
    private boolean recording = true;
    private boolean rewinding = false;
    private boolean frozen = false;
    private long totalMemoryUsed = 0;
    
    // Server reference
    private MinecraftServer server;

    private TimelineManager(int maxFrames) {
        this.maxFrames = maxFrames;
        this.frames = new TickFrame[maxFrames];
        LOGGER.info("TimelineManager initialized with {} frame capacity ({} seconds)",
                maxFrames, maxFrames / TICKS_PER_SECOND);
    }

    /**
     * Initialize the TimelineManager for a server.
     * Should be called when server starts.
     */
    public static void initialize(MinecraftServer server) {
        instance = new TimelineManager(DEFAULT_MAX_FRAMES);
        instance.server = server;
        LOGGER.info("Timeline recording started");
    }

    /**
     * Shutdown and cleanup.
     * Should be called when server stops.
     */
    public static void shutdown() {
        if (instance != null) {
            instance.clear();
            instance.server = null;
            instance = null;
            LOGGER.info("Timeline recording stopped");
        }
    }

    /**
     * Get the singleton instance.
     */
    @Nullable
    public static TimelineManager getInstance() {
        return instance;
    }

    /**
     * Start recording a new tick frame.
     */
    public void beginTick(long gameTime) {
        if (!recording || rewinding || frozen) {
            return;
        }
        
        if (currentFrame != null && !currentFrame.isSealed() && !currentFrame.isEmpty()) {
            commitCurrentFrame();
        }
        
        currentFrame = new TickFrame(gameTime);
    }
    
    private void commitCurrentFrame() {
        if (currentFrame == null) {
            return;
        }
        
        currentFrame.seal();
        
        if (frameCount == maxFrames) {
            TickFrame oldFrame = frames[writeHead];
            if (oldFrame != null) {
                totalMemoryUsed -= oldFrame.getEstimatedMemoryBytes();
            }
        }
        
        frames[writeHead] = currentFrame;
        totalMemoryUsed += currentFrame.getEstimatedMemoryBytes();
        
        writeHead = (writeHead + 1) % maxFrames;
        if (frameCount < maxFrames) {
            frameCount++;
        }
        
        currentFrame = null;
    }

    /**
     * Ensure a current frame exists for recording.
     */
    public void ensureCurrentFrame() {
        if (!recording || rewinding || frozen) {
            return;
        }
        if (currentFrame == null || currentFrame.isSealed()) {
            long gameTime = server != null ? server.getOverworld().getTime() : 0;
            currentFrame = new TickFrame(gameTime);
        }
    }

    /**
     * Finish recording the current tick frame and add to buffer.
     * Called at the END of each server tick.
     */
    public void endTick() {
        if (!recording || rewinding || frozen || currentFrame == null) {
            return;
        }
        
        commitCurrentFrame();
        
        // Memory pressure check
        enforceMemoryLimit();
    }

    /**
     * Get the current frame being recorded.
     * Returns null if not currently recording a tick.
     */
    @Nullable
    public TickFrame getCurrentFrame() {
        return currentFrame;
    }

    /**
     * Get frames for rewinding, from newest to oldest.
     * @param tickCount Number of ticks to rewind
     * @return List of frames in reverse chronological order (newest first)
     */
    public List<TickFrame> getFramesForRewind(int tickCount) {
        int count = Math.min(tickCount, frameCount);
        List<TickFrame> result = new ArrayList<>(count);
        
        // Start from most recent frame (one before writeHead)
        int readIndex = (writeHead - 1 + maxFrames) % maxFrames;
        
        for (int i = 0; i < count; i++) {
            TickFrame frame = frames[readIndex];
            if (frame != null) {
                result.add(frame);
            }
            readIndex = (readIndex - 1 + maxFrames) % maxFrames;
        }
        
        return result;
    }

    /**
     * Remove frames that have been rewound (they're no longer valid future).
     * @param tickCount Number of frames to remove from the buffer
     */
    public void removeRecentFrames(int tickCount) {
        int count = Math.min(tickCount, frameCount);
        
        for (int i = 0; i < count; i++) {
            writeHead = (writeHead - 1 + maxFrames) % maxFrames;
            TickFrame frame = frames[writeHead];
            if (frame != null) {
                totalMemoryUsed -= frame.getEstimatedMemoryBytes();
                frames[writeHead] = null;
            }
            frameCount--;
        }
    }

    /**
     * Clear all recorded frames.
     */
    /**
     * Freeze the timeline: stop recording (no new frames, no emergency frames).
     * Commits any pending frame first. History is preserved; rewind still works.
     */
    public void freeze() {
        if (currentFrame != null && !currentFrame.isSealed() && !currentFrame.isEmpty()) {
            commitCurrentFrame();
        }
        this.frozen = true;
    }

    public void clear() {
        for (int i = 0; i < maxFrames; i++) {
            frames[i] = null;
        }
        writeHead = 0;
        frameCount = 0;
        totalMemoryUsed = 0;
        currentFrame = null;
        frozen = false;
        LOGGER.info("Timeline buffer cleared");
    }

    /**
     * Drop oldest frames if memory exceeds limit.
     */
    private void enforceMemoryLimit() {
        while (totalMemoryUsed > MAX_MEMORY_BYTES && frameCount > TICKS_PER_SECOND * 5) {
            // Find oldest frame and remove it
            int oldestIndex = (writeHead - frameCount + maxFrames) % maxFrames;
            TickFrame oldFrame = frames[oldestIndex];
            if (oldFrame != null) {
                totalMemoryUsed -= oldFrame.getEstimatedMemoryBytes();
                frames[oldestIndex] = null;
            }
            frameCount--;
            LOGGER.warn("Dropped oldest frame due to memory pressure ({}MB used)",
                    totalMemoryUsed / (1024 * 1024));
        }
    }

    // State management

    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    public boolean isRecording() {
        return recording;
    }

    public void setRewinding(boolean rewinding) {
        this.rewinding = rewinding;
    }

    public boolean isRewinding() {
        return rewinding;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public boolean isFrozen() {
        return frozen;
    }

    // Status info

    public int getFrameCount() {
        return frameCount;
    }

    public int getMaxFrames() {
        return maxFrames;
    }

    public int getAvailableSeconds() {
        return frameCount / TICKS_PER_SECOND;
    }

    public long getTotalMemoryUsed() {
        return totalMemoryUsed;
    }

    @Nullable
    public Long getOldestTickTime() {
        if (frameCount == 0) return null;
        int oldestIndex = (writeHead - frameCount + maxFrames) % maxFrames;
        TickFrame oldestFrame = frames[oldestIndex];
        return oldestFrame != null ? oldestFrame.getGameTime() : null;
    }

    @Nullable
    public MinecraftServer getServer() {
        return server;
    }

    /**
     * Get status summary for the /timeline status command.
     */
    public String getStatusSummary() {
        return String.format(
                "Timeline Status:\n" +
                "  Recording: %s\n" +
                "  Frozen: %s\n" +
                "  Frames: %d / %d (%.1f seconds)\n" +
                "  Memory: %.2f MB / %.2f MB\n" +
                "  Oldest tick: %s",
                recording ? "Active" : "Paused",
                frozen ? "Yes" : "No",
                frameCount, maxFrames, frameCount / (float) TICKS_PER_SECOND,
                totalMemoryUsed / (1024.0 * 1024.0), MAX_MEMORY_BYTES / (1024.0 * 1024.0),
                getOldestTickTime() != null ? getOldestTickTime().toString() : "N/A"
        );
    }
}
