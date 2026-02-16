package io.github.rewind;

import io.github.rewind.command.TimelineCommand;
import io.github.rewind.core.TickRecorder;
import io.github.rewind.core.TimelineManager;
import io.github.rewind.network.PreviewPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod initializer for the Rewind mod.
 * Sets up event handlers and initializes the timeline system.
 */
public class RewindMod implements ModInitializer {
    public static final String MOD_ID = "rewind";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Rewind mod...");

        // Register preview payload for server -> client
        PayloadTypeRegistry.playS2C().register(PreviewPayload.ID, PreviewPayload.PACKET_CODEC);
        
        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            TimelineCommand.register(dispatcher);
        });
        
        // Initialize TimelineManager when server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            TimelineManager.initialize(server);
            LOGGER.info("Timeline system initialized for server");
        });
        
        // Cleanup when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TimelineManager.shutdown();
            TickRecorder.clearTrackingData();
            LOGGER.info("Timeline system shut down");
        });
        
        // Hook into server tick for frame management
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            TimelineManager manager = TimelineManager.getInstance();
            if (manager != null) {
                // Use overworld time as the canonical game time
                long gameTime = server.getOverworld().getTime();
                manager.beginTick(gameTime);
            }
        });
        
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TimelineManager manager = TimelineManager.getInstance();
            if (manager != null) {
                manager.endTick();
            }
        });
        
        // Track entity unloading via Fabric event
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            TickRecorder.recordEntityRemoval(entity);
        });
        
        LOGGER.info("Rewind mod initialized successfully!");
    }
}
