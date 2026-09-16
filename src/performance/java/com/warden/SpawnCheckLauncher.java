package com.warden;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

import java.nio.file.Path;

public final class SpawnCheckLauncher {
    public static void main(String[] args) throws Exception {
        Knot knot = new Knot(EnvType.SERVER);
        ClassLoader loader = knot.init(new String[]{"--gameDir", Path.of(".").toAbsolutePath().toString()});
        knot.addToClassPath(Path.of(SpawnCheckLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        Class.forName("com.warden.SpawnCheck", true, loader).getMethod("main", String[].class)
                .invoke(null, (Object) args);
    }
}
