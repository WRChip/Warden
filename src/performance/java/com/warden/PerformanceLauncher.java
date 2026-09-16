package com.warden;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PerformanceLauncher {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory(Path.of("build"), "performance-run-");
        Knot knot = new Knot(EnvType.SERVER);
        ClassLoader loader = knot.init(new String[]{"--gameDir", dir.toAbsolutePath().toString()});
        knot.addToClassPath(Path.of(PerformanceLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        Class.forName("com.warden.PerformanceCheck", true, loader).getMethod("main", String[].class)
                .invoke(null, (Object) args);
    }
}
