package io.github.rewind.core;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable plan describing what a rewind would do (block targets, entity ops).
 * Used for both execution and preview (dry-run).
 */
public record RewindPlan(
        int frameCount,
        Map<BlockKey, BlockState> blockTargetStates,
        Map<BlockKey, NbtCompound> blockEntityTargetNbts,
        Map<BlockKey, NbtCompound> standaloneBeTargetNbts,
        Set<UUID> entitiesToRemove,
        Map<UUID, EntitySpawnInfo> entitiesToRespawn,
        Map<UUID, NbtCompound> entityTargetStates
) {
    public record BlockKey(RegistryKey<World> dimension, long packedPos) {}

    public record EntitySpawnInfo(
            RegistryKey<World> dimension,
            Identifier entityType,
            NbtCompound state
    ) {}
}
