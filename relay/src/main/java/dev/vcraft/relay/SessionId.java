package dev.vcraft.relay;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The Mojang session id a client joins to sign in to this relay:
 * Minecraft's hex digest of SHA-1 over {@code vcraft-relay-v1|<host>|<nonce>}.
 * Clients compute it themselves from the relay's host and the challenge's
 * nonce, so the relay can't choose it (and can't pass on the hash of a
 * handshake with a real online-mode server). See docs/relay-auth.md in
 * vcraft-horizon.
 */
final class SessionId {
    static final String PREFIX = "vcraft-relay-v1";

    static String of(String host, String nonce) {
        String text = PREFIX + "|" + host.trim().toLowerCase(Locale.ROOT) + "|" + nonce;
        return minecraftHex(sha1(text));
    }

    /** {@code new BigInteger(digest).toString(16)}: signed, no leading zeros. */
    static String minecraftHex(byte[] digest) {
        return new BigInteger(digest).toString(16);
    }

    static byte[] sha1(String text) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private SessionId() {
    }
}
