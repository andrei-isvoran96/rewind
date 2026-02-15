package io.github.rewind.config;

/**
 * Configuration for the Rewind mod.
 * 
 * For v1, these are compile-time constants.
 * Future versions could load these from a config file.
 */
public final class RewindConfig {
    
    private RewindConfig() {} // Prevent instantiation
    
    // ========== Timing ==========
    
    /**
     * Maximum number of seconds that can be rewound.
     */
    public static final int MAX_REWIND_SECONDS = 30;
    
    /**
     * Ticks per second (Minecraft constant).
     */
    public static final int TICKS_PER_SECOND = 20;
    
    /**
     * Maximum number of frames in the ring buffer.
     */
    public static final int MAX_FRAMES = MAX_REWIND_SECONDS * TICKS_PER_SECOND;
    
    // ========== Memory ==========
    
    /**
     * Maximum memory usage for the timeline buffer in bytes.
     * When exceeded, oldest frames are dropped.
     */
    public static final long MAX_MEMORY_BYTES = 50 * 1024 * 1024; // 50 MB
    
    /**
     * Minimum frames to keep even under memory pressure.
     * Ensures at least some rewind capability.
     */
    public static final int MIN_FRAMES_UNDER_PRESSURE = 5 * TICKS_PER_SECOND; // 5 seconds
    
    // ========== Permissions ==========
    
    /**
     * Required operator permission level for timeline commands.
     * 0 = everyone, 1 = moderators, 2 = game masters, 3 = admins, 4 = owners
     */
    public static final int REQUIRED_PERMISSION_LEVEL = 2;
    
    // ========== Behavior ==========
    
    /**
     * Whether to track entity states.
     * Disabling can improve performance but entities won't rewind.
     */
    public static final boolean TRACK_ENTITIES = true;
    
    /**
     * Whether to track block entity (tile entity) changes.
     * Includes chests, furnaces, etc.
     */
    public static final boolean TRACK_BLOCK_ENTITIES = true;
    
    /**
     * Whether to broadcast rewind operations to all players.
     */
    public static final boolean BROADCAST_REWIND = true;
    
    /**
     * Block update flags used when restoring blocks.
     * NOTIFY_LISTENERS (2) sends updates to clients.
     * FORCE_STATE (16) prevents block update cascades.
     */
    public static final int RESTORE_BLOCK_FLAGS = 2 | 16; // Block.NOTIFY_LISTENERS | Block.FORCE_STATE
    
    // ========== Limits ==========
    
    /**
     * Maximum number of block changes to record per tick.
     * Prevents runaway memory usage from explosions or world edit.
     */
    public static final int MAX_BLOCK_CHANGES_PER_TICK = 10000;
    
    /**
     * Maximum number of entity changes to record per tick.
     */
    public static final int MAX_ENTITY_CHANGES_PER_TICK = 1000;
    
    // ========== Debug ==========
    
    /**
     * Enable verbose logging of recorded changes.
     * Warning: Very spammy in active worlds!
     */
    public static final boolean DEBUG_LOGGING = false;
}
