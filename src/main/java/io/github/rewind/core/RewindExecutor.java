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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.ReadView;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Executes the rewind operation by applying deltas in reverse order.
 * Handles all the complexity of restoring world state safely.
 * Supports plan-only mode for preview (dry-run).
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
     * Build a rewind plan from frames (no world modification).
     */
    public static RewindPlan buildPlan(List<TickFrame> frames) {
        Set<UUID> entitiesToRemove = new HashSet<>();
        Map<UUID, RewindPlan.EntitySpawnInfo> entitiesToRespawn = new HashMap<>();
        Map<UUID, NbtCompound> entityTargetStates = new HashMap<>();

        for (TickFrame frame : frames) {
            for (EntityDelta delta : frame.getEntityDeltas()) {
                switch (delta.type()) {
                    case SPAWN -> entitiesToRemove.add(delta.entityId());
                    case DESPAWN -> {
                        if (delta.oldState() != null && delta.entityType() != null) {
                            entitiesToRespawn.put(delta.entityId(),
                                    new RewindPlan.EntitySpawnInfo(delta.dimension(), delta.entityType(), delta.oldState()));
                        }
                    }
                    case UPDATE -> {
                        if (delta.oldState() != null) {
                            entityTargetStates.put(delta.entityId(), delta.oldState());
                        }
                    }
                }
            }
        }

        Map<RewindPlan.BlockKey, BlockState> blockTargetStates = new LinkedHashMap<>();
        Map<RewindPlan.BlockKey, NbtCompound> blockEntityTargetNbts = new LinkedHashMap<>();
        for (TickFrame frame : frames) {
            for (BlockDelta delta : frame.getBlockDeltas()) {
                RewindPlan.BlockKey key = new RewindPlan.BlockKey(delta.dimension(), delta.packedPos());
                blockTargetStates.put(key, delta.oldState());
                if (delta.oldBlockEntityNbt() != null) {
                    blockEntityTargetNbts.put(key, delta.oldBlockEntityNbt());
                }
            }
        }

        Map<RewindPlan.BlockKey, NbtCompound> standaloneBeTargetNbts = new LinkedHashMap<>();
        for (TickFrame frame : frames) {
            for (BlockEntityDelta delta : frame.getBlockEntityDeltas()) {
                RewindPlan.BlockKey key = new RewindPlan.BlockKey(delta.dimension(), delta.packedPos());
                standaloneBeTargetNbts.put(key, delta.oldNbt());
            }
        }

        return new RewindPlan(
                frames.size(),
                blockTargetStates,
                blockEntityTargetNbts,
                standaloneBeTargetNbts,
                entitiesToRemove,
                entitiesToRespawn,
                entityTargetStates
        );
    }

    /**
     * Apply a rewind plan to the world.
     */
    public static RewindResult applyPlan(MinecraftServer server, RewindPlan plan) {
        List<String> warnings = new ArrayList<>();
        int blocksRestored = 0;
        int blockEntitiesRestored = 0;
        int entitiesRestored = 0;
        int entitiesRemoved = 0;

        for (UUID entityId : plan.entitiesToRemove()) {
            for (ServerWorld world : server.getWorlds()) {
                Entity entity = world.getEntity(entityId);
                if (entity != null) {
                    entity.discard();
                    entitiesRemoved++;
                    break;
                }
            }
        }

        for (Map.Entry<UUID, NbtCompound> entry : plan.entityTargetStates().entrySet()) {
            UUID entityId = entry.getKey();
            if (plan.entitiesToRemove().contains(entityId)) continue;
            Entity entity = findEntity(server, entityId);
            if (entity != null) {
                restoreEntityState(entity, entry.getValue());
                entitiesRestored++;
            }
        }

        for (Map.Entry<UUID, RewindPlan.EntitySpawnInfo> entry : plan.entitiesToRespawn().entrySet()) {
            UUID entityId = entry.getKey();
            if (plan.entitiesToRemove().contains(entityId)) continue;
            RewindPlan.EntitySpawnInfo info = entry.getValue();
            ServerWorld world = server.getWorld(info.dimension());
            if (world == null) {
                warnings.add("Cannot respawn entity: dimension not loaded");
                continue;
            }
            if (world.getEntity(entityId) != null) continue;
            Entity respawned = respawnEntity(world, info.entityType(), info.state(), entityId);
            if (respawned != null) entitiesRestored++;
            else warnings.add("Failed to respawn entity " + entityId);
        }

        for (Map.Entry<RewindPlan.BlockKey, BlockState> e : plan.blockTargetStates().entrySet()) {
            RewindPlan.BlockKey key = e.getKey();
            ServerWorld world = server.getWorld(key.dimension());
            if (world == null) {
                warnings.add("Cannot restore block: dimension not loaded");
                continue;
            }
            BlockPos pos = BlockPos.fromLong(key.packedPos());
            if (!ensureChunkLoaded(world, pos)) {
                warnings.add("Cannot restore block at " + pos.toShortString() + ": chunk not loaded");
                continue;
            }
            if (world.setBlockState(pos, e.getValue(), Block.NOTIFY_ALL | Block.FORCE_STATE)) {
                blocksRestored++;
            }
            NbtCompound targetNbt = plan.blockEntityTargetNbts().get(key);
            if (targetNbt != null) {
                BlockEntity be = world.getBlockEntity(pos);
                if (be != null) {
                    ReadView readView = NbtReadView.create(ErrorReporter.EMPTY, world.getRegistryManager(), targetNbt);
                    be.read(readView);
                    be.markDirty();
                    blockEntitiesRestored++;
                }
            }
        }

        for (Map.Entry<RewindPlan.BlockKey, NbtCompound> entry : plan.standaloneBeTargetNbts().entrySet()) {
            RewindPlan.BlockKey key = entry.getKey();
            if (plan.blockEntityTargetNbts().containsKey(key)) continue;
            ServerWorld world = server.getWorld(key.dimension());
            if (world == null) continue;
            BlockPos pos = BlockPos.fromLong(key.packedPos());
            if (!ensureChunkLoaded(world, pos)) continue;
            BlockEntity be = world.getBlockEntity(pos);
            if (be != null) {
                ReadView readView = NbtReadView.create(ErrorReporter.EMPTY, world.getRegistryManager(), entry.getValue());
                be.read(readView);
                be.markDirty();
                blockEntitiesRestored++;
            }
        }

        return new RewindResult(true, plan.frameCount(), blocksRestored, blockEntitiesRestored,
                entitiesRestored, entitiesRemoved, warnings);
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
        RewindPlan plan = buildPlan(frames);
        manager.setRewinding(true);
        manager.setRecording(false);

        try {
            RewindResult result = applyPlan(server, plan);
            manager.removeRecentFrames(frames.size());
            LOGGER.info("Rewind complete: {} blocks, {} BEs, {} entities restored, {} entities removed",
                    result.blocksRestored(), result.blockEntitiesRestored(), result.entitiesRestored(), result.entitiesRemoved());
            return result;
        } catch (Exception e) {
            LOGGER.error("Rewind failed with exception", e);
            return new RewindResult(false, 0, 0, 0, 0, 0, List.of("Exception: " + e.getMessage()));
        } finally {
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
}
