package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every enchantment write (table, anvil, /enchant, loot) goes through EnchantmentHelper#set.
 * Capping here means the anvil preview already shows the capped result before the player pays.
 */
@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentHelperMixin {

    @ModifyVariable(method = "set", at = @At("HEAD"), argsOnly = true)
    private static ItemEnchantmentsComponent warden$capOnWrite(ItemEnchantmentsComponent enchants, ItemStack stack) {
        ItemEnchantmentsComponent capped = WardenMod.capEnchantments(stack, enchants);
        return capped != null ? capped : enchants;
    }
}
