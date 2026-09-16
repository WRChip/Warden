package com.warden.mixin;

import com.warden.WardenMod;
import com.warden.config.WardenConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts LivingEntity#addStatusEffect at HEAD so effects are capped or blocked
 * the moment they are applied (potions, beacons, commands, etc.).
 * A ThreadLocal re-entry guard prevents infinite recursion when we re-apply a capped effect.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);

    @Inject(
        method = "addStatusEffect(Lnet/minecraft/entity/effect/StatusEffectInstance;Lnet/minecraft/entity/Entity;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void warden$interceptEffect(StatusEffectInstance effect, @Nullable Entity source,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (APPLYING.get()) return;
        if (!((Object) this instanceof ServerPlayerEntity player)) return;
        if (!WardenMod.CONFIG.effectLimitsEnabled) return;
        if (WardenMod.isExempt(player)) return;

        RegistryEntry<StatusEffect> effectType = effect.getEffectType();
        String effectId = effectType.getKey().map(k -> k.getValue().toString()).orElse(null);
        if (effectId == null) return;

        WardenConfig.EffectLimitConfig limit = WardenMod.CONFIG.effectLimits.get(effectId);
        if (limit == null) return;

        int amplifier = effect.getAmplifier();
        int duration  = effect.getDuration();

        if (limit.maxDuration == 0 || limit.maxLevel == 0) {
            cir.setReturnValue(false);
            WardenMod.sendNotice(player, WardenMod.NoticeCategory.EFFECT,
                    WardenMod.shortId(effectId) + " blocked");
            return;
        }

        int newAmplifier = (limit.maxLevel > 0 && amplifier + 1 > limit.maxLevel) ? limit.maxLevel - 1 : amplifier;
        int newDuration = duration;
        if (!WardenMod.shouldSkipDurationCap(effect) && limit.maxDuration > 0 && (duration == -1 || duration > limit.maxDuration)) {
            newDuration = limit.maxDuration;
        }

        if (newAmplifier != amplifier || newDuration != duration) {
            APPLYING.set(true);
            try {
                // callers like /effect count this as a success, so return what the capped apply returned
                cir.setReturnValue(player.addStatusEffect(new StatusEffectInstance(effectType, newDuration, newAmplifier,
                        effect.isAmbient(), effect.shouldShowParticles(), effect.shouldShowIcon()), source));
                WardenMod.sendNotice(player, WardenMod.NoticeCategory.EFFECT,
                        WardenMod.shortId(effectId) + " capped");
            } finally {
                APPLYING.set(false);
            }
        }
    }
}
