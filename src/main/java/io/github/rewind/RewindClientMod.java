package io.github.rewind;

import io.github.rewind.network.PreviewPayload;
import io.github.rewind.client.PreviewRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side initializer: registers preview payload and receiver.
 * Overlay rendering is triggered from WorldRendererMixin (client-only).
 */
@Environment(EnvType.CLIENT)
public class RewindClientMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(RewindMod.MOD_ID + "-client");

    @Override
    public void onInitializeClient() {
        // Payload type is already registered in RewindMod (main); registering again causes "already registered" in singleplayer
        ClientPlayNetworking.registerGlobalReceiver(PreviewPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                PreviewRenderer.setPreview(payload);
                LOGGER.debug("Received preview: {} blocks, {} entities", payload.blocks().size(), payload.entities().size());
            });
        });
        LOGGER.info("Rewind client initialized (preview overlay enabled)");
    }
}
