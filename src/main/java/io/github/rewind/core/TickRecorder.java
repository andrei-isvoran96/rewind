package io.github.rewind.core;

import io.github.rewind.data.BlockDelta;
import io.github.rewind.data.BlockEntityDelta;
import io.github.rewind.data.EntityDelta;
import io.github.rewind.data.TickFrame;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles recording of world changes into TickFrames.
 * This class provides the hooks that mixins call into.
 */
public class TickRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rewind");
    
    // Track entity states from previous tick for diffing
    private static final Map<UUID, NbtCompound> previousEntityStates = new HashMap<>();
    
    // Track which entities existed at start of tick (for spawn detection)
    private static final Set<UUID> entitiesAtTickStart = ConcurrentHashMap.newKeySet();
    
    // Track entities that were removed this tick (for despawn detection)
    private static final Map<UUID, EntityRemovalInfo> removedEntitiesThisTick = new ConcurrentHashMap<>();
    
    // Entity types we exclude from tracking (players handled separately, some don't serialize well)
    private static final Set<String> EXCLUDED_ENTITY_TYPES = Set.of(
            "minecraft:player",           // Players handled separately
            "minecraft:lightning_bolt",   // Transient
            "minecraft:marker"            // Technical entity
    );

    /**
     * Record a block state change.
     */
    public static void recordBlockChange(
            World world,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            @Nullable BlockEntity oldBlockEntity,
            @Nullable BlockEntity newBlockEntity
    ) {
        if (world.isClient()) return;
        
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null || !manager.isRecording() || manager.isRewinding()) {
            return;
        }
        
        TickFrame frame = manager.getCurrentFrame();
        if (frame == null) {
            manager.ensureCurrentFrame();
            frame = manager.getCurrentFrame();
            if (frame == null) {
                return;
            }
        }
        if (frame.isSealed()) {
            return;
        }
        
        if (oldState.equals(newState)) {
            return;
        }
        
        NbtCompound oldBENbt = null;
        NbtCompound newBENbt = null;
        
        if (oldBlockEntity != null) {
            oldBENbt = oldBlockEntity.createNbt(world.getRegistryManager());
        }
        if (newBlockEntity != null) {
            newBENbt = newBlockEntity.createNbt(world.getRegistryManager());
        }
        
        RegistryKey<World> dimension = world.getRegistryKey();
        BlockDelta delta = BlockDelta.create(dimension, pos, oldState, newState, oldBENbt, newBENbt);
        frame.addBlockDelta(delta);
    }

    /**
     * Record a block entity NBT change (in-place modification).
     * Called when a block entity's data changes without a block state change.
     */
    public static void recordBlockEntityChange(
            World world,
            BlockPos pos,
            BlockEntity blockEntity,
            NbtCompound oldNbt,
            NbtCompound newNbt
    ) {
        if (world.isClient()) return;
        
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null || !manager.isRecording() || manager.isRewinding()) {
            return;
        }
        
        TickFrame frame = manager.getCurrentFrame();
        if (frame == null || frame.isSealed()) {
            return;
        }
        
        // Skip if NBT didn't actually change
        if (oldNbt.equals(newNbt)) {
            return;
        }
        
        Identifier beType = Registries.BLOCK_ENTITY_TYPE.getId(blockEntity.getType());
        if (beType == null) {
            return;
        }
        
        RegistryKey<World> dimension = world.getRegistryKey();
        BlockEntityDelta delta = BlockEntityDelta.create(dimension, pos, beType, oldNbt, newNbt);
        frame.addBlockEntityDelta(delta);
    }

    /**
     * Called at the start of each server tick to snapshot entity states.
     */
    public static void beginTickEntityTracking(ServerWorld world) {
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null || !manager.isRecording() || manager.isRewinding()) {
            return;
        }
        
        // Record which entities exist at tick start
        entitiesAtTickStart.clear();
        removedEntitiesThisTick.clear();
        
        for (Entity entity : world.iterateEntities()) {
            if (shouldTrackEntity(entity)) {
                entitiesAtTickStart.add(entity.getUuid());
            }
        }
    }

    /**
     * Called at the end of each server tick to detect entity changes.
     */
    public static void endTickEntityTracking(ServerWorld world) {
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null || !manager.isRecording() || manager.isRewinding()) {
            return;
        }
        
        TickFrame frame = manager.getCurrentFrame();
        if (frame == null || frame.isSealed()) {
            return;
        }
        
        RegistryKey<World> dimension = world.getRegistryKey();
        
        // Check for new entities (spawns) and state changes
        for (Entity entity : world.iterateEntities()) {
            if (!shouldTrackEntity(entity)) continue;
            
            UUID uuid = entity.getUuid();
            NbtCompound currentState = serializeEntityState(entity);
            
            if (!entitiesAtTickStart.contains(uuid)) {
                // New entity this tick - record spawn
                Identifier entityType = Registries.ENTITY_TYPE.getId(entity.getType());
                frame.addEntityDelta(EntityDelta.spawn(dimension, uuid, entityType, currentState));
            } else {
                // Existing entity - check for state change
                NbtCompound previousState = previousEntityStates.get(uuid);
                if (previousState != null && !previousState.equals(currentState)) {
                    frame.addEntityDelta(EntityDelta.update(dimension, uuid, previousState, currentState));
                }
            }
            
            // Update previous state for next tick
            previousEntityStates.put(uuid, currentState);
        }
        
        // Process removed entities (despawns)
        for (Map.Entry<UUID, EntityRemovalInfo> entry : removedEntitiesThisTick.entrySet()) {
            UUID uuid = entry.getKey();
            EntityRemovalInfo info = entry.getValue();
            
            // Only record despawn if entity existed at tick start
            if (entitiesAtTickStart.contains(uuid)) {
                frame.addEntityDelta(EntityDelta.despawn(dimension, uuid, info.entityType, info.finalState));
            }
            
            // Clean up tracking data
            previousEntityStates.remove(uuid);
        }
    }

    /**
     * Called when an entity is removed from the world.
     */
    public static void recordEntityRemoval(Entity entity) {
        if (!shouldTrackEntity(entity)) return;
        
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null || !manager.isRecording() || manager.isRewinding()) {
            return;
        }
        
        Identifier entityType = Registries.ENTITY_TYPE.getId(entity.getType());
        NbtCompound finalState = serializeEntityState(entity);
        
        removedEntitiesThisTick.put(entity.getUuid(), new EntityRemovalInfo(entityType, finalState));
    }

    /**
     * Clear all tracking data.
     */
    public static void clearTrackingData() {
        previousEntityStates.clear();
        entitiesAtTickStart.clear();
        removedEntitiesThisTick.clear();
    }

    /**
     * Determine if we should track this entity.
     */
    private static boolean shouldTrackEntity(Entity entity) {
        // Don't track players (handled separately if at all)
        if (entity instanceof PlayerEntity) {
            return false;
        }
        
        Identifier entityType = Registries.ENTITY_TYPE.getId(entity.getType());
        if (entityType != null && EXCLUDED_ENTITY_TYPES.contains(entityType.toString())) {
            return false;
        }
        
        return true;
    }

    /**
     * Serialize entity state to NBT for storage.
     * Captures position, rotation, velocity, and entity-specific data.
     */
    private static NbtCompound serializeEntityState(Entity entity) {
        NbtCompound nbt = new NbtCompound();
        
        // Position
        nbt.putDouble("X", entity.getX());
        nbt.putDouble("Y", entity.getY());
        nbt.putDouble("Z", entity.getZ());
        
        // Rotation
        nbt.putFloat("Yaw", entity.getYaw());
        nbt.putFloat("Pitch", entity.getPitch());
        
        // Velocity
        nbt.putDouble("VelX", entity.getVelocity().x);
        nbt.putDouble("VelY", entity.getVelocity().y);
        nbt.putDouble("VelZ", entity.getVelocity().z);
        
        // On ground
        nbt.putBoolean("OnGround", entity.isOnGround());
        
        // Living entity specific
        if (entity instanceof LivingEntity living) {
            nbt.putFloat("Health", living.getHealth());
            nbt.putInt("HurtTime", living.hurtTime);
            nbt.putInt("DeathTime", living.deathTime);
        }
        
        // Note: For v1, we only track position/velocity/health.
        // Full NBT serialization (inventory, AI state, etc.) is deferred to v2
        // as it requires careful handling of different entity types.
        
        return nbt;
    }

    /**
     * Info about a removed entity, stored until end of tick.
     */
    private record EntityRemovalInfo(Identifier entityType, NbtCompound finalState) {}
}
