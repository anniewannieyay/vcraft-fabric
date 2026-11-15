package dev.vcraft.bot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vcraft.core.E2ee;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A fake VCraft player for testing. Accepts friend requests, echoes messages (end-to-end
 * encrypted like a real client), answers calls and plays your voice back a second later.
 * Needs a relay started with --bot-token.
 */
public final class TestBot implements WebSocket.Listener {
    private static final String HELP = """
            I'm a VCraft test bot. Anything you send me gets echoed back. Commands:
            !call - I call you    !hangup - I leave the call    !status <online|idle|dnd|offline>
            !group - make a group with you    !invite - send a server invite    !delete - a message that deletes itself
            !community <text> - post in the VCraft Community chat
            !proximity <server address> - join that server's proximity voice room and echo voice there    !leave - leave it
            Call me and I'll answer after a few seconds and echo your voice back.""";

    private final String relay;
    private final String name;
    private final String token;
    private final String friend;
    private final long echoDelayMs;
    private final E2ee identity;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final StringBuilder text = new StringBuilder();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    private WebSocket ws;

    private String me;
    private final Map<String, String> keys = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Set<String>> groups = new HashMap<>();

    private String callId;
    private final Set<String> callMembers = new HashSet<>();
    private byte[] voiceKey;
    private int voiceEpoch;
    private long voiceCounter;
    private final Set<String> voiceKeySentTo = new HashSet<>();
    private final Map<String, byte[]> rxKeys = new HashMap<>();
    private final Map<String, Integer> rxEpochs = new HashMap<>();
    private String pendingDeleteText;

    // Proximity voice room (VCraft Proximity Chat addon). Keys rotate whenever someone joins or leaves.
    private String roomKey;
    private String roomId;
    private final Set<String> roomMembers = new HashSet<>();
    private byte[] roomVoiceKey;
    private int roomEpoch;
    private long roomCounter;
    private final Set<String> roomKeySentTo = new HashSet<>();
    private final Map<String, byte[]> roomRx = new HashMap<>();
    private final Map<String, Integer> roomRxEpochs = new HashMap<>();

    TestBot(String relay, String name, String token, String friend, long echoDelayMs) throws Exception {
        this.relay = relay;
        this.name = name;
        this.token = token;
        this.friend = friend;
        this.echoDelayMs = echoDelayMs;
        this.identity = new E2ee(loadIdentity(name));
    }

