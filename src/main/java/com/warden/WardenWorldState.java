package com.warden;

import com.mojang.serialization.Codec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

final class WardenWorldState extends PersistentState {
    private static final PersistentStateType<WardenWorldState> TYPE = new PersistentStateType<>(
            "warden_world", () -> new WardenWorldState(true),
            Codec.BOOL.fieldOf("locked").xmap(WardenWorldState::new, state -> state.locked).codec(), null);

    private boolean locked;

    private WardenWorldState(boolean locked) {
        this.locked = locked;
    }

    static WardenWorldState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().get(TYPE);
    }

    static void setLocked(MinecraftServer server, boolean locked) {
        WardenWorldState state = server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
        state.locked = locked;
        state.markDirty();
    }

    boolean isLocked() {
        return locked;
    }
}
