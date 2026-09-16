package com.warden.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record WeaponLimitsSyncPayload(String json) implements CustomPayload {

    public static final CustomPayload.Id<WeaponLimitsSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of("warden", "weapon_limits_sync"));

    public static final PacketCodec<RegistryByteBuf, WeaponLimitsSyncPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.string(1 << 20), WeaponLimitsSyncPayload::json, WeaponLimitsSyncPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
