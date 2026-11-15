package dev.vcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Downloads newer VCraft releases from the relay host and installs them when the game closes.
 * A release is only accepted if it's signed with the VCraft release key, so a compromised relay
 * or web server can't push code to players.
 */
public final class AutoUpdate {
    /** Public half of the release signing key (the private half never leaves the developer's machine). */
    static final String RELEASE_KEY = "MCowBQYDK2VwAyEAVXWOjP+2UA3FKowK/aPFnah6cv0N7oqukqfY69TXUlg=";
    private static final long MAX_SIZE = 32L * 1024 * 1024;

    public enum Status { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, FAILED, UNAVAILABLE }

    public static volatile Status status = Status.IDLE;
    public static volatile String detail;
    private static volatile Path staged;
    private static volatile String stagedVersion;
    private static volatile Path ownJar;
    private static boolean checkedThisSession;

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(VCraft.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    /** Called once the relay connection is up. */
    public static synchronized void checkOnce() {
        if (checkedThisSession || !VCraft.config().autoUpdate) return;
        checkedThisSession = true;
        check();
    }

    public static void check() {
        Path jar = findOwnJar();
        if (jar == null) {
            status = Status.UNAVAILABLE;
            detail = "Auto-update only works for the installed mod jar.";
            return;
        }
        ownJar = jar;
        URI manifest = manifestUri(VCraft.config().serverUrl);
        if (manifest == null) {
            status = Status.UNAVAILABLE;
            detail = "This relay doesn't offer updates.";
            return;
        }
        Path updateDir = VCraft.platform().configDir().resolve("vcraft").resolve("update");
        status = Status.CHECKING;
        CompletableFuture.runAsync(() -> {
            try {
                Result r = checkAndDownload(manifest, currentVersion(), updateDir);
                if (r == null) {
                    status = Status.UP_TO_DATE;
                    detail = "VCraft " + currentVersion() + " is up to date.";
                    return;
                }
                staged = r.jar();
                stagedVersion = r.version();
                status = Status.READY;
                detail = "VCraft " + r.version() + " downloaded. It installs when you close Minecraft.";
                VCraft.platform().runOnClient(() -> VCraft.platform().toast("VCraft update ready",
                        "Version " + r.version() + " installs when you close Minecraft."));
            } catch (Exception e) {
                status = Status.FAILED;
                detail = "Update check failed: " + e.getMessage();
                VCraft.LOGGER.warn("VCraft update check failed", e);
            }
        });
    }

    public record Result(String version, Path jar) {}

    /** Returns the verified, downloaded jar if a newer signed release exists, otherwise null. */
    public static Result checkAndDownload(URI manifestUri, String current, Path updateDir) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(manifestUri).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return null;
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
        JsonObject m = JsonParser.parseString(res.body()).getAsJsonObject();
        String version = m.get("version").getAsString();
        String sha256 = m.get("sha256").getAsString().toLowerCase();
        long size = m.get("size").getAsLong();
        String file = m.get("file").getAsString();
        if (!verify(signedText(version, sha256, size), m.get("signature").getAsString())) {
            throw new SecurityException("update signature is invalid");
        }
        if (compareVersions(version, current) <= 0) return null;
        if (size <= 0 || size > MAX_SIZE || !file.matches("[A-Za-z0-9._+-]+\\.jar")) throw new SecurityException("bad update metadata");

        Files.createDirectories(updateDir);
        Path target = updateDir.resolve("vcraft-" + version + ".jar");
        if (Files.exists(target) && sha256(target).equals(sha256)) return new Result(version, target);

        Path part = updateDir.resolve(target.getFileName() + ".part");
        URI jarUri = manifestUri.resolve(file);
        status = Status.DOWNLOADING;
        detail = "Downloading VCraft " + version + "...";
        HttpResponse<InputStream> dl = http.send(HttpRequest.newBuilder(jarUri).timeout(Duration.ofMinutes(5)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (dl.statusCode() != 200) throw new IllegalStateException("download HTTP " + dl.statusCode());
        try (InputStream in = dl.body()) {
            byte[] data = in.readNBytes((int) MAX_SIZE + 1);
            if (data.length != size) throw new SecurityException("download size mismatch");
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(sha256)) {
                throw new SecurityException("download checksum mismatch");
            }
            Files.write(part, data);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return new Result(version, target);
    }

    static String signedText(String version, String sha256, long size) {
        return "vcraft-update\n" + version + "\n" + sha256 + "\n" + size;
    }

    static boolean verify(String text, String signatureB64) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(RELEASE_KEY)));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(text.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            return false;
        }
    }

    /** wss://host[:port] -> https://host[:port]/vcraft/update.json. Plain ws:// relays (local testing) get no updates. */
    static URI manifestUri(String relayUrl) {
        try {
            URI u = URI.create(relayUrl.trim());
            if (!"wss".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) return null;
            return new URI("https", null, u.getHost(), u.getPort(), "/vcraft/update.json", null, null);
        } catch (Exception e) {
            return null;
        }
    }

    public static int compareVersions(String a, String b) {
        String[] pa = a.split("[^0-9]+"), pb = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : 0;
            long y = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    private static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    private static Path findOwnJar() {
        return FabricLoader.getInstance().getModContainer(VCraft.MOD_ID).map(c -> {
            List<Path> paths = c.getOrigin().getPaths();
            if (paths.size() != 1) return null;
            Path p = paths.get(0);
            return Files.isRegularFile(p) && p.toString().endsWith(".jar") ? p : null;
        }).orElse(null);
    }

    /** Called as the game shuts down: hands the swap to a helper process that outlives the game. */
    public static void installOnExit() {
        Path newJar = staged;
        Path oldJar = ownJar;
        if (newJar == null || oldJar == null || !Files.exists(newJar)) return;
        try {
            Path helperDir = Files.createTempDirectory("vcraft-updater");
            Path classFile = helperDir.resolve("dev/vcraft/core/Updater.class");
            Files.createDirectories(classFile.getParent());
            try (InputStream in = AutoUpdate.class.getResourceAsStream("/dev/vcraft/core/Updater.class")) {
                Files.copy(in, classFile);
            }
            String java = ProcessHandle.current().info().command().orElse("java");
            Path target = oldJar.resolveSibling("vcraft-" + stagedVersion + ".jar");
            Path log = newJar.resolveSibling("updater.log");
            new ProcessBuilder(java, "-cp", helperDir.toString(), "dev.vcraft.core.Updater",
                    String.valueOf(ProcessHandle.current().pid()), oldJar.toString(), newJar.toString(), target.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start();
            VCraft.LOGGER.info("VCraft {} will be installed after Minecraft exits", stagedVersion);
        } catch (Exception e) {
            VCraft.LOGGER.warn("Couldn't start the VCraft updater; the new jar is at {}", newJar, e);
        }
    }

    private AutoUpdate() {
    }
}
