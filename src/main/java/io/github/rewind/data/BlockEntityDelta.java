package io.github.rewind.data;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Represents a block entity NBT change that occurred without a block state change.
 * This captures in-place modifications like chest inventory changes, furnace progress, etc.
 */
public record BlockEntityDelta(
        RegistryKey<World> dimension,
        long packedPos,
        Identifier blockEntityType,
        NbtCompound oldNbt,
        NbtCompound newNbt
) {
    /**
     * Create a BlockEntityDelta with unpacked BlockPos.
     */
    public static BlockEntityDelta create(
            RegistryKey<World> dimension,
            BlockPos pos,
            Identifier blockEntityType,
            NbtCompound oldNbt,
            NbtCompound newNbt
    ) {
        return new BlockEntityDelta(
                dimension,
                pos.asLong(),
                blockEntityType,
                oldNbt.copy(),
                newNbt.copy()
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
        return 8 + 8 + 32 + estimateNbtSize(oldNbt) + estimateNbtSize(newNbt);
    }

    private static int estimateNbtSize(NbtCompound nbt) {
        return 50 + nbt.getKeys().size() * 20;
    }

    @Override
    public String toString() {
        return String.format("BlockEntityDelta[%s @ %s type=%s]",
                dimension.getValue(),
                pos().toShortString(),
                blockEntityType);
    }
}
