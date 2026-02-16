package io.github.rewind.mixin;

import io.github.rewind.client.PreviewRenderer;
import io.github.rewind.network.PreviewPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.client.render.state.WorldRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-only: after rendering the target block outline, draw the rewind preview overlay
 * (transparent red/green block outlines for would-be removed/added blocks).
 */
@Environment(EnvType.CLIENT)
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    @Shadow
    @Final
    private ClientWorld world;

    @Shadow
    @Final
    private MinecraftClient client;

    @Inject(
            method = "renderTargetBlockOutline",
            at = @At("TAIL")
    )
    private void rewind$renderPreviewOverlay(
            VertexConsumerProvider.Immediate immediate,
            MatrixStack matrices,
            boolean renderBlockOutline,
            WorldRenderState renderStates,
            CallbackInfo ci
    ) {
        PreviewPayload payload = PreviewRenderer.getCurrentPreview();
        if (payload == null || payload.blocks().isEmpty() || world == null) {
            return;
        }
        String dimension = world.getRegistryKey().getValue().toString();
        var camera = client.gameRenderer.getCamera();
        var camPos = camera.getCameraPos();
        double camX = camPos.x;
        double camY = camPos.y;
        double camZ = camPos.z;

        VertexConsumer buffer = immediate.getBuffer(RenderLayers.lines());
        float lineWidth = 2.0f;

        for (PreviewPayload.BlockEntry entry : payload.blocks()) {
            if (!entry.dimension().equals(dimension)) {
                continue;
            }
            BlockPos pos = BlockPos.fromLong(entry.packedPos());
            int color = colorForKind(entry.kind());
            matrices.push();
            matrices.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);
            VertexRendering.drawOutline(matrices, buffer, VoxelShapes.fullCube(), 0, 0, 0, color, lineWidth);
            matrices.pop();
        }
    }

    private static int colorForKind(byte kind) {
        // ARGB, semi-transparent
        return switch (kind) {
            case PreviewPayload.BlockEntry.KIND_PLACE -> 0x8000FF00;  // green
            case PreviewPayload.BlockEntry.KIND_REMOVE -> 0x80FF0000; // red
            case PreviewPayload.BlockEntry.KIND_CHANGE -> 0x80FFFF00; // yellow
            default -> 0x80FFFFFF;
        };
    }
}
