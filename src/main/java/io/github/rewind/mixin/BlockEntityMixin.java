package io.github.rewind.mixin;

import io.github.rewind.core.TickRecorder;
import io.github.rewind.core.TimelineManager;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to track block entity NBT changes.
 * Captures in-place modifications (e.g., chest inventory changes, furnace progress).
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Shadow
    public abstract BlockPos getPos();

    @Shadow
    public abstract World getWorld();

    /**
     * Stores the NBT state at the start of the tick for comparison.
     */
    @Unique
    private NbtCompound rewind$previousNbt = null;

    @Unique
    private long rewind$lastSnapshotTick = -1;

    /**
     * Capture NBT state when markDirty() is called.
     * This is called whenever a block entity's data changes.
     */
    @Inject(method = "markDirty()V", at = @At("HEAD"))
    private void rewind$onMarkDirty(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        World world = getWorld();
        
        if (world == null || world.isClient()) {
            return;
        }
        
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null || !manager.isRecording() || manager.isRewinding()) {
            return;
        }
        
        // Get current server tick
        long currentTick = world.getTime();
        
        // Take a snapshot at the start of each tick
        if (rewind$lastSnapshotTick != currentTick) {
            // New tick - save current state as "previous"
            rewind$previousNbt = self.createNbt(world.getRegistryManager());
            rewind$lastSnapshotTick = currentTick;
        }
    }

    /**
     * At the end of processing, compare and record if changed.
     * This approach avoids recording every single markDirty() call.
     * 
     * Note: We rely on the tick end event to finalize block entity changes.
     * This method is called to prepare the comparison data.
     */
    @Inject(method = "markDirty()V", at = @At("RETURN"))
    private void rewind$afterMarkDirty(CallbackInfo ci) {
        // The actual delta recording happens in TickRecorder at tick end
        // by comparing all block entities that were marked dirty.
        // This mixin just ensures we have the "before" state cached.
    }
}
