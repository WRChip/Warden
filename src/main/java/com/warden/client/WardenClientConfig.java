package com.warden.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.warden.WardenMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings that only matter on this client. Everything else lives in the server config. */
public class WardenClientConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("warden-client.json");

    public boolean blockSwingsOnCooldown = true;

    public static WardenClientConfig load() {
        if (!Files.exists(PATH)) {
            return new WardenClientConfig();
        }
        try (Reader reader = Files.newBufferedReader(PATH)) {
            WardenClientConfig cfg = GSON.fromJson(reader, WardenClientConfig.class);
            return cfg != null ? cfg : new WardenClientConfig();
        } catch (IOException | RuntimeException e) {
            WardenMod.LOGGER.error("[Warden] Failed to read client config, using defaults: {}", e.getMessage());
            return new WardenClientConfig();
        }
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(PATH)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            WardenMod.LOGGER.error("[Warden] Failed to save client config: {}", e.getMessage());
        }
    }
}
