package com.warden.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.warden.WardenMod;

import java.util.LinkedHashSet;
import java.util.Set;

/** What the server last told us. Cleared on disconnect. */
public final class WardenClientState {

    public static JsonObject serverConfig;
    public static boolean canEdit;
    public static final Set<String> disabledNotices = new LinkedHashSet<>();

    private WardenClientState() {
    }

    public static boolean connected() {
        return serverConfig != null;
    }

    public static void apply(String json, boolean editable) {
        try {
            serverConfig = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            WardenMod.LOGGER.warn("[Warden] bad config sync from server: {}", e.getMessage());
            return;
        }
        canEdit = editable;
        disabledNotices.clear();
        if (serverConfig.has("my_disabled_notices")) {
            for (JsonElement el : serverConfig.getAsJsonArray("my_disabled_notices")) {
                disabledNotices.add(el.getAsString());
            }
        }
    }

    public static void clear() {
        serverConfig = null;
        canEdit = false;
        disabledNotices.clear();
    }
}
