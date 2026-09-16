package com.warden.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: comma separated notice categories this player wants muted. */
public record NoticePrefsPayload(String disabled) implements CustomPayload {

    public static final CustomPayload.Id<NoticePrefsPayload> ID =
            new CustomPayload.Id<>(Identifier.of("warden", "notice_prefs"));

    public static final PacketCodec<RegistryByteBuf, NoticePrefsPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.STRING, NoticePrefsPayload::disabled, NoticePrefsPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
