package com.warden.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.warden.WardenClient;

public class WardenModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return WardenClient.hasYacl() ? WardenConfigScreen::create : MissingYaclScreen::new;
    }
}
