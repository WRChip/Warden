package com.warden;

import com.warden.client.WardenClientConfig;
import com.warden.client.WardenClientState;
import com.warden.client.MissingYaclScreen;
import com.warden.client.WardenConfigScreen;
import com.warden.net.ConfigSyncPayload;
import com.warden.net.WeaponLimitsSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

public class WardenClient implements ClientModInitializer {

    public static WardenClientConfig CONFIG;
    public static KeyBinding OPEN_CONFIG;

    // the screen is optional so servers don't have to ship YACL just for the client UI
    public static boolean hasYacl() {
        return FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3");
    }

    @Override
    public void onInitializeClient() {
        CONFIG = WardenClientConfig.load();

        ClientPlayNetworking.registerGlobalReceiver(WeaponLimitsSyncPayload.ID, (payload, context) ->
                context.client().execute(() -> WardenMod.applySyncedWeaponLimits(payload.json())));
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.ID, (payload, context) ->
                context.client().execute(() -> WardenClientState.apply(payload.json(), payload.canEdit())));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WardenMod.clearSyncedWeaponLimits();
            WardenClientState.clear();
        });

        OPEN_CONFIG = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.warden.open_config", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_CONFIG.wasPressed()) {
                if (hasYacl()) {
                    client.setScreen(WardenConfigScreen.create(client.currentScreen));
                } else {
                    client.setScreen(new MissingYaclScreen(client.currentScreen));
                }
            }
        });
    }
}
