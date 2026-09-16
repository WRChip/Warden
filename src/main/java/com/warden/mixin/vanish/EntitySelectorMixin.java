package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.command.EntitySelector;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// keeps vanished players out of @a, @e, @p and anything built on them
@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {

    @Inject(method = "getPlayers", at = @At("RETURN"), cancellable = true)
    private void warden$filterPlayers(ServerCommandSource source, CallbackInfoReturnable<List<ServerPlayerEntity>> cir) {
        if (!Vanish.anyVanished()) {
            return;
        }
        cir.setReturnValue(cir.getReturnValue().stream().filter(player -> Vanish.canSee(source, player)).toList());
    }

    @Inject(method = "getEntities(Lnet/minecraft/server/command/ServerCommandSource;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private void warden$filterEntities(ServerCommandSource source, CallbackInfoReturnable<List<? extends Entity>> cir) {
        if (!Vanish.anyVanished()) {
            return;
        }
        List<Entity> visible = cir.getReturnValue().stream()
                .filter(entity -> Vanish.canSee(source, entity))
                .map(entity -> (Entity) entity)
                .toList();
        cir.setReturnValue(visible);
    }
}
