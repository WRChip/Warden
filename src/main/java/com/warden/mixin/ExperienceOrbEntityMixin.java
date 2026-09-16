package com.warden.mixin;

import com.warden.WardenMod;
import com.warden.xp.ExperienceOrbEntityAccessor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Mixin(ExperienceOrbEntity.class)
public abstract class ExperienceOrbEntityMixin implements ExperienceOrbEntityAccessor {

    @Unique
    private String warden$source = "unknown";
    @Unique
    private String warden$context = "";

    @Override
    public void warden$setXpSource(String source) {
        this.warden$source = source;
    }

    @Override
    public void warden$setXpContext(String context) {
        this.warden$context = context;
    }

    @Override
    public String warden$getXpSource() {
        return warden$source;
    }

    @Override
    public String warden$getXpContext() {
        return warden$context;
    }

    // spawn() splits the amount into several orbs, so the cap has to apply to the total here;
    // capping per orb at pickup would let a furnace with 100 stored xp pay out well over the limit
    @ModifyVariable(method = "spawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;I)V",
            at = @At("HEAD"), argsOnly = true)
    private static int warden$capSpawnedXp(int amount) {
        return WardenMod.limitExperienceGain(WardenMod.XP_PLAYER.get(), amount);
    }

    // a freshly spawned orb may be folded into a nearby one of the same value; only allow that
    // when the existing orb came from the same source, otherwise it inherits the wrong cap
    @Redirect(method = "wasMergedIntoExistingOrb", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;getEntitiesByType(Lnet/minecraft/util/TypeFilter;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<ExperienceOrbEntity> warden$onlyMergeSameSource(ServerWorld world, TypeFilter<Entity, ExperienceOrbEntity> filter,
                                                                         Box box, Predicate<? super ExperienceOrbEntity> predicate) {
        String source = WardenMod.XP_SOURCE.get();
        String context = WardenMod.XP_CONTEXT.get();
        List<ExperienceOrbEntity> matching = new ArrayList<>();
        for (ExperienceOrbEntity orb : world.getEntitiesByType(filter, box, predicate)) {
            ExperienceOrbEntityAccessor tagged = (ExperienceOrbEntityAccessor) orb;
            if (source.equals(tagged.warden$getXpSource()) && context.equals(tagged.warden$getXpContext())) {
                matching.add(orb);
            }
        }
        return matching;
    }

    @Inject(method = "isMergeable(Lnet/minecraft/entity/ExperienceOrbEntity;)Z", at = @At("RETURN"), cancellable = true)
    private void warden$dontMergeAcrossSources(ExperienceOrbEntity other, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        ExperienceOrbEntityAccessor tagged = (ExperienceOrbEntityAccessor) other;
        if (!warden$source.equals(tagged.warden$getXpSource()) || !warden$context.equals(tagged.warden$getXpContext())) {
            cir.setReturnValue(false);
        }
    }

    // the (World,DDDI) constructor and ExperienceOrbEntity.spawn both route through this one
    @Inject(method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;I)V", at = @At("TAIL"))
    private void warden$tagOrbOnCreation(net.minecraft.world.World world, net.minecraft.util.math.Vec3d pos, net.minecraft.util.math.Vec3d velocity, int amount, CallbackInfo ci) {
        this.warden$source = WardenMod.XP_SOURCE.get();
        this.warden$context = WardenMod.XP_CONTEXT.get();
    }

    @Inject(method = "onPlayerCollision", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;addExperience(I)V"))
    private void warden$setXpSourceBeforeAdding(PlayerEntity player, CallbackInfo ci) {
        WardenMod.XP_SOURCE_OVERRIDE.set(warden$source);
        WardenMod.XP_CONTEXT_OVERRIDE.set(warden$context);
    }

    @Inject(method = "onPlayerCollision", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;addExperience(I)V", shift = At.Shift.AFTER))
    private void warden$clearXpSourceAfterAdding(PlayerEntity player, CallbackInfo ci) {
        WardenMod.XP_SOURCE_OVERRIDE.set(null);
        WardenMod.XP_CONTEXT_OVERRIDE.set(null);
    }
}
