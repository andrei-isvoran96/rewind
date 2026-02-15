package io.github.rewind.core;

import io.github.rewind.data.BlockDelta;
import io.github.rewind.data.BlockEntityDelta;
import io.github.rewind.data.EntityDelta;
import io.github.rewind.data.TickFrame;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.ReadView;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Optional;

/**
 * Executes the rewind operation by applying deltas in reverse order.
 * Handles all the complexity of restoring world state safely.
 */
public class RewindExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rewind");

    /**
     * Result of a rewind operation.
     */
    public record RewindResult(
            boolean success,
            int ticksRewound,
            int blocksRestored,
            int blockEntitiesRestored,
            int entitiesRestored,
            int entitiesRemoved,
            List<String> warnings) {
        public Text toText() {
            if (success) {
                return Text.literal(String.format(
                        "Rewind complete! Rewound %d ticks (%.1fs)\n" +
                                "Restored: %d blocks, %d block entities, %d entities\n" +
                                "Removed: %d entities spawned after target time" +
                                (warnings.isEmpty() ? "" : "\nWarnings: " + warnings.size()),
                        ticksRewound, ticksRewound / 20.0,
                        blocksRestored, blockEntitiesRestored, entitiesRestored,
                        entitiesRemoved));
            } else {
                return Text.literal("Rewind failed! " + (warnings.isEmpty() ? "" : warnings.get(0)));
            }
        }
    }

    /**
     * Execute a rewind operation.
     *
     * @param server  The Minecraft server
     * @param seconds Number of seconds to rewind (1-30)
     * @return Result of the operation
     */
    public static RewindResult execute(MinecraftServer server, int seconds) {
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null) {
            return new RewindResult(false, 0, 0, 0, 0, 0, List.of("Timeline system not initialized"));
        }

        if (manager.isRewinding()) {
            return new RewindResult(false, 0, 0, 0, 0, 0, List.of("Rewind already in progress"));
        }

        int ticksToRewind = seconds * TimelineManager.TICKS_PER_SECOND;
        List<TickFrame> frames = manager.getFramesForRewind(ticksToRewind);

        if (frames.isEmpty()) {
            return new RewindResult(false, 0, 0, 0, 0, 0, List.of("No frames available to rewind"));
        }

        LOGGER.info("Starting rewind of {} frames ({} seconds)", frames.size(), seconds);

        // Begin rewind - disable recording
        manager.setRewinding(true);
        manager.setRecording(false);

        List<String> warnings = new ArrayList<>();
        int blocksRestored = 0;
        int blockEntitiesRestored = 0;
        int entitiesRestored = 0;
        int entitiesRemoved = 0;

        // Track entities that need to be removed (spawned after target time)
        Set<UUID> entitiesToRemove = new HashSet<>();
        // Track entities that need to be respawned (despawned after target time)
        Map<UUID, EntitySpawnInfo> entitiesToRespawn = new HashMap<>();

        try {
            // First pass: Collect all entity operations
            for (TickFrame frame : frames) {
                for (EntityDelta delta : frame.getEntityDeltas()) {
                    switch (delta.type()) {
                        case SPAWN -> {
                            // Entity spawned after target - needs to be removed
                            entitiesToRemove.add(delta.entityId());
                        }
                        case DESPAWN -> {
                            // Entity despawned after target - needs to be respawned
                            if (delta.oldState() != null && delta.entityType() != null) {
                                entitiesToRespawn.put(delta.entityId(),
                                        new EntitySpawnInfo(delta.dimension(), delta.entityType(), delta.oldState()));
                            }
                        }
                        case UPDATE -> {
                            // Will handle in second pass
                        }
                    }
                }
            }

            // Phase 1: Remove entities that were spawned after target time
            for (UUID entityId : entitiesToRemove) {
                for (ServerWorld world : server.getWorlds()) {
                    Entity entity = world.getEntity(entityId);
                    if (entity != null) {
                        entity.discard();
                        entitiesRemoved++;
                        break;
                    }
                }
            }

            // Phase 2: Restore entity states (position, health, etc.)
            // Process frames from newest to oldest
            // We want the oldState from the OLDEST frame for each entity
            // Since frames are newest-first, overwrite so last (oldest) wins
            Map<UUID, NbtCompound> entityTargetStates = new HashMap<>();
            for (TickFrame frame : frames) {
                for (EntityDelta delta : frame.getEntityDeltas()) {
                    if (delta.type() == EntityDelta.EntityDeltaType.UPDATE && delta.oldState() != null) {
                        // Overwrite so oldest frame's oldState wins
                        entityTargetStates.put(delta.entityId(), delta.oldState());
                    }
                }
            }

            for (Map.Entry<UUID, NbtCompound> entry : entityTargetStates.entrySet()) {
                UUID entityId = entry.getKey();
                NbtCompound targetState = entry.getValue();

                // Skip if entity was removed
                if (entitiesToRemove.contains(entityId))
                    continue;

                Entity entity = findEntity(server, entityId);
                if (entity != null) {
                    restoreEntityState(entity, targetState);
                    entitiesRestored++;
                }
            }

            // Phase 3: Respawn despawned entities
            for (Map.Entry<UUID, EntitySpawnInfo> entry : entitiesToRespawn.entrySet()) {
                UUID entityId = entry.getKey();
                EntitySpawnInfo info = entry.getValue();

                // Skip if this entity was also spawned after target (transient)
                if (entitiesToRemove.contains(entityId))
                    continue;

                ServerWorld world = server.getWorld(info.dimension);
                if (world == null) {
                    warnings.add("Cannot respawn entity: dimension not loaded");
                    continue;
                }

                // Check if entity already exists (shouldn't, but safety check)
                if (world.getEntity(entityId) != null)
                    continue;

                Entity respawned = respawnEntity(world, info.entityType, info.state, entityId);
                if (respawned != null) {
                    entitiesRestored++;
                } else {
                    warnings.add("Failed to respawn entity " + entityId);
                }
            }

            // Phase 4: Restore blocks
            // Process frames from newest to oldest
            // We need to find the TARGET state = the oldState from the OLDEST delta for
            // each position
            // Since frames are ordered newest-first, we need to OVERWRITE (not putIfAbsent)
            // so the last write wins (= oldest frame's oldState)
            Map<BlockKey, BlockState> blockTargetStates = new LinkedHashMap<>();
            Map<BlockKey, NbtCompound> blockEntityTargetNbts = new LinkedHashMap<>();

            for (TickFrame frame : frames) {
                for (BlockDelta delta : frame.getBlockDeltas()) {
                    BlockKey key = new BlockKey(delta.dimension(), delta.packedPos());
                    // Overwrite so oldest frame's oldState wins (last iteration = oldest frame)
                    blockTargetStates.put(key, delta.oldState());
                    if (delta.oldBlockEntityNbt() != null) {
                        blockEntityTargetNbts.put(key, delta.oldBlockEntityNbt());
                    }
                }
            }

            // Apply block changes
            for (Map.Entry<BlockKey, BlockState> entry : blockTargetStates.entrySet()) {
                BlockKey key = entry.getKey();
                BlockState targetState = entry.getValue();

                ServerWorld world = server.getWorld(key.dimension);
                if (world == null) {
                    warnings.add("Cannot restore block: dimension not loaded");
                    continue;
                }

                BlockPos pos = BlockPos.fromLong(key.packedPos);
                BlockState currentState = world.getBlockState(pos);

                // Ensure chunk is loaded
                if (!ensureChunkLoaded(world, pos)) {
                    warnings.add("Cannot restore block at " + pos.toShortString() + ": chunk not loaded");
                    continue;
                }

                boolean success = world.setBlockState(pos, targetState, Block.NOTIFY_ALL | Block.FORCE_STATE);
                if (success) {
                    blocksRestored++;
                }

                // Restore block entity NBT if applicable
                NbtCompound targetNbt = blockEntityTargetNbts.get(key);
                if (targetNbt != null) {
                    BlockEntity be = world.getBlockEntity(pos);
                    if (be != null) {
                        ReadView readView = NbtReadView.create(
                                ErrorReporter.EMPTY,
                                world.getRegistryManager(),
                                targetNbt);
                        be.read(readView);
                        be.markDirty();
                        blockEntitiesRestored++;
                    }
                }
            }

            // Phase 5: Restore standalone block entity changes (no block state change)
            // Same logic: oldest frame's oldNbt wins
            Map<BlockKey, NbtCompound> standaloneBeTargetNbts = new LinkedHashMap<>();
            for (TickFrame frame : frames) {
                for (BlockEntityDelta delta : frame.getBlockEntityDeltas()) {
                    BlockKey key = new BlockKey(delta.dimension(), delta.packedPos());
                    standaloneBeTargetNbts.put(key, delta.oldNbt());
                }
            }

            for (Map.Entry<BlockKey, NbtCompound> entry : standaloneBeTargetNbts.entrySet()) {
                BlockKey key = entry.getKey();

                // Skip if already handled by block delta
                if (blockEntityTargetNbts.containsKey(key))
                    continue;

                NbtCompound targetNbt = entry.getValue();

                ServerWorld world = server.getWorld(key.dimension);
                if (world == null)
                    continue;

                BlockPos pos = BlockPos.fromLong(key.packedPos);

                if (!ensureChunkLoaded(world, pos))
                    continue;

                BlockEntity be = world.getBlockEntity(pos);
                if (be != null) {
                    ReadView readView = NbtReadView.create(
                            ErrorReporter.EMPTY,
                            world.getRegistryManager(),
                            targetNbt);
                    be.read(readView);
                    be.markDirty();
                    blockEntitiesRestored++;
                }
            }

            // Remove the rewound frames from the buffer (they're no longer valid future)
            manager.removeRecentFrames(frames.size());

            LOGGER.info("Rewind complete: {} blocks, {} BEs, {} entities restored, {} entities removed",
                    blocksRestored, blockEntitiesRestored, entitiesRestored, entitiesRemoved);

            return new RewindResult(true, frames.size(), blocksRestored, blockEntitiesRestored,
                    entitiesRestored, entitiesRemoved, warnings);

        } catch (Exception e) {
            LOGGER.error("Rewind failed with exception", e);
            warnings.add("Exception: " + e.getMessage());
            return new RewindResult(false, 0, blocksRestored, blockEntitiesRestored,
                    entitiesRestored, entitiesRemoved, warnings);

        } finally {
            // Re-enable recording
            manager.setRewinding(false);
            manager.setRecording(true);
            TickRecorder.clearTrackingData();
        }
    }

    /**
     * Find an entity by UUID across all worlds.
     */
    private static Entity findEntity(MinecraftServer server, UUID entityId) {
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    /**
     * Restore an entity's state from NBT.
     */
    private static void restoreEntityState(Entity entity, NbtCompound state) {
        // Restore position
        if (state.contains("X") && state.contains("Y") && state.contains("Z")) {
            double x = state.getDouble("X").orElse(entity.getX());
            double y = state.getDouble("Y").orElse(entity.getY());
            double z = state.getDouble("Z").orElse(entity.getZ());
            entity.setPosition(x, y, z);
        }

        // Restore rotation
        if (state.contains("Yaw") && state.contains("Pitch")) {
            entity.setYaw(state.getFloat("Yaw").orElse(entity.getYaw()));
            entity.setPitch(state.getFloat("Pitch").orElse(entity.getPitch()));
        }

        // Restore velocity
        if (state.contains("VelX") && state.contains("VelY") && state.contains("VelZ")) {
            entity.setVelocity(new Vec3d(
                    state.getDouble("VelX").orElse(0.0),
                    state.getDouble("VelY").orElse(0.0),
                    state.getDouble("VelZ").orElse(0.0)));
        }

        // Restore living entity specifics
        if (entity instanceof LivingEntity living) {
            state.getFloat("Health").ifPresent(living::setHealth);
        }

        // Note: For v1, we only restore position/velocity/health.
        // Full NBT restoration (inventory, AI state, etc.) is deferred to v2.

        // Sync to clients
        entity.velocityDirty = true;
    }

    /**
     * Respawn an entity that was despawned.
     */
    private static Entity respawnEntity(ServerWorld world, net.minecraft.util.Identifier entityTypeId,
            NbtCompound state, UUID targetUuid) {
        try {
            EntityType<?> entityType = Registries.ENTITY_TYPE.get(entityTypeId);
            if (entityType == null) {
                LOGGER.warn("Unknown entity type: {}", entityTypeId);
                return null;
            }

            // Get position from state
            double x = state.getDouble("X").orElse(0.0);
            double y = state.getDouble("Y").orElse(64.0);
            double z = state.getDouble("Z").orElse(0.0);

            // Ensure chunk is loaded
            BlockPos pos = BlockPos.ofFloored(x, y, z);
            if (!ensureChunkLoaded(world, pos)) {
                LOGGER.warn("Cannot respawn entity: chunk not loaded at {}", pos);
                return null;
            }

            // Create the entity using SpawnReason
            Entity entity = entityType.create(world, SpawnReason.COMMAND);
            if (entity == null) {
                LOGGER.warn("Failed to create entity of type {}", entityTypeId);
                return null;
            }

            // Set position first
            entity.setPosition(x, y, z);

            // Restore state
            restoreEntityState(entity, state);

            // Spawn the entity
            world.spawnEntity(entity);

            return entity;

        } catch (Exception e) {
            LOGGER.error("Failed to respawn entity", e);
            return null;
        }
    }

    /**
     * Ensure a chunk is loaded, force-loading if necessary.
     */
    private static boolean ensureChunkLoaded(ServerWorld world, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z);
        if (chunk != null) {
            return true;
        }

        // Try to load the chunk
        chunk = world.getChunk(chunkPos.x, chunkPos.z);
        return chunk != null;
    }

    /**
     * Key for tracking block positions across dimensions.
     */
    private record BlockKey(RegistryKey<World> dimension, long packedPos) {
    }

    /**
     * Info needed to respawn an entity.
     */
    private record EntitySpawnInfo(
            RegistryKey<World> dimension,
            net.minecraft.util.Identifier entityType,
            NbtCompound state) {
    }
}
