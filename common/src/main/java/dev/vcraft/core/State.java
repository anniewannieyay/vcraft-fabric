package dev.vcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side view of friends, groups, conversations and the current call. Conversations are keyed by friend uuid or "g:" + group id. */
public final class State {
    public record Profile(String uuid, String name, String status) {
        public boolean online() {
            return !"offline".equals(status);
        }
    }

    public record Group(String id, String name, String owner, List<Profile> members) {
        public String conv() {
            return "g:" + id;
        }
    }

    public record Reply(String id, String name, String text) {}

    public record ServerInvite(String name, String address) {}

    public record Message(String id, String from, String fromName, String text, long ts,
                          boolean secure, Reply reply, ServerInvite invite, boolean system, String song) {}

    public record CallMember(String uuid, String name, boolean muted, boolean deafened) {}

    public record Call(String id, List<CallMember> members, List<Profile> ringing) {}

    public record Invite(String callId, String from, String fromName, long expires) {}

    public static final List<String> STATUSES = List.of("online", "idle", "dnd", "invisible");
    public static final String LOBBY = "lobby";

    public static String myUuid;
    public static String myName;
    public static String myStatus = "online";
    public static String myRank;
    public static final Map<String, String> ranks = new HashMap<>();
    public static int lobbyOnline;
    public static boolean lobbyMuted;
    public static List<Profile> friends = List.of();
    public static List<Profile> incoming = List.of();
    public static List<Profile> outgoing = List.of();
    public static List<Profile> blocked = List.of();
    public static List<Group> groups = List.of();
    public static final Map<String, List<Message>> conversations = new HashMap<>();
    public static final Map<String, Integer> unread = new HashMap<>();
    public static final Map<String, Invite> invites = new LinkedHashMap<>();
    /** What each friend is listening to, from their Now Playing mod. */
    public static final Map<String, String> listening = new ConcurrentHashMap<>();
    private static String sharedSong;
    private static boolean listeningSynced;
    private static long nextListeningCheck;
    public static volatile Call call;
    /** An addon voice room (members other than you hear you); paused while in a call. */
    public record Room(String key, String id, List<CallMember> members) {}

    public static volatile Room room;
    private static String wantedRoom;
    public static String viewing;
    public static String focusRequest;
    public static final Map<String, Long> lastSpoke = new ConcurrentHashMap<>();

    public static volatile boolean muted;
    public static volatile boolean deafened;

    public static String notice;
    public static boolean noticeIsError;
    public static long noticeTime;

    private static boolean ringing;
    private static int revision;

    public static int revision() {
        return revision;
    }

    public static void changed() {
        revision++;
    }

    public static void reset() {
        myUuid = null;
        myName = null;
        friends = List.of();
        incoming = List.of();
        outgoing = List.of();
        blocked = List.of();
        groups = List.of();
        conversations.clear();
        unread.clear();
        invites.clear();
        ranks.clear();
        listening.clear();
        listeningSynced = false;
        myRank = null;
        lobbyOnline = 0;
        setCall(null);
        Secure.onCall(null);
        room = null;
        Secure.onRoom(null);
        updateVoice();
        updateRinging();
        revision++;
    }

