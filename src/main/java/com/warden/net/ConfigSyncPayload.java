package com.warden.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: the limits as JSON for the config screen, and whether this player may edit them. */
public record ConfigSyncPayload(String json, boolean canEdit) implements CustomPayload {

    public static final CustomPayload.Id<ConfigSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of("warden", "config_sync"));

    public static final PacketCodec<RegistryByteBuf, ConfigSyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ConfigSyncPayload::json,
            PacketCodecs.BOOLEAN, ConfigSyncPayload::canEdit,
            ConfigSyncPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
