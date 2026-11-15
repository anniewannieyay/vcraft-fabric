package dev.vcraft.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Routes friend requests, end-to-end encrypted messages, call signalling and voice between signed-in players. All state lives on one logic thread. */
public final class RelayServer extends WebSocketServer {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Set<String> STATUSES = Set.of("online", "idle", "dnd", "invisible");
    private static final int MAX_TEXT = 8192;
    private static final int MAX_GROUP_PAYLOAD = 96 * 1024;
    private static final int MAX_KEY = 200;
    private static final int MAX_GROUP_MEMBERS = 16;
    private static final int MAX_VOICE_FRAME = 1024;
    private static final int MAX_FRAME = 128 * 1024;
    private static final long INVITE_TTL_MS = 60_000;
    private static final int MAX_FRIENDS = 500;
    private static final int MAX_OUTGOING = 100;
    private static final int MAX_INCOMING = 300;
    private static final int MAX_BLOCKED = 1000;
    private static final int MAX_GROUPS_PER_USER = 30;

    private final ScheduledExecutorService logic = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "relay-logic"));
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();
    final Store store;
    private final Options opts;
    private final boolean offlineMode;

    final Map<String, Client> online = new HashMap<>();
    private final RelayAddons addons = new RelayAddons(this);
    private final Map<String, Call> calls = new HashMap<>();
    private final Map<String, AtomicInteger> connectionsPerIp = new ConcurrentHashMap<>();
    private final Map<String, Bucket> loginsPerIp = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    // Shared budget for Mojang name lookups, so no single user can get the relay rate-limited.
    private final Bucket mojangLookups = Bucket.perMinute(60, 120);
    private final Map<String, Long> unknownNames = new HashMap<>();

    static final class Client {
        final WebSocket conn;
        final String challenge;
        final String ip;
        String uuid;
        String name;
        String key;
        Call call;
        boolean muted;
        boolean deafened;
        final ArrayDeque<Long> recent = new ArrayDeque<>();
        final Bucket actions = new Bucket(40, 8);
        final Bucket friendRequests = Bucket.perMinute(10, 1);
        final Bucket groupChanges = Bucket.perMinute(10, 2);
        final Bucket callInvites = Bucket.perMinute(20, 10);
        final Bucket history = Bucket.perMinute(30, 30);
        final Bucket voice = new Bucket(150, 75);
        final Bucket lobbyPosts = Bucket.perMinute(5, 12);
        final Bucket activity = Bucket.perMinute(20, 30);
        boolean inLobby;
        RelayAddons.Room room;
        Set<String> addons = Set.of();

        Client(WebSocket conn, String challenge, String ip) {
            this.conn = conn;
            this.challenge = challenge;
            this.ip = ip;
        }

        boolean authed() {
            return uuid != null;
        }
    }

    static final class Call {
        final String id;
        final Set<Client> members = new LinkedHashSet<>();
        final Map<String, Long> invites = new LinkedHashMap<>();

        Call(String id) {
            this.id = id;
        }
    }

    public RelayServer(Options opts, Store store) {
        super(new InetSocketAddress(opts.bind, opts.port),
                List.of(new Draft_6455(Collections.emptyList(), List.of(new Protocol("")), MAX_FRAME)));
        this.store = store;
        this.opts = opts;
        this.offlineMode = opts.offline;
        setConnectionLostTimeout(30);
        setReuseAddr(true);
        setMaxPendingConnections(200);
    }

    public static void main(String[] args) throws Exception {
        Options opts = Options.parse(args);
        RelayServer server = new RelayServer(opts, new Store(opts.data));
        server.logic.scheduleWithFixedDelay(server.store::saveIfDirty, 5, 5, TimeUnit.SECONDS);
        server.logic.scheduleWithFixedDelay(server::expireInvites, 5, 5, TimeUnit.SECONDS);
        server.logic.scheduleWithFixedDelay(server::housekeeping, 1, 60, TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(1000);
                server.logic.submit(server.store::saveIfDirty).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }));
        server.start();
        System.out.println("[relay] VCraft relay listening on " + opts.bind + ":" + opts.port
                + (opts.offline ? " (OFFLINE MODE: accounts are NOT verified)" : ""));
        if (opts.console) new AdminConsole(server).start();
    }

    @Override
    public void onStart() {
    }

    private String clientIp(WebSocket conn, ClientHandshake handshake) {
        if (opts.trustProxy) {
            String cf = handshake.getFieldValue("CF-Connecting-IP");
            if (cf != null && !cf.isBlank()) return cf.trim();
            String xff = handshake.getFieldValue("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        }
        InetSocketAddress remote = conn.getRemoteSocketAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String ip = clientIp(conn, handshake);
        int perIp = connectionsPerIp.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet();
        int total = connectionCount.incrementAndGet();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // The session id is derived from our host and the nonce, never picked freely: a client
        // computes it itself, so no relay can make it a real server's handshake hash.
        Client c = new Client(conn, SessionId.of(opts.publicHost, nonce), ip);
        conn.setAttachment(c);
        if (total > opts.maxConnections || perIp > opts.maxPerIp) {
            conn.close(1008, total > opts.maxConnections ? "Relay is full, try again later" : "Too many connections from your network");
            return;
        }
        JsonObject o = msg("challenge");
        o.addProperty("nonce", nonce);
        // Older clients join whatever serverId we send; give them the same derived value.
        o.addProperty("serverId", c.challenge);
        o.addProperty("offline", offlineMode);
        o.addProperty("protocol", 3);
        send(c, o);
        logic.schedule(() -> {
            if (!c.authed() && conn.isOpen()) conn.close(1008, "Sign-in timed out");
        }, opts.authTimeoutSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void onMessage(WebSocket conn, String text) {
        Client c = conn.getAttachment();
        if (text.length() > MAX_FRAME) {
            conn.close(1009, "Message too large");
            return;
        }
        JsonObject o;
        try {
            o = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        logic.execute(() -> {
            try {
                handle(c, o);
            } catch (Exception e) {
                System.err.println("[relay] Error handling " + str(o, "t") + " from " + c.name + ": " + e);
                error(c, "Bad request");
            }
        });
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer data) {
        Client c = conn.getAttachment();
        if (data.remaining() > MAX_VOICE_FRAME || !c.voice.take()) return;
        byte[] payload = new byte[data.remaining()];
        data.get(payload);
        logic.execute(() -> relayVoice(c, payload));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Client c = conn.getAttachment();
        if (c == null) return;
        connectionCount.decrementAndGet();
        AtomicInteger n = connectionsPerIp.get(c.ip);
        if (n != null && n.decrementAndGet() <= 0) connectionsPerIp.remove(c.ip, n);
        logic.execute(() -> disconnected(c));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Client c = conn == null ? null : conn.getAttachment();
        System.err.println("[relay] " + (c == null || c.name == null ? "connection" : c.name) + ": " + ex);
    }

    private void handle(Client c, JsonObject o) {
        String type = str(o, "t");
        if (type == null) return;
        if (!c.authed()) {
            if ("login".equals(type)) {
                Bucket b = loginsPerIp.computeIfAbsent(c.ip, k -> Bucket.perMinute(10, 10));
                if (!b.take()) {
                    error(c, "Too many sign-in attempts. Wait a minute and try again.");
                    c.conn.close(1008, "Too many sign-in attempts");
                    return;
                }
                if (o.has("botToken")) botLogin(c, str(o, "name"), str(o, "key"), str(o, "botToken"));
                else login(c, str(o, "name"), str(o, "key"), str(o, "status"));
            }
            return;
        }
        if (!c.actions.take()) {
            error(c, "Slow down!");
            return;
        }
        switch (type) {
            case "delete_account" -> {
                if ("DELETE".equals(str(o, "confirm"))) deleteAccount(c);
            }
            case "export_data" -> exportData(c);
            case "lobby_join" -> lobbyJoin(c);
            case "lobby_leave" -> lobbyLeave(c);
            case "lobby_msg" -> lobbyMessage(c, str(o, "text"), o.has("reply") && o.get("reply").isJsonObject() ? o.getAsJsonObject("reply") : null);
            case "lobby_delete" -> lobbyDelete(c, str(o, "id"));
            case "lobby_mute" -> lobbyMute(c, str(o, "uuid"), bool(o, "muted"));
            case "admin" -> adminCommand(c, str(o, "cmd"), str(o, "arg"));
            case "status" -> setStatus(c, str(o, "status"));
            case "activity" -> activity(c, o.has("payloads") && o.get("payloads").isJsonObject() ? o.getAsJsonObject("payloads") : null);
            case "friend_request" -> friendRequest(c, str(o, "name"));
            case "friend_accept" -> friendAccept(c, str(o, "uuid"));
            case "friend_remove" -> friendRemove(c, str(o, "uuid"));
            case "block" -> block(c, str(o, "uuid"));
            case "unblock" -> unblock(c, str(o, "uuid"));
            case "dm" -> directMessage(c, str(o, "to"), str(o, "text"));
            case "gmsg" -> groupMessage(c, str(o, "group"), o.getAsJsonObject("payloads"));
            case "delete" -> deleteMessage(c, str(o, "id"), str(o, "with"), str(o, "group"));
            case "history" -> history(c, str(o, "with"), str(o, "group"));
            case "group_create" -> groupCreate(c, str(o, "name"), o.getAsJsonArray("members"));
            case "group_add" -> groupAdd(c, str(o, "group"), str(o, "uuid"));
            case "group_kick" -> groupKick(c, str(o, "group"), str(o, "uuid"));
            case "group_leave" -> groupLeave(c, str(o, "group"));
            case "group_rename" -> groupRename(c, str(o, "group"), str(o, "name"));
            case "call_invite" -> callInvite(c, str(o, "to"));
            case "call_accept" -> callAccept(c, str(o, "callId"));
            case "call_decline" -> callDecline(c, str(o, "callId"));
            case "call_leave" -> leaveCall(c, true);
            case "call_key" -> forwardCallKey(c, o);
            case "call_rekey" -> {
                if (c.call == null || !c.callInvites.take()) return;
                JsonObject r = msg("call_rekey");
                r.addProperty("from", c.uuid);
                for (Client m : c.call.members) if (m != c) send(m, r);
            }
            case "state" -> {
                c.muted = bool(o, "muted");
                c.deafened = bool(o, "deafened");
                if (c.call != null) broadcastCall(c.call);
                if (c.room != null) addons.broadcastRoom(c.room);
            }
            default -> addons.handle(c, type, o);
        }
    }


    private void botLogin(Client c, String name, String key, String token) {
        boolean tokenOk = opts.botToken != null && token != null
                && java.security.MessageDigest.isEqual(opts.botToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!tokenOk || name == null || !NAME.matcher(name).matches() || !name.startsWith("Bot")) {
            error(c, "Bot sign-in refused.");
            c.conn.close(1008, "Bot sign-in refused");
            return;
        }
        c.key = key != null && key.length() <= MAX_KEY ? key : null;
        // Name-based (version 3) UUIDs, which can never collide with real Minecraft accounts.
        String uuid = UUID.nameUUIDFromBytes(("VCraftBot:" + name).getBytes(StandardCharsets.UTF_8)).toString();
        completeLogin(c, uuid, name, "online");
    }

    private void login(Client c, String name, String key, String status) {
        if (name == null || !NAME.matcher(name).matches()) {
            error(c, "Invalid username");
            c.conn.close();
            return;
        }
        c.key = key != null && key.length() <= MAX_KEY ? key : null;
        String wantedStatus = status != null && STATUSES.contains(status) ? status : null;
        if (offlineMode) {
            String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
            completeLogin(c, uuid, name, wantedStatus);
            return;
        }
        String url = "https://sessionserver.mojang.com/session/minecraft/hasJoined?username="
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&serverId=" + c.challenge;
        http.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .whenCompleteAsync((res, ex) -> {
                    if (ex != null || res.statusCode() != 200 || res.body().isBlank()) {
                        error(c, "Could not verify your Minecraft account.");
                        c.conn.close();
                        return;
                    }
                    JsonObject profile = JsonParser.parseString(res.body()).getAsJsonObject();
                    completeLogin(c, dashed(str(profile, "id")), str(profile, "name"), wantedStatus);
                }, logic);
    }

    private void completeLogin(Client c, String uuid, String name, String status) {
        if (!c.conn.isOpen()) return;
        if (store.isBanned(uuid)) {
            error(c, "You are banned from this relay.");
            c.conn.close(1008, "Banned");
            return;
        }
        Client previous = online.get(uuid);
        if (previous != null) {
            error(previous, "You signed in from another location.");
            disconnected(previous);
            previous.conn.close();
        }
        c.uuid = uuid;
        c.name = name;
        Store.User user = store.touch(uuid, name);
        if (status != null) user.status = status;
        boolean keyChanged = c.key != null && !c.key.equals(user.key);
        if (keyChanged) user.key = c.key;
        store.markDirty();
        long lastSeen = user.lastSeen;
        online.put(uuid, c);

        JsonObject welcome = msg("welcome");
        welcome.addProperty("uuid", uuid);
        welcome.addProperty("name", name);
        welcome.addProperty("status", user.status);
        welcome.addProperty("rank", store.rank(uuid));
        send(c, welcome);
        sendFriends(c);
        sendGroups(c);

        Map<String, Integer> unread = store.unreadSince(uuid, lastSeen);
        if (!unread.isEmpty()) {
            JsonObject u = msg("unread");
            JsonObject counts = new JsonObject();
            unread.forEach(counts::addProperty);
            u.add("counts", counts);
            send(c, u);
        }
        if (keyChanged) refreshContacts(uuid);
        else broadcastPresence(uuid);
        System.out.println("[relay] " + name + " connected");
    }

    private void disconnected(Client c) {
        if (!c.authed() || online.get(c.uuid) != c) return;
        online.remove(c.uuid);
        if (c.inLobby) {
            c.inLobby = false;
            broadcastLobbyCount();
        }
        leaveCall(c, false);
        addons.disconnected(c);
        Store.User u = store.user(c.uuid);
        u.lastSeen = System.currentTimeMillis();
        store.markDirty();
        broadcastPresence(c.uuid);
        System.out.println("[relay] " + c.name + " disconnected");
    }

    private void setStatus(Client c, String status) {
        if (status == null || !STATUSES.contains(status)) return;
        store.user(c.uuid).status = status;
        store.markDirty();
        broadcastPresence(c.uuid);
        JsonObject o = msg("status");
        o.addProperty("status", status);
        send(c, o);
    }


    /** What {@code viewer} is allowed to see of {@code uuid}'s status. */
    private String statusFor(String uuid, String viewer) {
        Store.User u = store.user(uuid);
        if (u == null || !online.containsKey(uuid) || u.blocked.contains(viewer)) return "offline";
        return "invisible".equals(u.status) ? "offline" : u.status;
    }

    private Set<String> contacts(String uuid) {
        Set<String> out = new LinkedHashSet<>(store.user(uuid).friends);
        for (Store.Group g : store.groups.values()) {
            if (g.members.contains(uuid)) out.addAll(g.members);
        }
        out.remove(uuid);
        return out;
    }

    private void broadcastPresence(String uuid) {
        for (String other : contacts(uuid)) {
            Client oc = online.get(other);
            if (oc == null) continue;
            JsonObject o = msg("presence");
            o.addProperty("uuid", uuid);
            o.addProperty("status", statusFor(uuid, other));
            send(oc, o);
        }
    }

    private void refreshContacts(String uuid) {
        for (String other : contacts(uuid)) {
            Client oc = online.get(other);
            if (oc == null) continue;
            sendFriends(oc);
            sendGroups(oc);
        }
    }


    private void friendRequest(Client c, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            error(c, "Invalid username");
            return;
        }
        if (!c.friendRequests.take()) {
            error(c, "You're sending too many friend requests. Try again in a few minutes.");
            return;
        }
        String known = store.findUuidByName(name);
        if (known != null) {
            friendRequestResolved(c, known);
            return;
        }
        if (offlineMode) {
            error(c, name + " has never used VCraft on this relay.");
            return;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        Long unknownUntil = unknownNames.get(lower);
        if (unknownUntil != null && unknownUntil > System.currentTimeMillis()) {
            error(c, "No Minecraft account named " + name + ".");
            return;
        }
        if (!mojangLookups.take()) {
            error(c, "The relay is busy. Try adding " + name + " again in a minute.");
            return;
        }
        String url = "https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        http.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .whenCompleteAsync((res, ex) -> {
                    if (ex != null || res.statusCode() != 200) {
                        if (ex == null && (res.statusCode() == 404 || res.statusCode() == 204)) unknownNames.put(lower, System.currentTimeMillis() + 3_600_000);
                        error(c, ex == null && res.statusCode() == 429 ? "Mojang is busy. Try again in a minute." : "No Minecraft account named " + name + ".");
                        return;
                    }
                    JsonObject profile = JsonParser.parseString(res.body()).getAsJsonObject();
                    String uuid = dashed(str(profile, "id"));
                    store.touch(uuid, str(profile, "name"));
                    friendRequestResolved(c, uuid);
                }, logic);
    }

    private void friendRequestResolved(Client c, String target) {
        if (!c.conn.isOpen()) return;
        Store.User me = store.user(c.uuid);
        Store.User them = store.user(target);
        String targetName = store.nameOf(target);
        if (target.equals(c.uuid)) {
            error(c, "You can't add yourself.");
            return;
        }
        if (me.blocked.contains(target)) {
            error(c, "Unblock " + targetName + " first.");
            return;
        }
        if (me.friends.contains(target)) {
            error(c, targetName + " is already your friend.");
            return;
        }
        if (me.incoming.contains(target)) {
            friendAccept(c, target);
            return;
        }
        if (me.friends.size() >= MAX_FRIENDS) {
            error(c, "You've reached the friend limit (" + MAX_FRIENDS + ").");
            return;
        }
        if (me.outgoing.size() >= MAX_OUTGOING) {
            error(c, "You have too many pending requests. Cancel some first.");
            return;
        }
        info(c, "Friend request sent to " + targetName + ".");
        if (them.blocked.contains(c.uuid)) return;
        if (them.incoming.size() >= MAX_INCOMING || them.friends.size() >= MAX_FRIENDS) return;
        me.outgoing.add(target);
        them.incoming.add(c.uuid);
        store.markDirty();
        sendFriends(c);
        Client other = online.get(target);
        if (other != null) {
            JsonObject n = msg("friend_request");
            n.addProperty("uuid", c.uuid);
            n.addProperty("name", c.name);
            send(other, n);
            sendFriends(other);
        }
    }

    private void friendAccept(Client c, String from) {
        Store.User me = store.user(c.uuid);
        Store.User them = from == null ? null : store.user(from);
        if (them == null || !me.incoming.remove(from)) return;
        them.outgoing.remove(c.uuid);
        me.friends.add(from);
        them.friends.add(c.uuid);
        store.markDirty();
        sendFriends(c);
        Client other = online.get(from);
        if (other != null) {
            JsonObject n = msg("friend_added");
            n.addProperty("uuid", c.uuid);
            n.addProperty("name", c.name);
            send(other, n);
            sendFriends(other);
        }
    }

    private void friendRemove(Client c, String other) {
        Store.User them = other == null ? null : store.user(other);
        if (them == null) return;
        unlink(store.user(c.uuid), c.uuid, them, other);
        sendFriends(c);
        Client oc = online.get(other);
        if (oc != null) sendFriends(oc);
    }

    private void unlink(Store.User a, String aId, Store.User b, String bId) {
        a.friends.remove(bId);
        a.incoming.remove(bId);
        a.outgoing.remove(bId);
        b.friends.remove(aId);
        b.incoming.remove(aId);
        b.outgoing.remove(aId);
        store.markDirty();
    }

    private void block(Client c, String target) {
        Store.User them = target == null ? null : store.user(target);
        if (them == null || target.equals(c.uuid)) return;
        Store.User me = store.user(c.uuid);
        if (me.blocked.size() >= MAX_BLOCKED) {
            error(c, "Your block list is full.");
            return;
        }
        unlink(me, c.uuid, them, target);
        me.blocked.add(target);
        store.markDirty();
        Client oc = online.get(target);
        if (oc != null && oc.call != null && oc.call == c.call) leaveCall(c, true);
        if (c.room != null) addons.broadcastRoom(c.room);
        sendFriends(c);
        if (oc != null) {
            sendFriends(oc);
            JsonObject o = msg("presence");
            o.addProperty("uuid", c.uuid);
            o.addProperty("status", "offline");
            send(oc, o);
        }
        info(c, store.nameOf(target) + " is blocked.");
    }

    private void unblock(Client c, String target) {
        if (target == null || !store.user(c.uuid).blocked.remove(target)) return;
        store.markDirty();
        if (c.room != null) addons.broadcastRoom(c.room);
        sendFriends(c);
        broadcastPresence(c.uuid);
        info(c, store.nameOf(target) + " is unblocked.");
    }

    boolean blockedEitherWay(String a, String b) {
        Store.User ua = store.user(a);
        Store.User ub = store.user(b);
        return ua == null || ub == null || ua.blocked.contains(b) || ub.blocked.contains(a);
    }

    boolean areFriends(String a, String b) {
        return b != null && store.user(a).friends.contains(b);
    }

    private boolean shareGroup(String a, String b) {
        for (Store.Group g : store.groups.values()) {
            if (g.members.contains(a) && g.members.contains(b)) return true;
        }
        return false;
    }

    private void sendFriends(Client c) {
        Store.User me = store.user(c.uuid);
        JsonObject o = msg("friends");
        JsonArray friends = new JsonArray();
        me.friends.forEach(f -> friends.add(profile(f, c.uuid)));
        JsonArray incoming = new JsonArray();
        me.incoming.forEach(u -> incoming.add(profile(u, c.uuid)));
        JsonArray outgoing = new JsonArray();
        me.outgoing.forEach(u -> outgoing.add(profile(u, c.uuid)));
        JsonArray blocked = new JsonArray();
        me.blocked.forEach(u -> blocked.add(profile(u, c.uuid)));
        o.add("friends", friends);
        o.add("incoming", incoming);
        o.add("outgoing", outgoing);
        o.add("blocked", blocked);
        send(c, o);
    }


    boolean rateLimited(Client c) {
        long now = System.currentTimeMillis();
        while (!c.recent.isEmpty() && now - c.recent.peekFirst() > 5000) c.recent.pollFirst();
        if (c.recent.size() >= 10) {
            error(c, "Slow down! You're sending messages too quickly.");
            return true;
        }
        c.recent.addLast(now);
        return false;
    }

    private void directMessage(Client c, String to, String text) {
        if (!areFriends(c.uuid, to) || blockedEitherWay(c.uuid, to)) {
            error(c, "You can only message friends.");
            return;
        }
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT || rateLimited(c)) return;
        Store.Message m = store.addMessage(newId(), c.uuid, to, text);
        JsonObject o = dmJson(m);
        send(c, o);
        Client other = online.get(to);
        if (other != null) send(other, o);
    }

    /** "Now listening" updates: sealed per friend, forwarded only to friends who can see you online, never stored. */
    private void activity(Client c, JsonObject payloads) {
        if (payloads == null || payloads.size() > 500 || !c.activity.take()) return;
        for (Map.Entry<String, JsonElement> e : payloads.entrySet()) {
            String to = e.getKey();
            Client target = online.get(to);
            if (target == null || !e.getValue().isJsonPrimitive() || !areFriends(c.uuid, to) || blockedEitherWay(c.uuid, to)
                    || "offline".equals(statusFor(c.uuid, to))) continue;
            String body = e.getValue().getAsString();
            if (body.length() > 2048) continue;
            JsonObject out = msg("activity");
            out.addProperty("from", c.uuid);
            out.addProperty("body", body);
            send(target, out);
        }
    }

    private JsonObject dmJson(Store.Message m) {
        JsonObject o = msg("dm");
        o.addProperty("id", m.id());
        o.addProperty("from", m.from());
        o.addProperty("fromName", store.nameOf(m.from()));
        o.addProperty("fromRank", store.rank(m.from()));
        o.addProperty("to", m.to());
        o.addProperty("text", m.text());
        o.addProperty("ts", m.ts());
        return o;
    }

    private void groupMessage(Client c, String groupId, JsonObject payloads) {
        Store.Group g = groupId == null ? null : store.groups.get(groupId);
        if (g == null || !g.members.contains(c.uuid) || payloads == null || payloads.toString().length() > MAX_GROUP_PAYLOAD || rateLimited(c)) return;
        if (!addons.allowGroupPost(c, g)) return;
        Map<String, String> copies = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : payloads.entrySet()) {
            if (g.members.contains(e.getKey()) && e.getValue().isJsonPrimitive()) copies.put(e.getKey(), e.getValue().getAsString());
        }
        Store.GroupMessage m = store.addGroupMessage(newId(), c.uuid, g.id, copies);
        for (String member : g.members) {
            Client mc = online.get(member);
            if (mc == null || store.user(member).blocked.contains(c.uuid)) continue;
            send(mc, groupMessageJson(m, member));
        }
    }

    private JsonObject groupMessageJson(Store.GroupMessage m, String viewer) {
        JsonObject o = msg("gmsg");
        o.addProperty("id", m.id());
        o.addProperty("group", m.group());
        o.addProperty("from", m.from());
        o.addProperty("fromName", store.nameOf(m.from()));
        o.addProperty("fromRank", store.rank(m.from()));
        o.addProperty("text", m.payloads().get(viewer));
        o.addProperty("ts", m.ts());
        return o;
    }

    private void deleteMessage(Client c, String id, String with, String groupId) {
        if (id == null) return;
        if (groupId != null) {
            Store.Group g = store.groups.get(groupId);
            if (g == null || !g.members.contains(c.uuid) || !store.deleteGroupMessage(groupId, id, c.uuid)) return;
            JsonObject o = msg("deleted");
            o.addProperty("id", id);
            o.addProperty("group", groupId);
            for (String member : g.members) {
                Client mc = online.get(member);
                if (mc != null) send(mc, o);
            }
        } else if (with != null && store.deleteMessage(c.uuid, with, id, c.uuid)) {
            JsonObject mine = msg("deleted");
            mine.addProperty("id", id);
            mine.addProperty("with", with);
            send(c, mine);
            Client other = online.get(with);
            if (other != null) {
                JsonObject theirs = msg("deleted");
                theirs.addProperty("id", id);
                theirs.addProperty("with", c.uuid);
                send(other, theirs);
            }
        }
    }

    private void history(Client c, String with, String groupId) {
        if (!c.history.take()) return;
        JsonObject o = msg("history");
        JsonArray arr = new JsonArray();
        if (groupId != null) {
            Store.Group g = store.groups.get(groupId);
            if (g == null || !g.members.contains(c.uuid)) return;
            Set<String> blocked = store.user(c.uuid).blocked;
            for (Store.GroupMessage m : store.groupHistory(groupId, 150)) {
                if (!blocked.contains(m.from())) arr.add(groupMessageJson(m, c.uuid));
            }
            o.addProperty("group", groupId);
        } else {
            if (!areFriends(c.uuid, with)) return;
            for (Store.Message m : store.history(c.uuid, with, 150)) arr.add(dmJson(m));
            o.addProperty("with", with);
        }
        o.add("messages", arr);
        send(c, o);
    }


    private void groupCreate(Client c, String name, JsonArray members) {
        if (!c.groupChanges.take()) {
            error(c, "Slow down! Try again in a minute.");
            return;
        }
        if (groupCount(c.uuid) >= MAX_GROUPS_PER_USER) {
            error(c, "You're in too many groups (" + MAX_GROUPS_PER_USER + "). Leave one first.");
            return;
        }
        name = cleanGroupName(name);
        if (name == null) {
            error(c, "Give the group a name.");
            return;
        }
        Store.Group g = new Store.Group();
        g.id = newId();
        g.name = name;
        g.owner = c.uuid;
        g.members.add(c.uuid);
        if (members != null) {
            for (JsonElement e : members) {
                String m = e.getAsString();
                if (g.members.size() >= MAX_GROUP_MEMBERS) break;
                if (areFriends(c.uuid, m) && !blockedEitherWay(c.uuid, m) && groupCount(m) < MAX_GROUPS_PER_USER) g.members.add(m);
            }
        }
        store.groups.put(g.id, g);
        store.markDirty();
        groupChanged(g, null);
        JsonObject o = msg("group_created");
        o.addProperty("group", g.id);
        send(c, o);
    }

    private void groupAdd(Client c, String groupId, String uuid) {
        Store.Group g = groupId == null ? null : store.groups.get(groupId);
        if (g == null || !g.members.contains(c.uuid) || g.members.contains(uuid)) return;
        if (!c.groupChanges.take()) {
            error(c, "Slow down! Try again in a minute.");
            return;
        }
        if (groupCount(uuid) >= MAX_GROUPS_PER_USER) {
            error(c, store.nameOf(uuid) + " is in too many groups.");
            return;
        }
        if (!areFriends(c.uuid, uuid) || blockedEitherWay(c.uuid, uuid)) {
            error(c, "You can only add your friends.");
            return;
        }
        if (g.members.size() >= MAX_GROUP_MEMBERS) {
            error(c, "Groups can have up to " + MAX_GROUP_MEMBERS + " people.");
            return;
        }
        g.members.add(uuid);
        store.markDirty();
        groupChanged(g, null);
        groupNotice(g, c.name + " added " + store.nameOf(uuid) + ".");
        addons.memberAdded(g);
    }

    void groupKick(Client c, String groupId, String uuid) {
        Store.Group g = groupId == null ? null : store.groups.get(groupId);
        if (g == null || uuid == null || uuid.equals(c.uuid) || !g.members.contains(c.uuid) || !addons.canKick(g, c.uuid, uuid)
                || !g.members.remove(uuid)) return;
        addons.memberRemoved(g, uuid);
        store.markDirty();
        groupChanged(g, uuid);
        groupNotice(g, c.name + " removed " + store.nameOf(uuid) + ".");
    }

    private void groupLeave(Client c, String groupId) {
        Store.Group g = groupId == null ? null : store.groups.get(groupId);
        if (g == null || !g.members.remove(c.uuid)) return;
        addons.memberRemoved(g, c.uuid);
        if (g.members.isEmpty()) {
            store.deleteGroup(g.id);
            sendGroups(c);
            return;
        }
        if (c.uuid.equals(g.owner)) g.owner = g.members.iterator().next();
        store.markDirty();
        groupChanged(g, c.uuid);
        groupNotice(g, c.name + " left the group.");
    }

    private void groupRename(Client c, String groupId, String name) {
        if (!c.groupChanges.take()) return;
        Store.Group g = groupId == null ? null : store.groups.get(groupId);
        name = cleanGroupName(name);
        if (g == null || name == null || !g.members.contains(c.uuid)) return;
        g.name = name;
        store.markDirty();
        groupChanged(g, null);
        groupNotice(g, c.name + " renamed the group to " + name + ".");
    }

    private int groupCount(String uuid) {
        int n = 0;
        for (Store.Group g : store.groups.values()) if (g.members.contains(uuid)) n++;
        return n;
    }

    private static String cleanGroupName(String name) {
        if (name == null) return null;
        name = name.strip();
        if (name.isEmpty()) return null;
        return name.length() > 32 ? name.substring(0, 32) : name;
    }

    void groupChanged(Store.Group g, String removed) {
        for (String member : g.members) {
            Client mc = online.get(member);
            if (mc != null) sendGroups(mc);
        }
        if (removed != null) {
            Client rc = online.get(removed);
            if (rc != null) sendGroups(rc);
        }
    }

    void groupNotice(Store.Group g, String text) {
        JsonObject o = msg("group_notice");
        o.addProperty("group", g.id);
        o.addProperty("text", text);
        o.addProperty("ts", System.currentTimeMillis());
        for (String member : g.members) {
            Client mc = online.get(member);
            if (mc != null) send(mc, o);
        }
    }

    void sendGroups(Client c) {
        JsonObject o = msg("groups");
        JsonArray arr = new JsonArray();
        for (Store.Group g : store.groups.values()) {
            if (!g.members.contains(c.uuid)) continue;
            JsonObject gj = new JsonObject();
            gj.addProperty("id", g.id);
            gj.addProperty("name", g.name);
            gj.addProperty("owner", g.owner);
            JsonArray members = new JsonArray();
            g.members.forEach(m -> members.add(profile(m, c.uuid)));
            gj.add("members", members);
            arr.add(gj);
        }
        o.add("groups", arr);
        send(c, o);
    }


    private void callInvite(Client c, String to) {
        if (!c.callInvites.take()) {
            error(c, "Slow down! Try again in a moment.");
            return;
        }
        if (to == null || to.equals(c.uuid) || !(areFriends(c.uuid, to) || shareGroup(c.uuid, to))) {
            error(c, "You can only call friends and group members.");
            return;
        }
        Client target = online.get(to);
        if (target == null || blockedEitherWay(c.uuid, to)) {
            error(c, store.nameOf(to) + " is offline.");
            return;
        }
        if (c.call == null) {
            Call call = new Call(UUID.randomUUID().toString());
            calls.put(call.id, call);
            call.members.add(c);
            c.call = call;
        }
        Call call = c.call;
        if (call.members.contains(target)) {
            error(c, target.name + " is already in the call.");
            return;
        }
        call.invites.put(to, System.currentTimeMillis() + INVITE_TTL_MS);
        broadcastCall(call);
        JsonObject o = msg("call_invite");
        o.addProperty("callId", call.id);
        o.addProperty("from", c.uuid);
        o.addProperty("fromName", c.name);
        o.addProperty("members", call.members.size());
        send(target, o);
    }

    private void callAccept(Client c, String callId) {
        Call call = callId == null ? null : calls.get(callId);
        Long expiry = call == null ? null : call.invites.remove(c.uuid);
        if (expiry == null || expiry < System.currentTimeMillis()) {
            error(c, "That call is no longer available.");
            return;
        }
        if (c.call != null) leaveCall(c, true);
        call.members.add(c);
        c.call = call;
        broadcastCall(call);
    }

    private void callDecline(Client c, String callId) {
        Call call = callId == null ? null : calls.get(callId);
        if (call == null || call.invites.remove(c.uuid) == null) return;
        for (Client m : call.members) info(m, c.name + " declined the call.");
        endIfLonely(call);
        if (calls.containsKey(call.id)) broadcastCall(call);
    }

    void leaveCall(Client c, boolean notifySelf) {
        Call call = c.call;
        if (call == null) return;
        call.members.remove(c);
        c.call = null;
        if (notifySelf) send(c, callJson(null));
        if (call.members.isEmpty()) {
            calls.remove(call.id);
            cancelInvites(call);
            return;
        }
        endIfLonely(call);
        if (calls.containsKey(call.id)) broadcastCall(call);
    }

    private void cancelInvites(Call call) {
        JsonObject o = msg("call_cancel");
        o.addProperty("callId", call.id);
        for (String invitee : call.invites.keySet()) {
            Client ic = online.get(invitee);
            if (ic != null) send(ic, o);
        }
        call.invites.clear();
    }

    private void endIfLonely(Call call) {
        if (call.members.size() > 1 || !call.invites.isEmpty()) return;
        calls.remove(call.id);
        for (Client m : call.members) {
            m.call = null;
            send(m, callJson(null));
            info(m, "Call ended.");
        }
        call.members.clear();
    }

    private void expireInvites() {
        long now = System.currentTimeMillis();
        for (Call call : calls.values().toArray(new Call[0])) {
            boolean changed = false;
            for (Iterator<Map.Entry<String, Long>> it = call.invites.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> e = it.next();
                if (e.getValue() < now) {
                    it.remove();
                    changed = true;
                    for (Client m : call.members) info(m, store.nameOf(e.getKey()) + " didn't answer.");
                }
            }
            if (changed) {
                endIfLonely(call);
                if (calls.containsKey(call.id)) broadcastCall(call);
            }
        }
    }

    private void broadcastCall(Call call) {
        for (Client m : call.members) send(m, callJson(call));
    }

    private JsonObject callJson(Call call) {
        JsonObject o = msg("call");
        if (call == null) {
            o.add("call", null);
            return o;
        }
        JsonObject cj = new JsonObject();
        cj.addProperty("id", call.id);
        JsonArray members = new JsonArray();
        for (Client m : call.members) {
            JsonObject e = new JsonObject();
            e.addProperty("uuid", m.uuid);
            e.addProperty("name", m.name);
            e.addProperty("rank", store.rank(m.uuid));
            e.addProperty("key", store.user(m.uuid).key);
            e.addProperty("muted", m.muted);
            e.addProperty("deafened", m.deafened);
            members.add(e);
        }
        JsonArray ringing = new JsonArray();
        call.invites.keySet().forEach(u -> ringing.add(profile(u, null)));
        cj.add("members", members);
        cj.add("ringing", ringing);
        o.add("call", cj);
        return o;
    }

    private void forwardCallKey(Client c, JsonObject o) {
        String to = str(o, "to");
        String key = str(o, "key");
        Client target = to == null ? null : online.get(to);
        if (c.call == null || target == null || target.call != c.call || key == null || key.length() > MAX_KEY) return;
        JsonObject out = msg("call_key");
        out.addProperty("from", c.uuid);
        out.addProperty("callId", c.call.id);
        out.addProperty("epoch", o.get("epoch").getAsInt());
        out.addProperty("key", key);
        send(target, out);
    }

    private void relayVoice(Client c, byte[] payload) {
        if (!c.authed() || (c.call == null && c.room == null) || c.muted || c.deafened || payload.length < 4) return;
        UUID id = UUID.fromString(c.uuid);
        byte[] frame = ByteBuffer.allocate(16 + payload.length)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).put(payload).array();
        if (c.call == null) {
            addons.roomVoice(c, frame);
            return;
        }
        for (Client m : c.call.members) {
            if (m != c && !m.deafened && m.conn.isOpen()) m.conn.send(frame);
        }
    }


    // Community chat: one public room for everyone on the relay. Plain text, not end-to-end encrypted.

    private static final int MAX_LOBBY_TEXT = 300;

    boolean isStaff(String uuid) {
        String rank = store.rank(uuid);
        return "owner".equals(rank) || "mod".equals(rank);
    }

    private void lobbyJoin(Client c) {
        boolean wasIn = c.inLobby;
        c.inLobby = true;
        Set<String> blocked = store.user(c.uuid).blocked;
        JsonObject o = msg("lobby_history");
        JsonArray arr = new JsonArray();
        var all = store.community.messages;
        for (Store.LobbyMessage m : all.subList(Math.max(0, all.size() - 150), all.size())) {
            if (!blocked.contains(m.from())) arr.add(lobbyJson(m));
        }
        o.add("messages", arr);
        o.addProperty("muted", store.community.muted.contains(c.uuid));
        send(c, o);
        if (!wasIn) broadcastLobbyCount();
        else sendLobbyCount(c);
    }

    private void lobbyLeave(Client c) {
        if (!c.inLobby) return;
        c.inLobby = false;
        broadcastLobbyCount();
    }

    private void lobbyMessage(Client c, String text, JsonObject reply) {
        if (!c.inLobby) return;
        if (store.community.muted.contains(c.uuid)) {
            error(c, "You're muted in the VCraft Community chat.");
            return;
        }
        if (text == null || (text = text.strip()).isEmpty()) return;
        if (text.length() > MAX_LOBBY_TEXT) text = text.substring(0, MAX_LOBBY_TEXT);
        if (!c.lobbyPosts.take()) {
            error(c, "Slow mode: wait a few seconds between messages.");
            return;
        }
        String replyId = null, replyName = null, replyText = null;
        if (reply != null) {
            Store.LobbyMessage target = store.lobbyMessage(str(reply, "id"));
            if (target != null) {
                replyId = target.id();
                replyName = store.nameOf(target.from());
                replyText = target.text().length() > 120 ? target.text().substring(0, 120) : target.text();
            }
        }
        Store.LobbyMessage m = store.addLobbyMessage(new Store.LobbyMessage(newId(), c.uuid, text, replyId, replyName, replyText, System.currentTimeMillis()));
        JsonObject o = lobbyJson(m);
        for (Client member : online.values()) {
            if (member.inLobby && !store.user(member.uuid).blocked.contains(c.uuid)) send(member, o);
        }
    }

    private void lobbyDelete(Client c, String id) {
        Store.LobbyMessage m = id == null ? null : store.lobbyMessage(id);
        if (m == null || !(m.from().equals(c.uuid) || isStaff(c.uuid))) return;
        store.deleteLobbyMessage(id);
        broadcastLobbyDeleted(List.of(id));
    }

    private void lobbyMute(Client c, String target, boolean muted) {
        if (!isStaff(c.uuid) || target == null || store.user(target) == null) return;
        if ("owner".equals(store.rank(target)) || target.equals(c.uuid)) {
            error(c, "You can't mute that person.");
            return;
        }
        setLobbyMuted(target, muted);
        info(c, store.nameOf(target) + (muted ? " is muted in the community chat." : " can talk in the community chat again."));
    }

    private void setLobbyMuted(String uuid, boolean muted) {
        if (muted) store.community.muted.add(uuid);
        else store.community.muted.remove(uuid);
        store.markDirty();
        Client tc = online.get(uuid);
        if (tc != null) info(tc, muted ? "You've been muted in the VCraft Community chat." : "You can talk in the VCraft Community chat again.");
    }

    private JsonObject lobbyJson(Store.LobbyMessage m) {
        JsonObject o = msg("lobby_msg");
        o.addProperty("id", m.id());
        o.addProperty("from", m.from());
        o.addProperty("fromName", store.nameOf(m.from()));
        o.addProperty("fromRank", store.rank(m.from()));
        o.addProperty("text", m.text());
        o.addProperty("ts", m.ts());
        if (m.replyId() != null) {
            JsonObject r = new JsonObject();
            r.addProperty("id", m.replyId());
            r.addProperty("name", m.replyName());
            r.addProperty("text", m.replyText());
            o.add("reply", r);
        }
        return o;
    }

    private void broadcastLobbyDeleted(List<String> ids) {
        for (String id : ids) {
            JsonObject o = msg("lobby_deleted");
            o.addProperty("id", id);
            for (Client member : online.values()) if (member.inLobby) send(member, o);
        }
    }

    private int lobbyCount() {
        int n = 0;
        for (Client member : online.values()) if (member.inLobby) n++;
        return n;
    }

    private void sendLobbyCount(Client c) {
        JsonObject o = msg("lobby_count");
        o.addProperty("online", lobbyCount());
        send(c, o);
    }

    private void broadcastLobbyCount() {
        JsonObject o = msg("lobby_count");
        o.addProperty("online", lobbyCount());
        for (Client member : online.values()) if (member.inLobby) send(member, o);
    }

    private static final Set<String> MOD_COMMANDS = Set.of("stats", "list", "kick", "mute", "unmute", "clear");
    private static final Set<String> TARGETED = Set.of("kick", "ban", "delete", "mute", "clear", "rank");

    /** In-game admin commands: owners can run everything, mods a moderation subset. */
    private void adminCommand(Client c, String cmd, String arg) {
        String rank = store.rank(c.uuid);
        boolean owner = "owner".equals(rank);
        if (!owner && !"mod".equals(rank)) {
            error(c, "Only VCraft staff can use admin commands.");
            return;
        }
        if (cmd == null || cmd.isBlank()) return;
        cmd = cmd.toLowerCase(java.util.Locale.ROOT);
        if (!owner && !MOD_COMMANDS.contains(cmd)) {
            error(c, "Only the owner can use '" + cmd + "'.");
            return;
        }
        String[] parts = arg == null ? new String[0] : arg.trim().split("\\s+");
        String target = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : null;
        if (TARGETED.contains(cmd) && target == null) {
            error(c, "Usage: /vc admin " + cmd + " <player>" + (cmd.equals("rank") ? " <owner|mod|tester|none>" : ""));
            return;
        }
        if (target != null && TARGETED.contains(cmd)) {
            String targetUuid = store.findUuidByName(target);
            if (targetUuid != null && "owner".equals(store.rank(targetUuid))) {
                error(c, "You can't use '" + cmd + "' on an owner from in-game.");
                return;
            }
        }
        String result = switch (cmd) {
            case "stats" -> adminStats();
            case "list" -> adminList();
            case "kick" -> adminKick(target);
            case "ban" -> adminBan(target);
            case "unban" -> target == null ? "Usage: /vc admin unban <player>" : adminUnban(target);
            case "rank" -> parts.length < 2 ? "Usage: /vc admin rank <player> <owner|mod|tester|none>" : adminRank(target, parts[1].toLowerCase(java.util.Locale.ROOT));
            case "mute" -> adminLobbyMute(target, true);
            case "unmute" -> target == null ? "Usage: /vc admin unmute <player>" : adminLobbyMute(target, false);
            case "clear" -> adminLobbyClear(target);
            case "delete" -> adminDelete(target);
            default -> "Unknown command. Try: stats, list, kick, ban, unban, rank, mute, unmute, clear, delete";
        };
        System.out.println("[relay] admin " + c.name + ": " + cmd + (arg == null ? "" : " " + arg) + " -> " + result);
        if (c.conn.isOpen()) {
            JsonObject o = msg("admin_result");
            o.addProperty("text", result);
            send(c, o);
        }
    }

    String adminRank(String name, String rank) {
        String uuid = store.findUuidByName(name);
        if (uuid == null) return "No account named " + name + " on this relay.";
        if (rank == null || rank.equals("none")) rank = null;
        else if (!rank.equals("owner") && !rank.equals("mod") && !rank.equals("tester")) return "Rank must be owner, mod, tester or none.";
        store.setRank(uuid, rank);
        refreshContacts(uuid);
        Client c = online.get(uuid);
        if (c != null) {
            JsonObject o = msg("rank");
            o.addProperty("rank", rank);
            send(c, o);
        }
        return store.nameOf(uuid) + (rank == null ? " no longer has a rank." : " is now " + rank + ".");
    }

    String adminLobbyMute(String name, boolean muted) {
        String uuid = store.findUuidByName(name);
        if (uuid == null) return "No account named " + name + " on this relay.";
        setLobbyMuted(uuid, muted);
        return store.nameOf(uuid) + (muted ? " is muted in the community chat." : " is unmuted.");
    }

    String adminLobbyClear(String name) {
        String uuid = store.findUuidByName(name);
        if (uuid == null) return "No account named " + name + " on this relay.";
        List<String> ids = store.clearLobbyMessagesFrom(uuid);
        broadcastLobbyDeleted(ids);
        return "Removed " + ids.size() + " community messages from " + store.nameOf(uuid) + ".";
    }

    private void deleteAccount(Client c) {
        String uuid = c.uuid;
        Set<String> affected = contacts(uuid);
        leaveCall(c, false);
        List<String> lobbyIds = store.clearLobbyMessagesFrom(uuid);
        if (c.inLobby) {
            c.inLobby = false;
            broadcastLobbyCount();
        }
        store.deleteUser(uuid);
        JsonObject o = msg("account_deleted");
        send(c, o);
        online.remove(uuid);
        c.uuid = null;
        c.conn.close(1000, "Account deleted");
        for (String other : affected) {
            Client oc = online.get(other);
            if (oc == null) continue;
            sendFriends(oc);
            sendGroups(oc);
        }
        broadcastLobbyDeleted(lobbyIds);
        System.out.println("[relay] " + c.name + " deleted their account");
    }

    private void exportData(Client c) {
        Store.User u = store.user(c.uuid);
        JsonObject data = new JsonObject();
        data.addProperty("uuid", c.uuid);
        data.addProperty("name", u.name);
        data.addProperty("status", u.status);
        data.addProperty("publicKey", u.key);
        data.addProperty("lastSeen", u.lastSeen);
        data.add("friends", names(u.friends));
        data.add("incomingRequests", names(u.incoming));
        data.add("outgoingRequests", names(u.outgoing));
        data.add("blocked", names(u.blocked));
        JsonArray groups = new JsonArray();
        for (Store.Group g : store.groups.values()) {
            if (!g.members.contains(c.uuid)) continue;
            JsonObject gj = new JsonObject();
            gj.addProperty("name", g.name);
            gj.addProperty("owner", store.nameOf(g.owner));
            gj.add("members", names(g.members));
            groups.add(gj);
        }
        data.add("groups", groups);
        JsonArray community = new JsonArray();
        for (Store.LobbyMessage m : store.community.messages) {
            if (!m.from().equals(c.uuid)) continue;
            JsonObject mj = new JsonObject();
            mj.addProperty("text", m.text());
            mj.addProperty("ts", m.ts());
            community.add(mj);
        }
        data.add("communityMessages", community);
        data.addProperty("rank", store.rank(c.uuid));
        data.addProperty("note", "Messages and voice are end-to-end encrypted, so the relay cannot read them. "
                + "The relay also stores encrypted message history (up to " + Store.HISTORY_LIMIT + " messages per conversation"
                + (opts.retentionDays > 0 ? ", deleted after " + opts.retentionDays + " days" : "") + ").");
        JsonObject o = msg("export");
        o.add("data", data);
        send(c, o);
    }

    private JsonArray names(Set<String> uuids) {
        JsonArray arr = new JsonArray();
        for (String id : uuids) arr.add(store.nameOf(id) + " (" + id + ")");
        return arr;
    }

    private void housekeeping() {
        if (opts.retentionDays > 0) {
            int removed = store.pruneMessagesBefore(System.currentTimeMillis() - opts.retentionDays * 86_400_000L);
            if (removed > 0) System.out.println("[relay] Deleted " + removed + " messages older than " + opts.retentionDays + " days");
        }
        int orphans = store.pruneOrphans();
        if (orphans > 0) System.out.println("[relay] Pruned " + orphans + " unused accounts");
        long now = System.currentTimeMillis();
        unknownNames.values().removeIf(until -> until < now);
        loginsPerIp.keySet().removeIf(ip -> !connectionsPerIp.containsKey(ip));
    }

    // Admin console commands (run on the logic thread).

    String adminStats() {
        return online.size() + " online, " + connectionCount.get() + " connections, " + calls.size() + " calls, "
                + store.users.size() + " accounts, " + store.groups.size() + " groups, " + store.messageCount() + " stored messages";
    }

    String adminList() {
        if (online.isEmpty()) return "Nobody is online.";
        List<String> names = new ArrayList<>();
        for (Client c : online.values()) names.add(c.name + (c.call != null ? " (in call)" : ""));
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", names);
    }

    String adminKick(String name) {
        String uuid = store.findUuidByName(name);
        Client c = uuid == null ? null : online.get(uuid);
        if (c == null) return name + " isn't online.";
        error(c, "You were disconnected by the relay admin.");
        c.conn.close(1008, "Kicked");
        return "Kicked " + c.name + ".";
    }

    String adminBan(String name) {
        String uuid = store.findUuidByName(name);
        if (uuid == null) return "No account named " + name + " on this relay.";
        store.ban(uuid);
        Client c = online.get(uuid);
        if (c != null) {
            error(c, "You are banned from this relay.");
            c.conn.close(1008, "Banned");
        }
        return "Banned " + store.nameOf(uuid) + ".";
    }

    String adminUnban(String name) {
        String uuid = store.findUuidByName(name);
        if (uuid == null || !store.unban(uuid)) return name + " isn't banned.";
        return "Unbanned " + store.nameOf(uuid) + ".";
    }

    String adminDelete(String name) {
        String uuid = store.findUuidByName(name);
        if (uuid == null) return "No account named " + name + " on this relay.";
        Client c = online.get(uuid);
        if (c != null) {
            deleteAccount(c);
        } else {
            Set<String> affected = contacts(uuid);
            broadcastLobbyDeleted(store.clearLobbyMessagesFrom(uuid));
            store.deleteUser(uuid);
            for (String other : affected) {
                Client oc = online.get(other);
                if (oc == null) continue;
                sendFriends(oc);
                sendGroups(oc);
            }
        }
        return "Deleted all data for " + name + ".";
    }

    void runOnLogic(Runnable task) {
        logic.execute(task);
    }

    private JsonObject profile(String uuid, String viewer) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("name", store.nameOf(uuid));
        Store.User u = store.user(uuid);
        if (u != null && u.key != null) o.addProperty("key", u.key);
        String rank = store.rank(uuid);
        if (rank != null) o.addProperty("rank", rank);
        if (viewer != null) o.addProperty("status", statusFor(uuid, viewer));
        return o;
    }

    String newId() {
        byte[] b = new byte[9];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static JsonObject msg(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    void info(Client c, String text) {
        JsonObject o = msg("info");
        o.addProperty("text", text);
        send(c, o);
    }

    void error(Client c, String text) {
        JsonObject o = msg("error");
        o.addProperty("text", text);
        send(c, o);
    }

    static void send(Client c, JsonObject o) {
        if (c.conn.isOpen()) c.conn.send(o.toString());
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static String dashed(String id) {
        if (id.length() != 32) return id;
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-"
                + id.substring(16, 20) + "-" + id.substring(20);
    }
}
