package com.warden.net;

import com.google.gson.JsonObject;
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

    public static ConfigUpdatePayload between(JsonObject original, JsonObject draft) {
        JsonObject changes = new JsonObject();
        for (var entry : draft.entrySet()) {
            if (!entry.getKey().equals("my_disabled_notices")
                    && !entry.getValue().equals(original.get(entry.getKey()))) {
                changes.add(entry.getKey(), entry.getValue());
            }
        }
        return new ConfigUpdatePayload(changes.toString());
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
