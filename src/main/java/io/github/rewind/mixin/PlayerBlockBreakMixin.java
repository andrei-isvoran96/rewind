package io.github.rewind.mixin;

import io.github.rewind.core.TickRecorder;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to capture player block breaking.
 */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class PlayerBlockBreakMixin {

    @Shadow
    protected ServerWorld world;

    @Inject(method = "tryBreakBlock", at = @At("HEAD"))
    private void rewind$capturePlayerBlockBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (world == null) {
            return;
        }
        
        BlockState oldState = world.getBlockState(pos);
        if (oldState.isAir()) {
            return;
        }
        
        BlockEntity oldBlockEntity = world.getBlockEntity(pos);
        BlockState newState = oldState.getFluidState().getBlockState();
        
        TickRecorder.recordBlockChange(world, pos.toImmutable(), oldState, newState, oldBlockEntity, null);
    }
}
