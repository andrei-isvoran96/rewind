package io.github.rewind.network;

import io.github.rewind.core.RewindExecutor;
import io.github.rewind.core.RewindPlan;
import io.github.rewind.core.TimelineManager;
import io.github.rewind.data.TickFrame;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds preview payload from rewind plan (with caps) and sends to the client.
 */
public final class PreviewSender {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rewind");

    private PreviewSender() {}

    /**
     * Build a preview payload from the rewind plan for the given seconds, with caps and optional radius filter.
     * Sends only to the given player. Requires server to resolve the player's world.
     */
    public static boolean sendPreviewToPlayer(ServerPlayerEntity player, MinecraftServer server, int seconds) {
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null || server == null) return false;

        int ticks = seconds * TimelineManager.TICKS_PER_SECOND;
        List<TickFrame> frames = manager.getFramesForRewind(ticks);
        if (frames.isEmpty()) return false;

        RewindPlan plan = RewindExecutor.buildPlan(frames);
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        ServerWorld world = null;
        for (ServerWorld w : server.getWorlds()) {
            if (w.getEntity(player.getUuid()) != null) {
                world = w;
                break;
            }
        }
        if (world == null) return false;

        List<PreviewPayload.BlockEntry> blockEntries = new ArrayList<>();
        for (Map.Entry<RewindPlan.BlockKey, net.minecraft.block.BlockState> e : plan.blockTargetStates().entrySet()) {
            if (blockEntries.size() >= PreviewPayload.MAX_BLOCKS) break;

            RewindPlan.BlockKey key = e.getKey();
            ServerWorld dim = world.getServer().getWorld(key.dimension());
            if (dim == null) continue;

            BlockPos pos = BlockPos.fromLong(key.packedPos());
            double distSq = playerPos.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distSq > PreviewPayload.MAX_RADIUS_SQ) continue;

            BlockState current = dim.getBlockState(pos);
            BlockState target = e.getValue();
            byte kind;
            if (current.isAir() && !target.isAir()) kind = PreviewPayload.BlockEntry.KIND_PLACE;
            else if (!current.isAir() && target.isAir()) kind = PreviewPayload.BlockEntry.KIND_REMOVE;
            else kind = PreviewPayload.BlockEntry.KIND_CHANGE;

            blockEntries.add(new PreviewPayload.BlockEntry(
                    key.dimension().getValue().toString(),
                    key.packedPos(),
                    kind
            ));
        }

        List<PreviewPayload.EntityEntry> entityEntries = new ArrayList<>();
        for (UUID uuid : plan.entitiesToRemove()) {
            if (entityEntries.size() >= PreviewPayload.MAX_ENTITY_OPS) break;
            entityEntries.add(new PreviewPayload.EntityEntry(
                    world.getRegistryKey().getValue().toString(),
                    0, 0, 0,
                    PreviewPayload.EntityEntry.KIND_REMOVE,
                    ""
            ));
        }
        for (Map.Entry<UUID, RewindPlan.EntitySpawnInfo> e : plan.entitiesToRespawn().entrySet()) {
            if (entityEntries.size() >= PreviewPayload.MAX_ENTITY_OPS) break;
            RewindPlan.EntitySpawnInfo info = e.getValue();
            double x = info.state().getDouble("X").orElse(0.0);
            double y = info.state().getDouble("Y").orElse(64.0);
            double z = info.state().getDouble("Z").orElse(0.0);
            entityEntries.add(new PreviewPayload.EntityEntry(
                    info.dimension().getValue().toString(),
                    x, y, z,
                    PreviewPayload.EntityEntry.KIND_RESPAWN,
                    info.entityType() != null ? info.entityType().toString() : ""
            ));
        }
        for (Map.Entry<UUID, net.minecraft.nbt.NbtCompound> e : plan.entityTargetStates().entrySet()) {
            if (entityEntries.size() >= PreviewPayload.MAX_ENTITY_OPS) break;
            if (plan.entitiesToRemove().contains(e.getKey())) continue;
            net.minecraft.nbt.NbtCompound state = e.getValue();
            double x = state.getDouble("X").orElse(0.0);
            double y = state.getDouble("Y").orElse(64.0);
            double z = state.getDouble("Z").orElse(0.0);
            entityEntries.add(new PreviewPayload.EntityEntry(
                    world.getRegistryKey().getValue().toString(),
                    x, y, z,
                    PreviewPayload.EntityEntry.KIND_UPDATE,
                    ""
            ));
        }

        PreviewPayload payload = new PreviewPayload(blockEntries, entityEntries);
        if (ServerPlayNetworking.canSend(player, PreviewPayload.ID)) {
            ServerPlayNetworking.send(player, payload);
            LOGGER.debug("Sent preview to {}: {} blocks, {} entities", player.getName().getString(), blockEntries.size(), entityEntries.size());
            return true;
        }
        return false;
    }

    /**
     * Send clear preview to the player (empty payload).
     */
    public static void sendClearToPlayer(ServerPlayerEntity player) {
        if (ServerPlayNetworking.canSend(player, PreviewPayload.ID)) {
            ServerPlayNetworking.send(player, PreviewPayload.clear());
        }
    }
}