    public static void main(String[] args) throws Exception {
        String relay = "ws://localhost:24470", name = "Bot_Tester", token = System.getenv("VCRAFT_BOT_TOKEN"), friend = null;
        long delay = 1000;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--relay" -> relay = args[++i];
                case "--name" -> name = args[++i];
                case "--token" -> token = args[++i];
                case "--friend" -> friend = args[++i];
                case "--echo-delay" -> delay = Long.parseLong(args[++i]);
                default -> {
                    System.out.println("Usage: java -jar vcraft-testbot.jar --token <bot token> [--relay ws://localhost:24470] [--name Bot_Tester] [--friend <your name>] [--echo-delay 1000]");
                    return;
                }
            }
        }
        if (token == null) {
            System.out.println("Missing --token. Start the relay with --bot-token <secret> and pass the same secret here.");
            return;
        }
        new TestBot(relay, name, token, friend, delay).start();
        Thread.currentThread().join();
    }

    // Keep the same key between runs so you don't get "key changed" warnings.
    private static KeyPair loadIdentity(String name) throws Exception {
        Path file = Path.of("vcraft-bot-" + name + ".json");
        if (Files.exists(file)) {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return E2ee.decode(o.get("public").getAsString(), o.get("private").getAsString());
        }
        KeyPair pair = E2ee.generate();
        JsonObject o = new JsonObject();
        o.addProperty("public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        o.addProperty("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        Files.writeString(file, new Gson().toJson(o));
        return pair;
    }

    private void start() {
        log("Connecting to " + relay + " as " + name);
        HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(relay), this).whenComplete((w, e) -> {
            if (e != null) {
                log("Can't connect: " + e.getMessage() + " (retrying in 5s)");
                exec.schedule(this::start, 5, TimeUnit.SECONDS);
            }
        });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        ws = webSocket;
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
            String s = text.toString();
            text.setLength(0);
            exec.execute(() -> {
                try {
                    handle(JsonParser.parseString(s).getAsJsonObject());
                } catch (Exception e) {
                    log("Error: " + e);
                }
            });
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        binary.writeBytes(chunk);
        if (last) {
            byte[] frame = binary.toByteArray();
            binary.reset();
            exec.execute(() -> echoVoice(frame));
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        log("Disconnected (" + statusCode + " " + reason + "), reconnecting in 5s");
        exec.execute(this::resetCall);
        exec.schedule(this::start, 5, TimeUnit.SECONDS);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log("Socket error: " + error);
    }

    private void handle(JsonObject o) throws Exception {
        switch (str(o, "t")) {
            case "challenge" -> {
                JsonObject login = msg("login");
                login.addProperty("name", name);
                login.addProperty("key", identity.publicKey());
                login.addProperty("botToken", token);
                send(login);
            }
            case "welcome" -> {
                me = str(o, "uuid");
                log("Signed in as " + str(o, "name") + " (" + me + ")");
                if (friend != null) send("friend_request", "name", friend);
                send("lobby_join", null, null);
                if (roomKey != null) send("room.join", "key", roomKey);
            }
            case "error" -> log("Relay says: " + str(o, "text"));
            case "info" -> log(str(o, "text"));
            case "friends" -> {
                remember(o.getAsJsonArray("friends"));
                remember(o.getAsJsonArray("outgoing"));
                for (JsonElement e : o.getAsJsonArray("incoming")) {
                    JsonObject p = e.getAsJsonObject();
                    remember(p);
                    log("Accepting friend request from " + str(p, "name"));
                    send("friend_accept", "uuid", str(p, "uuid"));
                }
            }
            case "friend_added" -> log(str(o, "name") + " is now my friend");
            case "groups" -> {
                groups.clear();
                for (JsonElement e : o.getAsJsonArray("groups")) {
                    JsonObject g = e.getAsJsonObject();
                    Set<String> members = new HashSet<>();
                    for (JsonElement m : g.getAsJsonArray("members")) {
                        remember(m.getAsJsonObject());
                        members.add(str(m.getAsJsonObject(), "uuid"));
                    }
                    groups.put(str(g, "id"), members);
                }
            }
            case "dm" -> {
                String from = str(o, "from");
                if (from.equals(me)) {
                    if (pendingDeleteText != null) {
                        JsonObject env = open(str(o, "to"), "dm|" + from + "|" + str(o, "to"), str(o, "text"));
                        if (env != null && pendingDeleteText.equals(str(env, "text"))) {
                            pendingDeleteText = null;
                            String id = str(o, "id"), with = str(o, "to");
                            exec.schedule(() -> {
                                JsonObject d = msg("delete");
                                d.addProperty("id", id);
                                d.addProperty("with", with);
                                send(d);
                            }, 3, TimeUnit.SECONDS);
                        }
                    }
                    return;
                }
                JsonObject env = open(from, "dm|" + from + "|" + me, str(o, "text"));
                if (env == null) {
                    sendDm(from, "I couldn't decrypt that message.", null, null);
                    return;
                }
                log(str(o, "fromName") + ": " + str(env, "text"));
                onMessage(from, null, str(o, "id"), str(o, "fromName"), str(env, "text"));
            }
            case "gmsg" -> {
                String from = str(o, "from");
                if (from.equals(me)) return;
                String group = str(o, "group");
                JsonObject env = open(from, "gm|" + group + "|" + from + "|" + me, str(o, "text"));
                if (env == null) return;
                log("[group] " + str(o, "fromName") + ": " + str(env, "text"));
                onMessage(from, group, str(o, "id"), str(o, "fromName"), str(env, "text"));
            }
            case "lobby_msg" -> {
                String t = str(o, "text");
                if (!me.equals(str(o, "from")) && t != null && t.toLowerCase().contains("@bot")) {
                    JsonObject m = msg("lobby_msg");
                    m.addProperty("text", "Hi " + str(o, "fromName") + "! I'm a test bot. DM me !help.");
                    send(m);
                }
            }
            case "call_invite" -> {
                String cid = str(o, "callId");
                log(str(o, "fromName") + " is calling, answering in 4s");
                exec.schedule(() -> send("call_accept", "callId", cid), 4, TimeUnit.SECONDS);
            }
            case "call" -> onCall(o.get("call"));
            case "call_key" -> {
                String from = str(o, "from");
                int epoch = o.get("epoch").getAsInt();
                if (!str(o, "callId").equals(callId) || !keys.containsKey(from)) return;
                byte[] key = E2ee.open(identity.pairwiseKey(keys.get(from)), Base64.getDecoder().decode(str(o, "key")),
                        E2ee.utf8("callkey|" + callId + "|" + from + "|" + me + "|" + epoch));
                rxKeys.put(from, key);
                rxEpochs.put(from, epoch);
                log("Got voice key from " + names.getOrDefault(from, from));
            }
            case "room.state" -> onRoom(o.get("room"));
            case "room.key" -> {
                String from = str(o, "from");
                int epoch = o.get("epoch").getAsInt();
                if (!str(o, "room").equals(roomId) || !keys.containsKey(from)) return;
                byte[] key = E2ee.open(identity.pairwiseKey(keys.get(from)), Base64.getDecoder().decode(str(o, "key")),
                        E2ee.utf8("roomkey|" + roomId + "|" + from + "|" + me + "|" + epoch));
                roomRx.put(from, key);
                roomRxEpochs.put(from, epoch);
                log("Got proximity voice key from " + names.getOrDefault(from, from));
            }
            case "room.rekey" -> {
                roomRotate();
                sendRoomKeys();
            }
            default -> {
            }
        }
    }

    private void onMessage(String from, String group, String id, String fromName, String text) {
        String t = text == null ? "" : text.trim();
        String lower = t.toLowerCase();
        JsonObject reply = new JsonObject();
        reply.addProperty("id", id);
        reply.addProperty("name", fromName);
        reply.addProperty("text", t.length() > 120 ? t.substring(0, 120) : t);
        if (lower.equals("!help")) say(from, group, HELP, null, null);
        else if (lower.equals("!call")) {
            say(from, group, "Calling you now...", null, null);
            send("call_invite", "to", from);
        } else if (lower.equals("!hangup")) send("call_leave", null, null);
        else if (lower.startsWith("!status")) {
            String s = lower.replace("!status", "").trim();
            if (s.equals("offline")) s = "invisible";
            if (!java.util.List.of("online", "idle", "dnd", "invisible").contains(s)) {
                say(from, group, "Try !status online, idle, dnd or offline.", null, null);
                return;
            }
            send("status", "status", s);
            say(from, group, "Status set to " + (s.equals("invisible") ? "offline" : s) + ".", null, null);
        } else if (lower.equals("!group")) {
            JsonObject g = msg("group_create");
            g.addProperty("name", "Bot test group");
            JsonArray members = new JsonArray();
            members.add(from);
            g.add("members", members);
            send(g);
        } else if (lower.equals("!invite")) {
            JsonObject inv = new JsonObject();
            inv.addProperty("name", "Test Server");
            inv.addProperty("address", "localhost:25565");
            say(from, group, "Come play with me on Test Server!", null, inv);
        } else if (lower.equals("!delete")) {
            pendingDeleteText = "This message deletes itself in 3 seconds...";
            say(from, null, pendingDeleteText, null, null);
        } else if (lower.startsWith("!community")) {
            JsonObject m = msg("lobby_msg");
            String body = t.substring("!community".length()).trim();
            m.addProperty("text", body.isEmpty() ? "Hello from the test bot!" : body);
            send(m);
            say(from, group, "Posted in the VCraft Community chat.", null, null);
        } else if (lower.startsWith("!proximity")) {
            String address = t.substring("!proximity".length()).trim();
            if (address.isEmpty()) {
                say(from, group, "Tell me the server address, like !proximity play.example.com", null, null);
                return;
            }
            roomKey = proximityRoomKey(address);
            send("room.join", "key", roomKey);
            say(from, group, "Joined the proximity room for " + address + ". I echo voice there, but proximity sets my volume to zero because I have no position in your world.", null, null);
        } else if (lower.equals("!leave")) {
            roomKey = null;
            send("room.leave", null, null);
            say(from, group, "Left the proximity room.", null, null);
        } else {
            say(from, group, "Echo: " + t, reply, null);
        }
    }

    private void say(String to, String group, String text, JsonObject reply, JsonObject invite) {
        if (group != null) sendGroup(group, text, reply, invite);
        else sendDm(to, text, reply, invite);
    }

    private void sendDm(String to, String text, JsonObject reply, JsonObject invite) {
        String sealed = seal(to, "dm|" + me + "|" + to, envelope(text, reply, invite));
        if (sealed == null) return;
        JsonObject o = msg("dm");
        o.addProperty("to", to);
        o.addProperty("text", sealed);
        send(o);
    }

    private void sendGroup(String group, String text, JsonObject reply, JsonObject invite) {
        String env = envelope(text, reply, invite);
        JsonObject payloads = new JsonObject();
        for (String member : groups.getOrDefault(group, Set.of())) {
            String sealed = seal(member, "gm|" + group + "|" + me + "|" + member, env);
            if (sealed != null) payloads.addProperty(member, sealed);
        }
        JsonObject o = msg("gmsg");
        o.addProperty("group", group);
        o.add("payloads", payloads);
        send(o);
    }

    private static String envelope(String text, JsonObject reply, JsonObject invite) {
        JsonObject env = new JsonObject();
        env.addProperty("text", text);
        if (reply != null) env.add("reply", reply);
        if (invite != null) env.add("invite", invite);
        return env.toString();
    }

    private String seal(String to, String aad, String plain) {
        String key = to.equals(me) ? identity.publicKey() : keys.get(to);
        if (key == null) return null;
        try {
            return "e2:" + Base64.getEncoder().encodeToString(E2ee.seal(identity.pairwiseKey(key), E2ee.utf8(plain), E2ee.utf8(aad)));
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject open(String peer, String aad, String wire) {
        String key = keys.get(peer);
        if (wire == null || !wire.startsWith("e2:") || key == null) return null;
        try {
            byte[] plain = E2ee.open(identity.pairwiseKey(key), Base64.getDecoder().decode(wire.substring(3)), E2ee.utf8(aad));
            return JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    // Voice: same sender-key scheme as the mod.

    private void onCall(JsonElement c) throws Exception {
        if (c == null || c.isJsonNull()) {
            if (callId != null) log("Call ended");
            resetCall();
            return;
        }
        JsonObject call = c.getAsJsonObject();
        Set<String> members = new HashSet<>();
        for (JsonElement e : call.getAsJsonArray("members")) {
            JsonObject m = e.getAsJsonObject();
            remember(m);
            if (!str(m, "uuid").equals(me)) members.add(str(m, "uuid"));
        }
        if (!str(call, "id").equals(callId)) {
            callId = str(call, "id");
            rxKeys.clear();
            callMembers.clear();
            rotate();
            log("Joined call with " + members.size() + " other(s)");
        }
        if (!members.containsAll(callMembers)) rotate();
        callMembers.clear();
        callMembers.addAll(members);
        for (String m : members) {
            if (voiceKeySentTo.contains(m) || !keys.containsKey(m)) continue;
            byte[] sealed = E2ee.seal(identity.pairwiseKey(keys.get(m)), voiceKey,
                    E2ee.utf8("callkey|" + callId + "|" + me + "|" + m + "|" + voiceEpoch));
            JsonObject o = msg("call_key");
            o.addProperty("to", m);
            o.addProperty("epoch", voiceEpoch);
            o.addProperty("key", Base64.getEncoder().encodeToString(sealed));
            send(o);
            voiceKeySentTo.add(m);
        }
    }

    private void rotate() {
        voiceKey = E2ee.randomKey();
        voiceEpoch = (voiceEpoch + 1) & 0xFF;
        voiceCounter = 0;
        voiceKeySentTo.clear();
    }

    private void resetCall() {
        callId = null;
        voiceKey = null;
        callMembers.clear();
        rxKeys.clear();
        voiceKeySentTo.clear();
    }

    private void onRoom(JsonElement r) throws Exception {
        if (r == null || r.isJsonNull()) {
            if (roomId != null) log("Left the proximity room");
            roomId = null;
            roomVoiceKey = null;
            roomMembers.clear();
            roomRx.clear();
            roomKeySentTo.clear();
            return;
        }
        JsonObject room = r.getAsJsonObject();
        Set<String> members = new HashSet<>();
        for (JsonElement e : room.getAsJsonArray("members")) {
            JsonObject m = e.getAsJsonObject();
            remember(m);
            if (!str(m, "uuid").equals(me)) members.add(str(m, "uuid"));
        }
        if (!str(room, "id").equals(roomId)) {
            roomId = str(room, "id");
            roomRx.clear();
            roomMembers.clear();
            roomRotate();
            log("Joined proximity room with " + members.size() + " other(s)");
        } else if (!members.equals(roomMembers) && !roomMembers.isEmpty()) {
            roomRotate();
            roomRx.keySet().retainAll(members);
        }
        roomMembers.clear();
        roomMembers.addAll(members);
        sendRoomKeys();
    }

    private void roomRotate() {
        roomVoiceKey = E2ee.randomKey();
        roomEpoch = (roomEpoch + 1) & 0xFF;
        roomCounter = 0;
        roomKeySentTo.clear();
    }

    private void sendRoomKeys() {
        if (roomId == null || roomVoiceKey == null) return;
        for (String m : roomMembers) {
            if (roomKeySentTo.contains(m) || !keys.containsKey(m)) continue;
            try {
                byte[] sealed = E2ee.seal(identity.pairwiseKey(keys.get(m)), roomVoiceKey,
                        E2ee.utf8("roomkey|" + roomId + "|" + me + "|" + m + "|" + roomEpoch));
                JsonObject o = msg("room.key");
                o.addProperty("to", m);
                o.addProperty("epoch", roomEpoch);
                o.addProperty("key", Base64.getEncoder().encodeToString(sealed));
                send(o);
                roomKeySentTo.add(m);
            } catch (Exception e) {
                log("Couldn't send a proximity key: " + e);
            }
        }
    }

    /** Same room key as the VCraft Proximity Chat addon. */
    static String proximityRoomKey(String address) {
        String a = address.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        if (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(("vcraft-proximity|" + a).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "mc:" + java.util.HexFormat.of().formatHex(h).substring(0, 40);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Decrypt the speaker's frame and send it back under our own key after a delay. Calls take priority over the room.
    private void echoVoice(byte[] data) {
        boolean inCall = callId != null;
        byte[] sendKey = inCall ? voiceKey : roomVoiceKey;
        if (data.length < 16 + 9 + E2ee.TAG || sendKey == null) return;
        ByteBuffer buf = ByteBuffer.wrap(data);
        UUID speaker = new UUID(buf.getLong(), buf.getLong());
        String sid = speaker.toString();
        byte[] key = (inCall ? rxKeys : roomRx).get(sid);
        if (key == null) return;
        int epoch = buf.get() & 0xFF;
        long counter = buf.getLong();
        if (epoch != (inCall ? rxEpochs : roomRxEpochs).getOrDefault(sid, -1)) return;
        try {
            byte[] frame = E2ee.open(key, nonce(counter), data, 25, data.length - 25, voiceAad(sid, epoch));
            exec.schedule(() -> {
                if (ws == null) return;
                try {
                    byte[] out;
                    if (callId != null) {
                        if (voiceKey == null) return;
                        long n = ++voiceCounter;
                        byte[] ct = E2ee.seal(voiceKey, nonce(n), frame, voiceAad(me, voiceEpoch));
                        out = ByteBuffer.allocate(9 + ct.length).put((byte) voiceEpoch).putLong(n).put(ct).array();
                    } else {
                        if (roomVoiceKey == null) return;
                        long n = ++roomCounter;
                        byte[] ct = E2ee.seal(roomVoiceKey, nonce(n), frame, voiceAad(me, roomEpoch));
                        out = ByteBuffer.allocate(9 + ct.length).put((byte) roomEpoch).putLong(n).put(ct).array();
                    }
                    ws.sendBinary(ByteBuffer.wrap(out), true).join();
                } catch (Exception ignored) {
                }
            }, echoDelayMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private static byte[] nonce(long counter) {
        return ByteBuffer.allocate(E2ee.NONCE).putInt(0).putLong(counter).array();
    }

    private static byte[] voiceAad(String uuid, int epoch) {
        UUID u = UUID.fromString(uuid);
        return ByteBuffer.allocate(17).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).put((byte) epoch).array();
    }

    // Helpers

    private void remember(JsonArray arr) {
        if (arr != null) for (JsonElement e : arr) remember(e.getAsJsonObject());
    }

    private void remember(JsonObject p) {
        String uuid = str(p, "uuid");
        if (uuid == null) return;
        if (str(p, "key") != null) keys.put(uuid, str(p, "key"));
        if (str(p, "name") != null) names.put(uuid, str(p, "name"));
    }

    private void send(String type, String key, String value) {
        JsonObject o = msg(type);
        if (key != null) o.addProperty(key, value);
        send(o);
    }

    private synchronized void send(JsonObject o) {
        if (ws == null) return;
        try {
            ws.sendText(o.toString(), true).join();
        } catch (Exception e) {
            log("Send failed: " + e.getMessage());
        }
    }

    private static JsonObject msg(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static void log(String s) {
        System.out.println("[bot] " + s);
    }
}
