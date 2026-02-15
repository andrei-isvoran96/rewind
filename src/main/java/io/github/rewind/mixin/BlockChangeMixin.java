package io.github.rewind.mixin;

import io.github.rewind.core.TickRecorder;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to capture block state changes via World.setBlockState().
 */
@Mixin(World.class)
public abstract class BlockChangeMixin {

    @Shadow
    public abstract boolean isClient();
    
    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);
    
    @Shadow
    @Nullable
    public abstract BlockEntity getBlockEntity(BlockPos pos);

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD")
    )
    private void rewind$captureBlockChange(
            BlockPos pos,
            BlockState newState,
            int flags,
            int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (isClient()) {
            return;
        }
        
        BlockState oldState = getBlockState(pos);
        if (oldState.equals(newState)) {
            return;
        }
        
        BlockEntity oldBlockEntity = getBlockEntity(pos);
        TickRecorder.recordBlockChange((World)(Object)this, pos.toImmutable(), oldState, newState, oldBlockEntity, null);
    }
}
