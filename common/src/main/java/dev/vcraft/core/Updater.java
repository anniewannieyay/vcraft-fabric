package dev.vcraft.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Runs as its own short-lived process after Minecraft exits: waits for the game to close,
 * then swaps the old mod jar for the downloaded one. Must only use the JDK, because it runs
 * from a copy of this class outside the mod jar (the jar itself is the thing being replaced).
 *
 * Args: <game pid> <old jar> <new jar> <target jar>
 */
public final class Updater {
    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path oldJar = Path.of(args[1]);
        Path newJar = Path.of(args[2]);
        Path target = Path.of(args[3]);
        log("Waiting for Minecraft (pid " + pid + ") to exit");
        ProcessHandle.of(pid).ifPresent(p -> {
            try {
                p.onExit().get(10, TimeUnit.MINUTES);
            } catch (Exception ignored) {
            }
        });
        // Windows can hold the file lock briefly after exit, so retry for a while.
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                if (!oldJar.equals(target)) Files.deleteIfExists(oldJar);
                Files.move(newJar, target, StandardCopyOption.REPLACE_EXISTING);
                log("Installed " + target.getFileName());
                return;
            } catch (Exception e) {
                if (attempt == 59) log("Giving up: " + e);
                Thread.sleep(500);
            }
        }
    }

    private static void log(String s) {
        System.out.println(Instant.now() + " " + s);
    }

    private Updater() {
    }
}
