package io.github.rewind.client;

import io.github.rewind.network.PreviewPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-only: holds current preview payload.
 * Preview expires after 10 seconds or when clear packet is received.
 * 3D overlay rendering is invoked from WorldRendererMixin when the payload is active.
 */
@Environment(EnvType.CLIENT)
public final class PreviewRenderer {
    private static final long PREVIEW_EXPIRY_MS = 10_000;

    private static volatile PreviewPayload currentPayload = null;
    private static volatile long receivedAtMs = 0;

    private PreviewRenderer() {}

    public static void setPreview(PreviewPayload payload) {
        currentPayload = payload;
        receivedAtMs = System.currentTimeMillis();
    }

    /** Returns the current preview payload, or null if none or expired. */
    public static PreviewPayload getCurrentPreview() {
        PreviewPayload payload = currentPayload;
        if (payload == null) return null;
        if (payload.blocks().isEmpty() && payload.entities().isEmpty()) {
            currentPayload = null;
            return null;
        }
        if (System.currentTimeMillis() - receivedAtMs > PREVIEW_EXPIRY_MS) {
            currentPayload = null;
            return null;
        }
        return payload;
    }

    /** Called from mixin to clear after render (optional). Currently we keep until expiry or clear packet. */
    public static void clearPreview() {
        currentPayload = null;
    }
}