    static void handle(JsonObject o) {
        switch (o.get("t").getAsString()) {
            case "welcome" -> {
                myUuid = str(o, "uuid");
                myName = str(o, "name");
                myStatus = str(o, "status");
                Secure.observe(myUuid, Secure.publicKey());
                myRank = str(o, "rank");
                setRank(myUuid, myRank);
                if (LOBBY.equals(viewing)) send("lobby_join", null, null);
                sendVoiceState();
                listeningSynced = false;
                if (wantedRoom != null) send("room.join", "key", wantedRoom);
                AddonHost.INSTANCE.fireConnected();
            }
            case "status" -> myStatus = str(o, "status");
            case "rank" -> {
                myRank = str(o, "rank");
                setRank(myUuid, myRank);
            }
            case "lobby_history" -> {
                List<Message> list = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("messages")) {
                    Message m = AddonHost.INSTANCE.filter(LOBBY, lobbyMessage(e.getAsJsonObject()));
                    if (m != null) list.add(m);
                }
                conversations.put(LOBBY, list);
                lobbyMuted = bool(o, "muted");
            }
            case "lobby_msg" -> {
                Message m = AddonHost.INSTANCE.filter(LOBBY, lobbyMessage(o));
                if (m == null) return;
                AddonHost.INSTANCE.fireMessage(LOBBY, m);
                conversations.computeIfAbsent(LOBBY, k -> new ArrayList<>()).add(m);
                List<Message> list = conversations.get(LOBBY);
                if (list.size() > 300) list.subList(0, list.size() - 300).clear();
                if (!m.from().equals(myUuid) && !LOBBY.equals(viewing)) unread.merge(LOBBY, 1, Integer::sum);
            }
            case "lobby_deleted" -> {
                String id = str(o, "id");
                List<Message> list = conversations.get(LOBBY);
                if (list != null) list.removeIf(m -> id.equals(m.id()));
            }
            case "lobby_count" -> lobbyOnline = o.get("online").getAsInt();
            case "friends" -> {
                friends = sorted(profiles(o.getAsJsonArray("friends")));
                incoming = profiles(o.getAsJsonArray("incoming"));
                outgoing = profiles(o.getAsJsonArray("outgoing"));
                blocked = profiles(o.getAsJsonArray("blocked"));
                if (!listeningSynced) {
                    listeningSynced = true;
                    sharedSong = sharedSong();
                    if (sharedSong != null) sendListening(onlineFriends());
                }
            }
            case "groups" -> {
                List<Group> list = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("groups")) {
                    JsonObject g = e.getAsJsonObject();
                    list.add(new Group(str(g, "id"), str(g, "name"), str(g, "owner"), profiles(g.getAsJsonArray("members"))));
                }
                list.sort(Comparator.comparing(g -> g.name().toLowerCase()));
                groups = list;
            }
            case "presence" -> updatePresence(str(o, "uuid"), str(o, "status"));
            case "activity" -> {
                String from = str(o, "from");
                Secure.Opened opened = Secure.open(from, Secure.activityAad(from, myUuid), str(o, "body"));
                if (!opened.ok() || friend(from) == null) return;
                String song = NowPlaying.clean(str(JsonParser.parseString(opened.json()).getAsJsonObject(), "song"));
                if (song == null) listening.remove(from);
                else listening.put(from, song);
            }
            case "friend_request" -> notifyUser("Friend request", str(o, "name") + " wants to be friends", null);
            case "friend_added" -> notifyUser("New friend", str(o, "name") + " accepted your request", null);
            case "dm" -> {
                String from = str(o, "from");
                String to = str(o, "to");
                String other = from.equals(myUuid) ? to : from;
                Message m = decode(o, Secure.open(other, Secure.dmAad(from, to), str(o, "text")));
                add(other, m);
            }
            case "gmsg" -> {
                String group = str(o, "group");
                String from = str(o, "from");
                Message m = decode(o, Secure.open(from, Secure.groupAad(group, from, myUuid), str(o, "text")));
                add("g:" + group, m);
            }
            case "history" -> {
                String group = str(o, "group");
                String conv = group != null ? "g:" + group : str(o, "with");
                List<Message> list = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("messages")) {
                    JsonObject m = e.getAsJsonObject();
                    String from = str(m, "from");
                    Secure.Opened opened = group != null
                            ? Secure.open(from, Secure.groupAad(group, from, myUuid), str(m, "text"))
                            : Secure.open(from.equals(myUuid) ? str(m, "to") : from, Secure.dmAad(from, str(m, "to")), str(m, "text"));
                    Message msg = AddonHost.INSTANCE.filter(conv, decode(m, opened));
                    if (msg != null) list.add(msg);
                }
                for (Message m : conversations.getOrDefault(conv, List.of())) if (m.system()) list.add(m);
                list.sort(Comparator.comparingLong(Message::ts));
                conversations.put(conv, list);
            }
            case "deleted" -> {
                String group = str(o, "group");
                String conv = group != null ? "g:" + group : str(o, "with");
                String id = str(o, "id");
                List<Message> list = conversations.get(conv);
                if (list != null) list.removeIf(m -> id.equals(m.id()));
            }
            case "unread" -> {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("counts").entrySet()) {
                    unread.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum);
                }
                int total = unread.values().stream().mapToInt(Integer::intValue).sum();
                if (total > 0) notifyUser("VCraft", total + " unread message" + (total == 1 ? "" : "s"), null);
            }
            case "group_notice" -> {
                String conv = "g:" + str(o, "group");
                conversations.computeIfAbsent(conv, k -> new ArrayList<>())
                        .add(new Message(null, null, null, str(o, "text"), o.get("ts").getAsLong(), true, null, null, true, null));
            }
            case "group_created" -> focusRequest = "g:" + str(o, "group");
            case "call_invite" -> {
                Invite inv = new Invite(str(o, "callId"), str(o, "from"), str(o, "fromName"), System.currentTimeMillis() + 60_000);
                if (isBlocked(inv.from())) break;
                invites.put(inv.callId(), inv);
                if (!quiet(inv.from())) {
                    VCraft.platform().toast("Incoming call", inv.fromName() + " is calling you");
                    VCraft.platform().chat("Call", 0xFF55FF55, inv.fromName() + " is calling you. Type /vc accept or open VCraft.");
                }
            }
            case "call" -> {
                JsonElement c = o.get("call");
                if (c == null || c.isJsonNull()) {
                    setCall(null);
                    Secure.onCall(null);
                } else {
                    JsonObject j = c.getAsJsonObject();
                    List<CallMember> members = new ArrayList<>();
                    for (JsonElement e : j.getAsJsonArray("members")) {
                        JsonObject m = e.getAsJsonObject();
                        setRank(str(m, "uuid"), str(m, "rank"));
                        Secure.observe(str(m, "uuid"), str(m, "key"));
                        members.add(new CallMember(str(m, "uuid"), str(m, "name"), bool(m, "muted"), bool(m, "deafened")));
                    }
                    invites.remove(str(j, "id"));
                    setCall(new Call(str(j, "id"), members, profiles(j.getAsJsonArray("ringing"))));
                    Secure.onCall(call);
                }
            }
            case "export" -> saveExport(o.getAsJsonObject("data"));
            case "account_deleted" -> {
                VCraft.config().autoConnect = false;
                VCraft.config().save();
                notice("Your VCraft account and its data were deleted from the relay.", false);
            }
            case "admin_result" -> {
                VCraft.platform().chat("Admin", 0xFFF0B232, str(o, "text"));
                notice(str(o, "text"), false);
            }
            case "call_rekey" -> Secure.rekey(call, false);
            case "call_cancel" -> invites.remove(str(o, "callId"));
            case "call_key" -> Secure.onCallKey(str(o, "from"), str(o, "callId"), o.get("epoch").getAsInt(), str(o, "key"));
            case "room.state" -> {
                JsonElement r = o.get("room");
                if (r == null || r.isJsonNull()) {
                    room = null;
                } else {
                    JsonObject j = r.getAsJsonObject();
                    List<CallMember> members = new ArrayList<>();
                    for (JsonElement e : j.getAsJsonArray("members")) {
                        JsonObject m = e.getAsJsonObject();
                        Secure.observe(str(m, "uuid"), str(m, "key"));
                        members.add(new CallMember(str(m, "uuid"), str(m, "name"), bool(m, "muted"), bool(m, "deafened")));
                    }
                    room = new Room(str(j, "key"), str(j, "id"), members);
                }
                Secure.onRoom(room);
                updateVoice();
            }
            case "room.key" -> Secure.onRoomKey(str(o, "from"), str(o, "room"), o.get("epoch").getAsInt(), str(o, "key"));
            case "room.rekey" -> Secure.rekeyRoom(false);
            case "info" -> notice(str(o, "text"), false);
            case "error" -> {
                notice(str(o, "text"), true);
                VCraft.platform().toast("VCraft", str(o, "text"));
            }
            default -> AddonHost.INSTANCE.dispatch(o.get("t").getAsString(), o);
        }
        updateRinging();
        revision++;
    }

    private static void add(String conv, Message m) {
        m = AddonHost.INSTANCE.filter(conv, m);
        if (m == null) return;
        AddonHost.INSTANCE.fireMessage(conv, m);
        conversations.computeIfAbsent(conv, k -> new ArrayList<>()).add(m);
        if (m.from().equals(myUuid) || conv.equals(viewing)) return;
        unread.merge(conv, 1, Integer::sum);
        String where = conv.startsWith("g:") ? convName(conv) : null;
        String preview = m.invite() != null ? "invited you to " + m.invite().name() : m.song() != null ? "is listening to " + m.song() : m.text();
        notifyUser(where == null ? m.fromName() : m.fromName() + " in " + where, preview, m.from());
        if (!quiet(m.from())) {
            VCraft.platform().chat(where == null ? "DM" : where, 0xFFFF77FF, m.fromName() + ": " + preview);
            if (VCraft.config().messageSound) VCraft.platform().playDing();
        }
    }

    private static Message decode(JsonObject o, Secure.Opened opened) {
        if (o.has("fromRank")) setRank(str(o, "from"), str(o, "fromRank"));
        String text;
        Reply reply = null;
        ServerInvite invite = null;
        String song = null;
        if (!opened.ok()) {
            text = "This message couldn't be decrypted.";
        } else {
            JsonObject env = JsonParser.parseString(opened.json()).getAsJsonObject();
            text = str(env, "text");
            if (env.has("reply")) {
                JsonObject r = env.getAsJsonObject("reply");
                reply = new Reply(str(r, "id"), str(r, "name"), str(r, "text"));
            }
            if (env.has("invite")) {
                JsonObject s = env.getAsJsonObject("invite");
                invite = new ServerInvite(str(s, "name"), str(s, "address"));
            }
            if (env.has("song")) song = NowPlaying.clean(str(env, "song"));
        }
        return new Message(str(o, "id"), str(o, "from"), str(o, "fromName"), text == null ? "" : text,
                o.get("ts").getAsLong(), opened.ok(), reply, invite, false, song);
    }

    private static void updatePresence(String uuid, String status) {
        Profile before = friend(uuid);
        if ("offline".equals(status)) listening.remove(uuid);
        else if (before != null && !before.online() && sharedSong != null) sendListening(List.of(uuid));
        friends = sorted(friends.stream().map(p -> p.uuid().equals(uuid) ? new Profile(uuid, p.name(), status) : p).toList());
        List<Group> list = new ArrayList<>();
        for (Group g : groups) {
            list.add(new Group(g.id(), g.name(), g.owner(),
                    g.members().stream().map(p -> p.uuid().equals(uuid) ? new Profile(uuid, p.name(), status) : p).toList()));
        }
        groups = list;
    }

    private static void setCall(Call newCall) {
        Call before = call;
        call = newCall;
        updateVoice();
        AddonHost.INSTANCE.fireCall(before, newCall);
        // Back in the room after a call: everyone there may have rotated keys meanwhile.
        if (before != null && newCall == null && room != null) {
            Secure.rekeyRoom(true);
            send("room.rekey", null, null);
        }
    }

    /** Whether the mic should be sent anywhere: a call, or a voice room with someone else in it. */
    public static boolean voiceActive() {
        Room r = room;
        return call != null || (r != null && r.members().size() > 1);
    }

    private static void updateVoice() {
        if (voiceActive()) VCraft.voice().start();
        else VCraft.voice().stop();
    }

    /**
     * Accepts someone's changed key. Their voice key was rejected while it didn't match, so ask the call or
     * room to send fresh keys again.
     */
    public static void trustNewKey(String uuid) {
        Secure.acceptPresentedKey(uuid);
        if (call != null && call.members().stream().anyMatch(m -> m.uuid().equals(uuid))) send("call_rekey", null, null);
        if (room != null && room.members().stream().anyMatch(m -> m.uuid().equals(uuid))) send("room.rekey", null, null);
        revision++;
    }

    public static void joinRoom(String key) {
        if (key.equals(wantedRoom)) return;
        wantedRoom = key;
        send("room.join", "key", key);
    }

    public static void leaveRoom() {
        if (wantedRoom == null && room == null) return;
        wantedRoom = null;
        send("room.leave", null, null);
        room = null;
        Secure.onRoom(null);
        updateVoice();
        revision++;
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (invites.values().removeIf(i -> i.expires() < now)) revision++;
        updateRinging();
        AddonHost.INSTANCE.tick();
        if (now >= nextListeningCheck && listeningSynced && VCraft.relay().connected()) {
            nextListeningCheck = now + 5000;
            String song = sharedSong();
            if (!Objects.equals(song, sharedSong)) {
                sharedSong = song;
                sendListening(onlineFriends());
            }
        }
    }

    /** The song to show friends, or null when nothing's playing or sharing is off (here, in Now Playing, or while invisible). */
    private static String sharedSong() {
        if (!VCraft.config().shareListening || "invisible".equals(myStatus) || !NowPlaying.sharingEnabled()) return null;
        return NowPlaying.current();
    }

    private static List<String> onlineFriends() {
        return friends.stream().filter(Profile::online).map(Profile::uuid).toList();
    }

    private static void sendListening(List<String> to) {
        JsonObject env = new JsonObject();
        if (sharedSong != null) env.addProperty("song", sharedSong);
        JsonObject payloads = new JsonObject();
        for (String uuid : to) {
            String sealed = Secure.seal(uuid, Secure.activityAad(myUuid, uuid), env.toString());
            if (sealed != null) payloads.addProperty(uuid, sealed);
        }
        if (payloads.isEmpty()) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "activity");
        o.add("payloads", payloads);
        VCraft.relay().send(o);
    }

    private static void updateRinging() {
        boolean shouldRing = false;
        for (Invite i : invites.values()) {
            if (!quiet(i.from())) shouldRing = true;
        }
        if (shouldRing == ringing) return;
        ringing = shouldRing;
        if (shouldRing && !"none".equals(VCraft.config().ringtone)) VCraft.platform().startRingtone(VCraft.config().ringtone);
        else VCraft.platform().stopRingtone();
    }

    private static boolean quiet(String from) {
        return "dnd".equals(myStatus) || (from != null && VCraft.config().mutedUsers.contains(from));
    }


    public static void send(String type, String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        if (key != null) o.addProperty(key, value);
        VCraft.relay().send(o);
    }

    /** Returns false if an addon's rule blocked the message. */
    public static boolean sendMessage(String conv, String text, Reply reply, ServerInvite invite) {
        return sendMessage(conv, text, reply, invite, null);
    }

    /** Shares the song Now Playing detected as a message. */
    public static void sendSong(String conv) {
        String song = NowPlaying.current();
        if (song == null) {
            notice(NowPlaying.installed() ? "Nothing is playing right now." : "Install the Now Playing mod to share songs.", true);
            return;
        }
        sendMessage(conv, "♪ Listening to " + song, null, null, song);
    }

    private static boolean sendMessage(String conv, String text, Reply reply, ServerInvite invite, String song) {
        String blocked = AddonHost.INSTANCE.checkSend(conv, text);
        if (blocked != null) {
            notice(blocked, true);
            return false;
        }
        if (LOBBY.equals(conv)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "lobby_msg");
            o.addProperty("text", text);
            if (reply != null) {
                JsonObject r = new JsonObject();
                r.addProperty("id", reply.id());
                o.add("reply", r);
            }
            VCraft.relay().send(o);
            return true;
        }
        JsonObject env = new JsonObject();
        env.addProperty("text", text);
        if (reply != null) {
            JsonObject r = new JsonObject();
            r.addProperty("id", reply.id());
            r.addProperty("name", reply.name());
            r.addProperty("text", reply.text().length() > 120 ? reply.text().substring(0, 120) : reply.text());
            env.add("reply", r);
        }
        if (invite != null) {
            JsonObject s = new JsonObject();
            s.addProperty("name", invite.name());
            s.addProperty("address", invite.address());
            env.add("invite", s);
        }
        if (song != null) env.addProperty("song", song);
        String plain = env.toString();
        JsonObject o = new JsonObject();
        if (conv.startsWith("g:")) {
            Group g = group(conv);
            if (g == null) return false;
            JsonObject payloads = new JsonObject();
            List<String> missed = new ArrayList<>();
            for (Profile p : g.members()) {
                String sealed = Secure.seal(p.uuid(), Secure.groupAad(g.id(), myUuid, p.uuid()), plain);
                if (sealed != null) payloads.addProperty(p.uuid(), sealed);
                else if (!p.uuid().equals(myUuid)) missed.add(p.name());
            }
            if (!missed.isEmpty()) notice("Not sent to " + String.join(", ", missed) + " (encryption key not trusted).", true);
            o.addProperty("t", "gmsg");
            o.addProperty("group", g.id());
            o.add("payloads", payloads);
        } else {
            String sealed = Secure.seal(conv, Secure.dmAad(myUuid, conv), plain);
            if (sealed == null) {
                notice(Secure.trust(conv) == Secure.Trust.CHANGED
                        ? "Not sent: their encryption key changed. Check the safety number, then Trust."
                        : "Not sent: no encryption key for them yet.", true);
                return false;
            }
            o.addProperty("t", "dm");
            o.addProperty("to", conv);
            o.addProperty("text", sealed);
        }
        VCraft.relay().send(o);
        return true;
    }

    public static void exportData() {
        send("export_data", null, null);
    }

    public static void deleteAccount() {
        send("delete_account", "confirm", "DELETE");
    }

    private static void saveExport(JsonObject data) {
        try {
            java.nio.file.Path dir = VCraft.platform().configDir().resolve("vcraft");
            java.nio.file.Files.createDirectories(dir);
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            java.nio.file.Path file = dir.resolve("data-export-" + stamp + ".json");
            java.nio.file.Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data));
            notice("Saved your data to config/vcraft/" + file.getFileName(), false);
        } catch (Exception e) {
            notice("Couldn't save your data export: " + e.getMessage(), true);
        }
    }

    private static Message lobbyMessage(JsonObject o) {
        setRank(str(o, "from"), str(o, "fromRank"));
        Reply reply = null;
        if (o.has("reply") && o.get("reply").isJsonObject()) {
            JsonObject r = o.getAsJsonObject("reply");
            reply = new Reply(str(r, "id"), str(r, "name"), str(r, "text"));
        }
        return new Message(str(o, "id"), str(o, "from"), str(o, "fromName"), str(o, "text"), o.get("ts").getAsLong(), true, reply, null, false, null);
    }

    private static void setRank(String uuid, String rank) {
        if (uuid == null) return;
        if (rank == null || rank.isEmpty()) ranks.remove(uuid);
        else ranks.put(uuid, rank);
    }

    public static String rank(String uuid) {
        return uuid == null ? null : ranks.get(uuid);
    }

    public static boolean isStaff() {
        return "owner".equals(myRank) || "mod".equals(myRank);
    }

    public static void admin(String cmd, String arg) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "admin");
        o.addProperty("cmd", cmd);
        if (arg != null) o.addProperty("arg", arg);
        VCraft.relay().send(o);
    }

    public static void lobbyMute(String uuid, boolean mute) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "lobby_mute");
        o.addProperty("uuid", uuid);
        o.addProperty("muted", mute);
        VCraft.relay().send(o);
    }

    public static void deleteMessage(String conv, String id) {
        if (LOBBY.equals(conv)) {
            send("lobby_delete", "id", id);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("t", "delete");
        o.addProperty("id", id);
        if (conv.startsWith("g:")) o.addProperty("group", conv.substring(2));
        else o.addProperty("with", conv);
        VCraft.relay().send(o);
    }

    public static boolean inviteToServer(String conv) {
        Platform.ServerInfo server = VCraft.platform().currentServer();
        if (server == null) {
            notice("Join a multiplayer server first, then invite people to it.", true);
            return false;
        }
        sendMessage(conv, "Come play with me on " + server.name() + "!", null, new ServerInvite(server.name(), server.address()));
        return true;
    }

    public static void openConversation(String conv) {
        viewing = conv;
        if (conv == null) return;
        unread.remove(conv);
        JsonObject o = new JsonObject();
        o.addProperty("t", "history");
        if (LOBBY.equals(conv)) {
            send("lobby_join", null, null);
            revision++;
            return;
        }
        if (conv.startsWith("g:")) o.addProperty("group", conv.substring(2));
        else o.addProperty("with", conv);
        VCraft.relay().send(o);
        revision++;
    }

    public static void setStatus(String status) {
        myStatus = status;
        VCraft.config().status = status;
        VCraft.config().save();
        send("status", "status", status);
        updateRinging();
        revision++;
    }

    public static void createGroup(String name, List<String> members) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "group_create");
        o.addProperty("name", name);
        JsonArray arr = new JsonArray();
        members.forEach(arr::add);
        o.add("members", arr);
        VCraft.relay().send(o);
    }

    public static void groupAction(String type, String groupId, String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        o.addProperty("group", groupId);
        if (key != null) o.addProperty(key, value);
        VCraft.relay().send(o);
    }

    public static void call(String uuid) {
        send("call_invite", "to", uuid);
    }

    public static void callGroup(Group g) {
        int rung = 0;
        for (Profile p : g.members()) {
            if (p.uuid().equals(myUuid) || !p.online() || inCall(p.uuid())) continue;
            call(p.uuid());
            rung++;
        }
        if (rung == 0) notice("Nobody else in " + g.name() + " is online.", true);
    }

    public static boolean inCall(String uuid) {
        Call c = call;
        if (c == null) return false;
        for (CallMember m : c.members()) if (m.uuid().equals(uuid)) return true;
        for (Profile p : c.ringing()) if (p.uuid().equals(uuid)) return true;
        return false;
    }

    public static void acceptInvite(Invite inv) {
        send("call_accept", "callId", inv.callId());
        invites.remove(inv.callId());
        updateRinging();
        revision++;
    }

    public static void declineInvite(Invite inv) {
        send("call_decline", "callId", inv.callId());
        invites.remove(inv.callId());
        updateRinging();
        revision++;
    }

    public static Invite latestInvite() {
        Invite latest = null;
        for (Invite inv : invites.values()) latest = inv;
        return latest;
    }

    // Restarts audio and swaps fresh voice keys with everyone, for when a call sounds broken.
    public static void reconnectVoice() {
        Call c = call;
        if (c == null && room == null) return;
        VCraft.voice().stop();
        VCraft.voice().start();
        if (c != null) {
            Secure.rekey(c, true);
            send("call_rekey", null, null);
        } else {
            Secure.rekeyRoom(true);
            send("room.rekey", null, null);
        }
        sendVoiceState();
        notice("Reconnecting voice...", false);
    }

    public static void toggleMute() {
        if (deafened) {
            deafened = false;
            muted = false;
        } else {
            muted = !muted;
        }
        sendVoiceState();
        revision++;
    }

    public static void toggleDeafen() {
        deafened = !deafened;
        sendVoiceState();
        revision++;
    }

    private static void sendVoiceState() {
        JsonObject o = new JsonObject();
        o.addProperty("t", "state");
        o.addProperty("muted", muted || deafened);
        o.addProperty("deafened", deafened);
        VCraft.relay().send(o);
    }

    public static void toggleUserMute(String uuid) {
        var set = VCraft.config().mutedUsers;
        if (!set.remove(uuid)) set.add(uuid);
        VCraft.config().save();
        updateRinging();
        revision++;
    }


    public static Profile friend(String uuid) {
        for (Profile f : friends) if (f.uuid().equals(uuid)) return f;
        return null;
    }

    public static Profile friendByName(String name) {
        for (Profile f : friends) if (f.name().equalsIgnoreCase(name)) return f;
        return null;
    }

    public static Group group(String conv) {
        if (conv == null || !conv.startsWith("g:")) return null;
        String id = conv.substring(2);
        for (Group g : groups) if (g.id().equals(id)) return g;
        return null;
    }

    public static boolean isBlocked(String uuid) {
        for (Profile p : blocked) if (p.uuid().equals(uuid)) return true;
        return false;
    }

    public static String convName(String conv) {
        if (LOBBY.equals(conv)) return "VCraft Community";
        Group g = group(conv);
        if (g != null) return g.name();
        Profile f = friend(conv);
        return f == null ? "Unknown" : f.name();
    }

    public static boolean conversationExists(String conv) {
        return conv != null && (LOBBY.equals(conv) || (conv.startsWith("g:") ? group(conv) != null : friend(conv) != null));
    }

    public static boolean isSpeaking(String uuid) {
        Long t = lastSpoke.get(uuid);
        return t != null && System.currentTimeMillis() - t < 300;
    }

    public static String statusLabel(String status) {
        return switch (status == null ? "offline" : status) {
            case "online" -> "Online";
            case "idle" -> "Idle";
            case "dnd" -> "Do Not Disturb";
            case "invisible" -> "Offline (invisible)";
            default -> "Offline";
        };
    }

    public static int statusColor(String status) {
        return switch (status == null ? "offline" : status) {
            case "online" -> 0xFF3BA55C;
            case "idle" -> 0xFFFAA61A;
            case "dnd" -> 0xFFED4245;
            default -> 0xFF747F8D;
        };
    }

    public static void notice(String text, boolean error) {
        notice = text;
        noticeIsError = error;
        noticeTime = System.currentTimeMillis();
        revision++;
    }

    private static void notifyUser(String title, String body, String from) {
        if (quiet(from) || !VCraft.config().notifications) return;
        VCraft.platform().toast(title, body);
    }

    private static List<Profile> sorted(List<Profile> list) {
        List<Profile> out = new ArrayList<>(list);
        out.sort(Comparator.comparing((Profile p) -> !p.online()).thenComparing(p -> p.name().toLowerCase()));
        return out;
    }

    private static List<Profile> profiles(JsonArray arr) {
        List<Profile> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement e : arr) {
            JsonObject j = e.getAsJsonObject();
            setRank(str(j, "uuid"), str(j, "rank"));
            Secure.observe(str(j, "uuid"), str(j, "key"));
            out.add(new Profile(str(j, "uuid"), str(j, "name"), j.has("status") ? str(j, "status") : "offline"));
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private State() {
    }
}
