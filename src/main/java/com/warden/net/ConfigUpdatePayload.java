package com.warden.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: an admin saved edits in the config screen. Server re-checks permission. */
public record ConfigUpdatePayload(String json) implements CustomPayload {

    public static final CustomPayload.Id<ConfigUpdatePayload> ID =
            new CustomPayload.Id<>(Identifier.of("warden", "config_update"));

    public static final PacketCodec<RegistryByteBuf, ConfigUpdatePayload> CODEC =
            PacketCodec.tuple(PacketCodecs.STRING, ConfigUpdatePayload::json, ConfigUpdatePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
