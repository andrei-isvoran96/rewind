package io.github.rewind.network;

import io.github.rewind.RewindMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializable preview data sent server -> client for the rewind diff overlay.
 * Compact format: dimension + pos (long) + kind for blocks; dimension + pos + kind + entity type for entities.
 */
public record PreviewPayload(
        List<BlockEntry> blocks,
        List<EntityEntry> entities
) implements CustomPayload {
    public static final CustomPayload.Id<PreviewPayload> ID = new CustomPayload.Id<>(Identifier.of(RewindMod.MOD_ID, "preview"));
    public static final int MAX_BLOCKS = 2000;
    public static final int MAX_ENTITY_OPS = 500;
    public static final int MAX_RADIUS_SQ = 64 * 64;

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record BlockEntry(
            String dimension,
            long packedPos,
            byte kind  // 0 = would be placed, 1 = would be removed, 2 = would change state
    ) {
        public static final byte KIND_PLACE = 0;
        public static final byte KIND_REMOVE = 1;
        public static final byte KIND_CHANGE = 2;
    }

    public record EntityEntry(
            String dimension,
            double x, double y, double z,
            byte kind,  // 0 = would be removed, 1 = would be respawned, 2 = would move/update
            String entityType
    ) {
        public static final byte KIND_REMOVE = 0;
        public static final byte KIND_RESPAWN = 1;
        public static final byte KIND_UPDATE = 2;
    }

    public static final PacketCodec<RegistryByteBuf, PreviewPayload> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.collection(ArrayList::new, PacketCodec.tuple(
                    PacketCodecs.STRING,
                    BlockEntry::dimension,
                    PacketCodecs.LONG,
                    BlockEntry::packedPos,
                    PacketCodecs.BYTE,
                    BlockEntry::kind,
                    BlockEntry::new
            )),
            PreviewPayload::blocks,
            PacketCodecs.collection(ArrayList::new, PacketCodec.tuple(
                    PacketCodecs.STRING,
                    EntityEntry::dimension,
                    PacketCodecs.DOUBLE,
                    EntityEntry::x,
                    PacketCodecs.DOUBLE,
                    EntityEntry::y,
                    PacketCodecs.DOUBLE,
                    EntityEntry::z,
                    PacketCodecs.BYTE,
                    EntityEntry::kind,
                    PacketCodecs.STRING,
                    EntityEntry::entityType,
                    EntityEntry::new
            )),
            PreviewPayload::entities,
            PreviewPayload::new
    );

    public static PreviewPayload clear() {
        return new PreviewPayload(List.of(), List.of());
    }
}
