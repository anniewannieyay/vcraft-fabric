package dev.vcraft.relay;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Signs VCraft releases for auto-update.
 *   keygen <key.json>                          create a signing key (keep the file private and backed up)
 *   sign <key.json> <mod.jar> <version> <dir>  write <dir>/update.json and copy the jar next to it
 * Run with: java -cp vcraft-relay.jar dev.vcraft.relay.ReleaseTool ...
 */
public final class ReleaseTool {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("keygen")) {
            keygen(Path.of(args[1]));
        } else if (args.length == 5 && args[0].equals("sign")) {
            sign(Path.of(args[1]), Path.of(args[2]), args[3], Path.of(args[4]));
        } else {
            System.out.println("Usage:\n  keygen <key.json>\n  sign <key.json> <mod.jar> <version> <output dir>");
        }
    }

    private static void keygen(Path file) throws Exception {
        if (Files.exists(file)) throw new IllegalStateException(file + " already exists; refusing to overwrite a signing key");
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JsonObject o = new JsonObject();
        o.addProperty("public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        o.addProperty("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(o));
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
        }
        System.out.println("Public key (goes in the mod): " + o.get("public").getAsString());
    }

    /** The exact bytes that get signed; the mod rebuilds the same string to verify. */
    public static String signedText(String version, String sha256, long size) {
        return "vcraft-update\n" + version + "\n" + sha256 + "\n" + size;
    }

    private static void sign(Path keyFile, Path jar, String version, Path outDir) throws Exception {
        JsonObject key = JsonParser.parseString(Files.readString(keyFile)).getAsJsonObject();
        PrivateKey priv = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.get("private").getAsString())));
        byte[] bytes = Files.readAllBytes(jar);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(priv);
        sig.update(signedText(version, sha256, bytes.length).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String fileName = "vcraft-" + version + ".jar";
        Files.createDirectories(outDir);
        Files.copy(jar, outDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("version", version);
        manifest.addProperty("file", fileName);
        manifest.addProperty("sha256", sha256);
        manifest.addProperty("size", bytes.length);
        manifest.addProperty("signature", Base64.getEncoder().encodeToString(sig.sign()));
        Files.writeString(outDir.resolve("update.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
        System.out.println("Signed " + fileName + " (" + bytes.length + " bytes, sha256 " + sha256.substring(0, 12) + "...)");
    }

    private ReleaseTool() {
    }
}
