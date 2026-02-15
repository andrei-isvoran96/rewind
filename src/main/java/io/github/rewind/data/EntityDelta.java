package io.github.rewind.data;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Represents an entity state change within a tick.
 */
public record EntityDelta(
        RegistryKey<World> dimension,
        UUID entityId,
        EntityDeltaType type,
        @Nullable Identifier entityType,  // Required for SPAWN
        @Nullable NbtCompound oldState,   // State before (null for SPAWN)
        @Nullable NbtCompound newState    // State after (null for DESPAWN)
) {
    /**
     * Types of entity changes we track.
     */
    public enum EntityDeltaType {
        SPAWN,      // Entity was created/loaded this tick
        DESPAWN,    // Entity was removed/unloaded this tick
        UPDATE      // Entity state changed (position, health, etc.)
    }

    /**
     * Create a SPAWN delta for a newly created entity.
     */
    public static EntityDelta spawn(
            RegistryKey<World> dimension,
            UUID entityId,
            Identifier entityType,
            NbtCompound initialState
    ) {
        return new EntityDelta(dimension, entityId, EntityDeltaType.SPAWN, entityType, null, initialState.copy());
    }

    /**
     * Create a DESPAWN delta for a removed entity.
     */
    public static EntityDelta despawn(
            RegistryKey<World> dimension,
            UUID entityId,
            Identifier entityType,
            NbtCompound finalState
    ) {
        return new EntityDelta(dimension, entityId, EntityDeltaType.DESPAWN, entityType, finalState.copy(), null);
    }

    /**
     * Create an UPDATE delta for entity state change.
     */
    public static EntityDelta update(
            RegistryKey<World> dimension,
            UUID entityId,
            NbtCompound oldState,
            NbtCompound newState
    ) {
        return new EntityDelta(dimension, entityId, EntityDeltaType.UPDATE, null, oldState.copy(), newState.copy());
    }

    /**
     * Estimate memory usage of this delta in bytes.
     */
    public int estimateMemoryBytes() {
        int size = 8 + 16 + 4 + 32; // dimension ref + UUID + type + entityType ref
        if (oldState != null) {
            size += estimateNbtSize(oldState);
        }
        if (newState != null) {
            size += estimateNbtSize(newState);
        }
        return size;
    }

    private static int estimateNbtSize(NbtCompound nbt) {
        return 50 + nbt.getKeys().size() * 20;
    }

    @Override
    public String toString() {
        return String.format("EntityDelta[%s %s in %s type=%s]",
                type,
                entityId,
                dimension.getValue(),
                entityType);
    }
}
