package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * updateToClient pushes the full slot contents when a container is opened. Strip oversized stacks
 * first, so opening a chest packed with book-stuffed shulkers can't kick the opener before the
 * tick-based sweep gets to it.
 */
@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "updateToClient", at = @At("HEAD"))
    private void warden$purgeBeforeSync(CallbackInfo ci) {
        WardenMod.purgeOversized((ScreenHandler) (Object) this, "container sync");
    }
}
