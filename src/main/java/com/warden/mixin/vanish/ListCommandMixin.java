package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ListCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.function.Function;

@Mixin(ListCommand.class)
public abstract class ListCommandMixin {

    @SuppressWarnings("rawtypes")
    @Redirect(method = "execute",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;getPlayerList()Ljava/util/List;"))
    private static List<ServerPlayerEntity> warden$hideVanished(PlayerManager manager, ServerCommandSource source, Function formatter) {
        return manager.getPlayerList().stream().filter(player -> Vanish.canSee(source, player)).toList();
    }
}
