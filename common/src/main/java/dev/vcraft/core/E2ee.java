package dev.vcraft.core;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** X25519 + HKDF-SHA256 pairwise keys, AES-256-GCM sealing. */
public final class E2ee {
    public static final int NONCE = 12;
    public static final int TAG = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<Cipher> GCM = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    });

    private final PrivateKey privateKey;
    private final byte[] publicKey;
    private final String publicKeyB64;
    private final Map<String, byte[]> pairwiseCache = new ConcurrentHashMap<>();

    public E2ee(KeyPair pair) {
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic().getEncoded();
        this.publicKeyB64 = Base64.getEncoder().encodeToString(publicKey);
    }

    public static KeyPair generate() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    public static KeyPair decode(String publicB64, String privateB64) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("X25519");
        PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)));
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateB64)));
        return new KeyPair(pub, priv);
    }

    public String publicKey() {
        return publicKeyB64;
    }

    // HKDF info uses both public keys in sorted order so each side derives the same key.
    public byte[] pairwiseKey(String theirPublicB64) throws GeneralSecurityException {
        byte[] cached = pairwiseCache.get(theirPublicB64);
        if (cached != null) return cached;
        byte[] theirs = Base64.getDecoder().decode(theirPublicB64);
        PublicKey pub = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(theirs));
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(privateKey);
        ka.doPhase(pub, true);
        byte[] shared = ka.generateSecret();
        byte[] info = concat("crosstalk-pairwise-v1".getBytes(StandardCharsets.UTF_8), sorted(publicKey, theirs));
        byte[] key = hkdf(shared, info);
        Arrays.fill(shared, (byte) 0);
        pairwiseCache.put(theirPublicB64, key);
        return key;
    }

    public String safetyNumber(String theirPublicB64) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sorted(publicKey, Base64.getDecoder().decode(theirPublicB64)));
            long n = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
            String digits = String.format("%015d", n % 1_000_000_000_000_000L);
            return digits.substring(0, 5) + " " + digits.substring(5, 10) + " " + digits.substring(10);
        } catch (Exception e) {
            return "?????";
        }
    }

    public static byte[] seal(byte[] key, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        byte[] ct = seal(key, nonce, plaintext, aad);
        return concat(nonce, ct);
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        Cipher c = GCM.get();
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
        c.updateAAD(aad);
        return c.doFinal(plaintext);
    }

    public static byte[] open(byte[] key, byte[] sealed, byte[] aad) throws GeneralSecurityException {
        if (sealed.length < NONCE + TAG) throw new GeneralSecurityException("too short");
        return open(key, Arrays.copyOf(sealed, NONCE), sealed, NONCE, sealed.length - NONCE, aad);
    }

    public static byte[] open(byte[] key, byte[] nonce, byte[] data, int offset, int length, byte[] aad) throws GeneralSecurityException {
        Cipher c = GCM.get();
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
        c.updateAAD(aad);
        return c.doFinal(data, offset, length);
    }

    public static byte[] randomKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return k;
    }

    private static byte[] hkdf(byte[] ikm, byte[] info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return mac.doFinal();
    }

    private static byte[] sorted(byte[] a, byte[] b) {
        return Arrays.compare(a, b) <= 0 ? concat(a, b) : concat(b, a);
    }

    public static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
