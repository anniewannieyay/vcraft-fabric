package dev.vcraft.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Identity keys, key pinning, message sealing and per-sender voice keys (rotated whenever someone leaves a call). */
public final class Secure {
    public enum Trust { TRUSTED, CHANGED, NONE }

    public record Opened(String json, boolean ok) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PREFIX = "e2:";

    private static E2ee identity;
    private static Path trustFile;
    private static final Map<String, String> pinned = new ConcurrentHashMap<>();
    private static final Map<String, String> presented = new ConcurrentHashMap<>();

    private static final class Rx {
        final byte[] key;
        final int epoch;
        long lastCounter;

        Rx(byte[] key, int epoch) {
            this.key = key;
            this.epoch = epoch;
        }
    }

    public static synchronized void init(Path configDir, String account) throws Exception {
        Path dir = configDir.resolve("vcraft");
        Files.createDirectories(dir);
        String safe = account.replaceAll("[^A-Za-z0-9_-]", "_");
        Path idFile = dir.resolve("identity-" + safe + ".json");
        trustFile = dir.resolve("trust-" + safe + ".json");

        KeyPair pair;
        if (Files.exists(idFile)) {
            try (Reader r = Files.newBufferedReader(idFile)) {
                JsonObject o = GSON.fromJson(r, JsonObject.class);
                pair = E2ee.decode(o.get("public").getAsString(), o.get("private").getAsString());
            }
        } else {
            pair = E2ee.generate();
            JsonObject o = new JsonObject();
            o.addProperty("public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            o.addProperty("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            try (Writer w = Files.newBufferedWriter(idFile)) {
                GSON.toJson(o, w);
            }
            try {
                Files.setPosixFilePermissions(idFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
        }
        identity = new E2ee(pair);

        pinned.clear();
        presented.clear();
        if (Files.exists(trustFile)) {
            try (Reader r = Files.newBufferedReader(trustFile)) {
                Map<String, String> m = GSON.fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
                if (m != null) pinned.putAll(m);
            }
        }
    }

    public static String publicKey() {
        return identity == null ? null : identity.publicKey();
    }

    public static void observe(String uuid, String key) {
        if (uuid == null || key == null || key.isEmpty()) return;
        presented.put(uuid, key);
        if (pinned.putIfAbsent(uuid, key) == null) saveTrust();
    }

    public static Trust trust(String uuid) {
        String key = presented.get(uuid);
        if (key == null) return Trust.NONE;
        return key.equals(pinned.get(uuid)) ? Trust.TRUSTED : Trust.CHANGED;
    }

    public static void acceptPresentedKey(String uuid) {
        String key = presented.get(uuid);
        if (key == null) return;
        pinned.put(uuid, key);
        saveTrust();
        onCall(State.call);
        onRoom(State.room);
    }

    public static String safetyNumber(String uuid) {
        String key = presented.get(uuid);
        return key == null || identity == null ? null : identity.safetyNumber(key);
    }

    private static byte[] pairKey(String uuid) throws Exception {
        if (identity == null) throw new IllegalStateException("no identity");
        if (trust(uuid) != Trust.TRUSTED) throw new IllegalStateException("key not trusted");
        return identity.pairwiseKey(presented.get(uuid));
    }

    private static void saveTrust() {
        if (trustFile == null) return;
        try (Writer w = Files.newBufferedWriter(trustFile)) {
            GSON.toJson(new HashMap<>(pinned), w);
        } catch (Exception e) {
            VCraft.LOGGER.warn("Could not save VCraft trust store", e);
        }
    }

    // Message payloads are a JSON envelope (text, reply, server invite), so replies and invites are encrypted too.

    public static String seal(String recipient, String aad, String envelope) {
        try {
            byte[] sealed = E2ee.seal(pairKey(recipient), E2ee.utf8(envelope), E2ee.utf8(aad));
            return PREFIX + Base64.getEncoder().encodeToString(sealed);
        } catch (Exception e) {
            return null;
        }
    }

    public static Opened open(String peer, String aad, String wire) {
        if (wire == null || !wire.startsWith(PREFIX)) return new Opened(null, false);
        try {
            byte[] sealed = Base64.getDecoder().decode(wire.substring(PREFIX.length()));
            return new Opened(new String(E2ee.open(pairKey(peer), sealed, E2ee.utf8(aad)), StandardCharsets.UTF_8), true);
        } catch (Exception e) {
            return new Opened(null, false);
        }
    }

    public static String dmAad(String from, String to) {
        return "dm|" + from + "|" + to;
    }

    public static String activityAad(String from, String to) {
        return "act|" + from + "|" + to;
    }

    public static String groupAad(String group, String from, String to) {
        return "gm|" + group + "|" + from + "|" + to;
    }


    /**
     * One voice session: a call, or an addon's voice room. Each speaker has their own key, sent to every other
     * member sealed with the pairwise key, and rotated when membership changes.
     */
    private static final class Session {
        final String keyMessage;
        final String aadLabel;
        final boolean rotateOnJoin;
        String id;
        byte[] key;
        int epoch;
        final AtomicLong counter = new AtomicLong();
        final Set<String> sentTo = new HashSet<>();
        Set<String> lastMembers = new HashSet<>();
        final Map<String, Rx> rx = new ConcurrentHashMap<>();

        Session(String keyMessage, String aadLabel, boolean rotateOnJoin) {
            this.keyMessage = keyMessage;
            this.aadLabel = aadLabel;
            this.rotateOnJoin = rotateOnJoin;
        }

        synchronized void update(String newId, Set<String> members) {
            String me = State.myUuid;
            if (newId == null || me == null) {
                id = null;
                key = null;
                sentTo.clear();
                lastMembers = new HashSet<>();
                rx.clear();
                return;
            }
            members.remove(me);
            if (!newId.equals(id)) {
                id = newId;
                rx.clear();
                lastMembers = new HashSet<>();
                rotate();
            }
            boolean left = !members.containsAll(lastMembers);
            boolean joined = !lastMembers.containsAll(members);
            if (left || (joined && rotateOnJoin && !lastMembers.isEmpty())) {
                rotate();
                rx.keySet().retainAll(members);
            }
            lastMembers = members;
            for (String m : members) {
                if (sentTo.contains(m) || trust(m) != Trust.TRUSTED) continue;
                try {
                    byte[] sealed = E2ee.seal(pairKey(m), key, aad(id, me, m, epoch));
                    JsonObject o = new JsonObject();
                    o.addProperty("t", keyMessage);
                    o.addProperty("to", m);
                    o.addProperty("epoch", epoch);
                    o.addProperty("key", Base64.getEncoder().encodeToString(sealed));
                    VCraft.relay().send(o);
                    sentTo.add(m);
                } catch (Exception e) {
                    VCraft.LOGGER.warn("Could not send voice key", e);
                }
            }
        }

        synchronized void rekey(boolean clearReceived) {
            if (id == null) return;
            if (clearReceived) rx.clear();
            rotate();
            Set<String> members = new HashSet<>(lastMembers);
            members.add(State.myUuid);
            update(id, members);
        }

        void rotate() {
            key = E2ee.randomKey();
            epoch = (epoch + 1) & 0xFF;
            counter.set(0);
            sentTo.clear();
        }

        void onKey(String from, String forId, int keyEpoch, String keyB64) {
            String me = State.myUuid;
            if (me == null || forId == null || !forId.equals(id) || trust(from) != Trust.TRUSTED) return;
            try {
                byte[] k = E2ee.open(pairKey(from), Base64.getDecoder().decode(keyB64), aad(forId, from, me, keyEpoch));
                rx.put(from, new Rx(k, keyEpoch));
            } catch (Exception e) {
                VCraft.LOGGER.warn("Rejected a voice key from {}", from);
            }
        }

        private byte[] aad(String session, String from, String to, int e) {
            return E2ee.utf8(aadLabel + "|" + session + "|" + from + "|" + to + "|" + e);
        }

        /** [epoch][counter][AES-GCM(frame)] */
        byte[] encrypt(byte[] frame) {
            byte[] k;
            int e;
            synchronized (this) {
                k = key;
                e = epoch;
            }
            String me = State.myUuid;
            if (k == null || me == null) return null;
            long n = counter.incrementAndGet();
            try {
                byte[] ct = E2ee.seal(k, nonce(n), frame, voiceAad(me, e));
                return ByteBuffer.allocate(9 + ct.length).put((byte) e).putLong(n).put(ct).array();
            } catch (Exception ex) {
                return null;
            }
        }

        byte[] decrypt(String sender, byte[] data, int offset, int length) {
            Rx r = rx.get(sender);
            if (r == null || length < 9 + E2ee.TAG) return null;
            ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
            int e = buf.get() & 0xFF;
            long n = buf.getLong();
            // Frames arrive in order over TCP, so anything not newer is a replay.
            if (e != r.epoch || n <= r.lastCounter) return null;
            try {
                byte[] plain = E2ee.open(r.key, nonce(n), data, offset + 9, length - 9, voiceAad(sender, e));
                r.lastCounter = n;
                return plain;
            } catch (Exception ex) {
                return null;
            }
        }
    }

    // Calls keep their original key labels, so they work with older VCraft versions.
    private static final Session CALL = new Session("call_key", "callkey", false);
    private static final Session ROOM = new Session("room.key", "roomkey", true);

    private static Session active() {
        return State.call != null ? CALL : ROOM;
    }

    public static void onCall(State.Call call) {
        Set<String> members = new HashSet<>();
        if (call != null) for (State.CallMember m : call.members()) members.add(m.uuid());
        CALL.update(call == null ? null : call.id(), members);
    }

    public static void onRoom(State.Room room) {
        Set<String> members = new HashSet<>();
        if (room != null) for (State.CallMember m : room.members()) members.add(m.uuid());
        ROOM.update(room == null ? null : room.id(), members);
    }

    /** Fresh voice key, re-sent to everyone in the call. Clears received keys too when {@code clearReceived}. */
    public static void rekey(State.Call call, boolean clearReceived) {
        if (call != null) CALL.rekey(clearReceived);
    }

    public static void rekeyRoom(boolean clearReceived) {
        ROOM.rekey(clearReceived);
    }

    public static void onCallKey(String from, String forCall, int epoch, String keyB64) {
        CALL.onKey(from, forCall, epoch, keyB64);
    }

    public static void onRoomKey(String from, String forRoom, int epoch, String keyB64) {
        ROOM.onKey(from, forRoom, epoch, keyB64);
    }

    public static byte[] encryptVoice(byte[] frame) {
        return active().encrypt(frame);
    }

    public static byte[] decryptVoice(String sender, byte[] data, int offset, int length) {
        return active().decrypt(sender, data, offset, length);
    }

    private static byte[] nonce(long counter) {
        return ByteBuffer.allocate(E2ee.NONCE).putInt(0).putLong(counter).array();
    }

    private static byte[] voiceAad(String sender, int epoch) {
        UUID u = UUID.fromString(sender);
        return ByteBuffer.allocate(17).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).put((byte) epoch).array();
    }

    public static boolean hasVoiceKeyFrom(String uuid) {
        return active().rx.containsKey(uuid);
    }

    private Secure() {
    }
}
