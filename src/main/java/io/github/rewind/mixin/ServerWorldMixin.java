package io.github.rewind.mixin;

import io.github.rewind.core.TickRecorder;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Mixin to hook into ServerWorld tick for entity tracking.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    /**
     * Hook at the start of world tick to snapshot entity states.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void rewind$onTickStart(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        ServerWorld world = (ServerWorld) (Object) this;
        TickRecorder.beginTickEntityTracking(world);
    }

    /**
     * Hook at the end of world tick to detect entity changes.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void rewind$onTickEnd(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        ServerWorld world = (ServerWorld) (Object) this;
        TickRecorder.endTickEntityTracking(world);
    }
}
