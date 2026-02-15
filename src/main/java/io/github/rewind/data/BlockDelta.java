package io.github.rewind.data;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single block state change within a tick.
 * Stores both old and new state to enable forward replay and reverse rewind.
 */
public record BlockDelta(
        RegistryKey<World> dimension,
        long packedPos,           // BlockPos.asLong() for memory efficiency
        BlockState oldState,      // State BEFORE this change
        BlockState newState,      // State AFTER this change
        @Nullable NbtCompound oldBlockEntityNbt,  // Block entity NBT before (if existed)
        @Nullable NbtCompound newBlockEntityNbt   // Block entity NBT after (if exists)
) {
    /**
     * Create a BlockDelta with unpacked BlockPos.
     */
    public static BlockDelta create(
            RegistryKey<World> dimension,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            @Nullable NbtCompound oldBENbt,
            @Nullable NbtCompound newBENbt
    ) {
        return new BlockDelta(
                dimension,
                pos.asLong(),
                oldState,
                newState,
                oldBENbt != null ? oldBENbt.copy() : null,
                newBENbt != null ? newBENbt.copy() : null
        );
    }

    /**
     * Get the BlockPos from packed long.
     */
    public BlockPos pos() {
        return BlockPos.fromLong(packedPos);
    }

    /**
     * Estimate memory usage of this delta in bytes.
     */
    public int estimateMemoryBytes() {
        int size = 8 + 8 + 16 + 16; // dimension ref + packedPos + state refs (rough)
        if (oldBlockEntityNbt != null) {
            size += estimateNbtSize(oldBlockEntityNbt);
        }
        if (newBlockEntityNbt != null) {
            size += estimateNbtSize(newBlockEntityNbt);
        }
        return size;
    }

    private static int estimateNbtSize(NbtCompound nbt) {
        // Rough estimate: 50 bytes base + 20 bytes per key
        return 50 + nbt.getKeys().size() * 20;
    }

    @Override
    public String toString() {
        return String.format("BlockDelta[%s @ %s: %s -> %s]",
                dimension.getValue(),
                pos().toShortString(),
                oldState.getBlock().getName().getString(),
                newState.getBlock().getName().getString());
    }
}
