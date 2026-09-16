package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.component.ComponentChanges;
import net.minecraft.component.ComponentMap;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "<init>(Lnet/minecraft/item/ItemConvertible;I)V", at = @At("TAIL"))
    private void warden$afterInit(ItemConvertible item, int count, CallbackInfo ci) {
        WardenMod.enforceWeaponComponents((ItemStack) (Object) this);
    }

    @Inject(method = "<init>(Lnet/minecraft/registry/entry/RegistryEntry;ILnet/minecraft/component/ComponentChanges;)V", at = @At("TAIL"))
    private void warden$afterInit(RegistryEntry<?> item, int count, ComponentChanges changes, CallbackInfo ci) {
        WardenMod.enforceWeaponComponents((ItemStack) (Object) this);
    }

    @Inject(method = "applyChanges", at = @At("TAIL"))
    private void warden$afterApplyChanges(ComponentChanges changes, CallbackInfo ci) {
        WardenMod.enforceWeaponComponents((ItemStack) (Object) this);
    }

    @Inject(method = "applyUnvalidatedChanges", at = @At("TAIL"))
    private void warden$afterApplyUnvalidatedChanges(ComponentChanges changes, CallbackInfo ci) {
        WardenMod.enforceWeaponComponents((ItemStack) (Object) this);
    }

    @Inject(method = "applyComponentsFrom", at = @At("TAIL"))
    private void warden$afterApplyComponentsFrom(ComponentMap components, CallbackInfo ci) {
        WardenMod.enforceWeaponComponents((ItemStack) (Object) this);
    }
}
