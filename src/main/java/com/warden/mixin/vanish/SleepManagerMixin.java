package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.SleepManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

// a vanished player must not hold the night open or show up in the "x/y sleeping" count
@Mixin(SleepManager.class)
public abstract class SleepManagerMixin {

    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayerEntity> warden$ignoreVanished(List<ServerPlayerEntity> players) {
        if (!Vanish.anyVanished()) {
            return players;
        }
        return players.stream().filter(player -> !Vanish.isVanished(player)).toList();
    }
}
