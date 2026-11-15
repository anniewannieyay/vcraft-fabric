package dev.vcraft.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public final class State {
   public static final List<String> STATUSES = List.of("online", "idle", "dnd", "invisible");
   public static final String LOBBY = "lobby";
   public static String myUuid;
   public static String myName;
   public static String myStatus = "online";
   public static String myRank;
   public static final Map<String, String> ranks = new HashMap<>();
   public static int lobbyOnline;
   public static boolean lobbyMuted;
   public static List<State.Profile> friends = List.of();
   public static List<State.Profile> incoming = List.of();
   public static List<State.Profile> outgoing = List.of();
   public static List<State.Profile> blocked = List.of();
   public static List<State.Group> groups = List.of();
   public static final Map<String, List<State.Message>> conversations = new HashMap<>();
   public static final Map<String, Integer> unread = new HashMap<>();
   public static final Map<String, State.Invite> invites = new LinkedHashMap<>();
   public static final Map<String, String> listening = new ConcurrentHashMap<>();
   private static String sharedSong;
   private static boolean listeningSynced;
   private static long nextListeningCheck;
   public static volatile State.Call call;
   public static volatile State.Room room;
   private static String wantedRoom;
   public static String viewing;
   public static String focusRequest;
   public static final Map<String, Long> lastSpoke = new ConcurrentHashMap<>();
   public static volatile boolean muted;
   public static volatile boolean deafened;
   /** Muted by the pause menu or the window losing focus, not by the player (2.5.0). */
   public static volatile boolean autoMuted;
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
      Social.prepareRejoin();
      Social.resetConnection();
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
      String var1 = o.get("t").getAsString();
      switch (var1) {
         case "welcome":
            myUuid = str(o, "uuid");
            myName = str(o, "name");
            myStatus = str(o, "status");
            Secure.observe(myUuid, Secure.publicKey());
            myRank = str(o, "rank");
            setRank(myUuid, myRank);
            if ("lobby".equals(viewing)) {
               send("lobby_join", null, null);
            }

            sendVoiceState();
            listeningSynced = false;
            if (wantedRoom != null) {
               send("room.join", "key", wantedRoom);
            }

            AddonHost.INSTANCE.fireConnected();
            break;
         case "status":
            myStatus = str(o, "status");
            break;
         case "rank":
            myRank = str(o, "rank");
            setRank(myUuid, myRank);
            break;
         case "lobby_history": {
            List<State.Message> listxxx = new ArrayList<>();

            for (JsonElement ex : o.getAsJsonArray("messages")) {
               State.Message mx = AddonHost.INSTANCE.filter("lobby", lobbyMessage(ex.getAsJsonObject()));
               if (mx != null) {
                  listxxx.add(mx);
               }
            }

            conversations.put("lobby", listxxx);
            lobbyMuted = bool(o, "muted");
            break;
         }
         case "lobby_msg": {
            State.Message m = AddonHost.INSTANCE.filter("lobby", lobbyMessage(o));
            if (m == null) {
               return;
            }

            AddonHost.INSTANCE.fireMessage("lobby", m);
            conversations.computeIfAbsent("lobby", k -> new ArrayList<>()).add(m);
            List<State.Message> listxx = conversations.get("lobby");
            if (listxx.size() > 300) {
               listxx.subList(0, listxx.size() - 300).clear();
            }

            if (!m.from().equals(myUuid) && !"lobby".equals(viewing)) {
               unread.merge("lobby", 1, Integer::sum);
            }
            break;
         }
         case "lobby_deleted": {
            String idx = str(o, "id");
            List<State.Message> listx = conversations.get("lobby");
            if (listx != null) {
               listx.removeIf(mx -> Objects.equals(idx, mx.id()));
            }
            break;
         }
         case "lobby_count":
            lobbyOnline = o.get("online").getAsInt();
            break;
         case "friends": {
            List<State.Profile> next = sorted(profiles(o.getAsJsonArray("friends")));
            for (State.Profile n : next) {
               State.Profile before = friend(n.uuid());
               if (!n.online() && before != null && before.online()) {
                  Social.notePresence(n.uuid(), true, "offline");
               }
            }

            friends = next;
            incoming = profiles(o.getAsJsonArray("incoming"));
            outgoing = profiles(o.getAsJsonArray("outgoing"));
            blocked = profiles(o.getAsJsonArray("blocked"));
            if (!listeningSynced) {
               listeningSynced = true;
               sharedSong = sharedSong();
               Social.changed();
               sendListening(onlineFriends());
            }
            break;
         }
         case "groups": {
            List<State.Group> listxxx = new ArrayList<>();

            for (JsonElement ex : o.getAsJsonArray("groups")) {
               JsonObject g = ex.getAsJsonObject();
               listxxx.add(new State.Group(str(g, "id"), str(g, "name"), str(g, "owner"), profiles(g.getAsJsonArray("members"))));
            }

            listxxx.sort(Comparator.comparing(g -> g.name().toLowerCase()));
            groups = listxxx;
            break;
         }
         case "presence":
            updatePresence(str(o, "uuid"), str(o, "status"));
            break;
         case "activity": {
            String from = str(o, "from");
            Secure.Opened opened = Secure.open(from, Secure.activityAad(from, myUuid), str(o, "body"));
            if (!opened.ok() || friend(from) == null) {
               return;
            }

            JsonObject env = JsonParser.parseString(opened.json()).getAsJsonObject();
            String song = NowPlaying.clean(str(env, "song"));
            if (song == null) {
               listening.remove(from);
            } else {
               listening.put(from, song);
            }

            Social.onActivity(from, env);
            break;
         }
         case "friend_request":
            notifyUser("Friend request", str(o, "name") + " wants to be friends", null);
            break;
         case "friend_added":
            notifyUser("New friend", str(o, "name") + " accepted your request", null);
            break;
         case "dm": {
            String from = str(o, "from");
            String to = str(o, "to");
            String other = from.equals(myUuid) ? to : from;
            State.Message m = decode(o, Secure.open(other, Secure.dmAad(from, to), str(o, "text")));
            add(other, m);
            break;
         }
         case "gmsg": {
            String group = str(o, "group");
            String from = str(o, "from");
            State.Message m = decode(o, Secure.open(from, Secure.groupAad(group, from, myUuid), str(o, "text")));
            add("g:" + group, m);
            break;
         }
         case "history": {
            String group = str(o, "group");
            String conv = group != null ? "g:" + group : str(o, "with");
            List<State.Message> listh = new ArrayList<>();

            for (JsonElement e : o.getAsJsonArray("messages")) {
               JsonObject mx = e.getAsJsonObject();
               String fromx = str(mx, "from");
               Secure.Opened opened = group != null
                  ? Secure.open(fromx, Secure.groupAad(group, fromx, myUuid), str(mx, "text"))
                  : Secure.open(fromx.equals(myUuid) ? str(mx, "to") : fromx, Secure.dmAad(fromx, str(mx, "to")), str(mx, "text"));
               State.Message msg = AddonHost.INSTANCE.filter(conv, decode(mx, opened));
               if (msg != null) {
                  listh.add(msg);
               }
            }

            for (State.Message m : conversations.getOrDefault(conv, List.of())) {
               if (m.system()) {
                  listh.add(m);
               }
            }

            listh.sort(Comparator.comparingLong(State.Message::ts));
            conversations.put(conv, listh);
            break;
         }
         case "deleted": {
            String group = str(o, "group");
            String conv = group != null ? "g:" + group : str(o, "with");
            String id = str(o, "id");
            List<State.Message> list = conversations.get(conv);
            if (list != null) {
               list.removeIf(mx -> id.equals(mx.id()));
            }
            break;
         }
         case "unread":
            for (Entry<String, JsonElement> e : o.getAsJsonObject("counts").entrySet()) {
               unread.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum);
            }

            int total = unread.values().stream().mapToInt(Integer::intValue).sum();
            if (total > 0) {
               notifyUser("VCraft", total + " unread message" + (total == 1 ? "" : "s"), null);
            }
            break;
         case "group_notice": {
            String conv = "g:" + str(o, "group");
            conversations.computeIfAbsent(conv, k -> new ArrayList<>())
               .add(new State.Message(null, null, null, str(o, "text"), o.get("ts").getAsLong(), true, null, null, true, null));
            break;
         }
         case "group_created":
            focusRequest = "g:" + str(o, "group");
            break;
         case "call_invite": {
            State.Invite inv = new State.Invite(str(o, "callId"), str(o, "from"), str(o, "fromName"), System.currentTimeMillis() + 60000L);
            if (!isBlocked(inv.from()) && !Social.autoAccept(inv)) {
               invites.put(inv.callId(), inv);
               String name = Social.displayName(inv.from(), inv.fromName());
               if (!quiet(inv.from())) {
                  VCraft.platform().toast(I18n.t("Incoming call"), I18n.tf("{0} is calling you", name));
                  VCraft.platform().chat("Call", -11141291, I18n.tf("{0} is calling you. Type /vc accept or open VCraft.", name));
               }

               DebugLog.line("call: invited by " + DebugLog.who(inv.from()));
            }
            break;
         }
         case "call": {
            JsonElement c = o.get("call");
            if (c != null && !c.isJsonNull()) {
               JsonObject j = c.getAsJsonObject();
               List<State.CallMember> members = new ArrayList<>();

               for (JsonElement e : j.getAsJsonArray("members")) {
                  JsonObject mx = e.getAsJsonObject();
                  setRank(str(mx, "uuid"), str(mx, "rank"));
                  Secure.observe(str(mx, "uuid"), str(mx, "key"));
                  members.add(new State.CallMember(str(mx, "uuid"), str(mx, "name"), bool(mx, "muted"), bool(mx, "deafened")));
               }

               invites.remove(str(j, "id"));
               if (call == null) {
                  DebugLog.line("call: joined (" + members.size() + " in it)");
               }

               setCall(new State.Call(str(j, "id"), members, profiles(j.getAsJsonArray("ringing"))));
               Social.noteCallMembers();
               Secure.onCall(call);
            } else {
               if (call != null) {
                  DebugLog.line("call: left");
               }

               setCall(null);
               Secure.onCall(null);
            }
            break;
         }
         case "export":
            saveExport(o.getAsJsonObject("data"));
            break;
         case "account_deleted":
            VCraft.config().autoConnect = false;
            VCraft.config().save();
            notice("Your VCraft account and its data were deleted from the relay.", false);
            break;
         case "admin_result":
            VCraft.platform().chat("Admin", -1002958, str(o, "text"));
            notice(str(o, "text"), false);
            break;
         case "call_rekey":
            Secure.rekey(call, false);
            break;
         case "call_cancel":
            invites.remove(str(o, "callId"));
            break;
         case "call_key":
            Secure.onCallKey(str(o, "from"), str(o, "callId"), o.get("epoch").getAsInt(), str(o, "key"));
            break;
         case "room.state": {
            JsonElement r = o.get("room");
            if (r != null && !r.isJsonNull()) {
               JsonObject j = r.getAsJsonObject();
               List<State.CallMember> members = new ArrayList<>();

               for (JsonElement e : j.getAsJsonArray("members")) {
                  JsonObject mx = e.getAsJsonObject();
                  Secure.observe(str(mx, "uuid"), str(mx, "key"));
                  members.add(new State.CallMember(str(mx, "uuid"), str(mx, "name"), bool(mx, "muted"), bool(mx, "deafened")));
               }

               room = new State.Room(str(j, "key"), str(j, "id"), members);
            } else {
               room = null;
            }

            Secure.onRoom(room);
            updateVoice();
            break;
         }
         case "room.key":
            Secure.onRoomKey(str(o, "from"), str(o, "room"), o.get("epoch").getAsInt(), str(o, "key"));
            break;
         case "room.rekey":
            Secure.rekeyRoom(false);
            break;
         case "info":
            notice(str(o, "text"), false);
            break;
         case "error":
            notice(str(o, "text"), true);
            VCraft.platform().toast("VCraft", str(o, "text"));
            break;
         default:
            AddonHost.INSTANCE.dispatch(o.get("t").getAsString(), o);
      }

      updateRinging();
      revision++;
   }

   private static void add(String conv, State.Message m) {
      m = AddonHost.INSTANCE.filter(conv, m);
      if (m != null) {
         AddonHost.INSTANCE.fireMessage(conv, m);
         conversations.computeIfAbsent(conv, k -> new ArrayList<>()).add(m);
         if (!m.from().equals(myUuid) && !conv.equals(viewing)) {
            unread.merge(conv, 1, Integer::sum);
            if (Social.groupMuted(conv)) {
               return;
            }

            String where = conv.startsWith("g:") ? convName(conv) : null;
            String fromName = Social.displayName(m.from(), m.fromName());
            String preview = m.invite() != null ? "invited you to " + m.invite().name() : (m.song() != null ? "is listening to " + m.song() : m.text());
            notifyUser(where == null ? fromName : I18n.tf("{0} in {1}", fromName, where), preview, m.from());
            if (!quiet(m.from())) {
               VCraft.platform().chat(where == null ? "DM" : where, -34817, fromName + ": " + preview);
               if (VCraft.config().messageSound) {
                  VCraft.platform().playDing();
               }
            }
         }
      }
   }

   private static State.Message decode(JsonObject o, Secure.Opened opened) {
      if (o.has("fromRank")) {
         setRank(str(o, "from"), str(o, "fromRank"));
      }

      State.Reply reply = null;
      State.ServerInvite invite = null;
      String song = null;
      String text;
      if (!opened.ok()) {
         text = "This message couldn't be decrypted.";
      } else {
         JsonObject env = JsonParser.parseString(opened.json()).getAsJsonObject();
         text = str(env, "text");
         if (env.has("reply")) {
            JsonObject r = env.getAsJsonObject("reply");
            reply = new State.Reply(str(r, "id"), str(r, "name"), str(r, "text"));
         }

         if (env.has("invite")) {
            JsonObject s = env.getAsJsonObject("invite");
            invite = new State.ServerInvite(str(s, "name"), str(s, "address"));
         }

         if (env.has("song")) {
            song = NowPlaying.clean(str(env, "song"));
         }
      }

      return new State.Message(
         str(o, "id"), str(o, "from"), str(o, "fromName"), text == null ? "" : text, o.get("ts").getAsLong(), opened.ok(), reply, invite, false, song
      );
   }

   private static void updatePresence(String uuid, String status) {
      State.Profile before = friend(uuid);
      Social.notePresence(uuid, before != null && before.online(), status);
      if ("offline".equals(status)) {
         listening.remove(uuid);
      } else if (before != null && !before.online() && (sharedSong != null || VCraft.config().shareServer)) {
         sendListening(List.of(uuid));
      }

      friends = sorted(friends.stream().map(p -> p.uuid().equals(uuid) ? new State.Profile(uuid, p.name(), status) : p).toList());
      List<State.Group> list = new ArrayList<>();

      for (State.Group g : groups) {
         list.add(
            new State.Group(
               g.id(), g.name(), g.owner(), g.members().stream().map(p -> p.uuid().equals(uuid) ? new State.Profile(uuid, p.name(), status) : p).toList()
            )
         );
      }

      groups = list;
   }

   private static void setCall(State.Call newCall) {
      State.Call before = call;
      call = newCall;
      updateVoice();
      AddonHost.INSTANCE.fireCall(before, newCall);
      if (before != null && newCall == null && room != null) {
         Secure.rekeyRoom(true);
         send("room.rekey", null, null);
      }
   }

   public static boolean voiceActive() {
      State.Room r = room;
      return call != null || r != null && r.members().size() > 1;
   }

   private static void updateVoice() {
      if (voiceActive()) {
         VCraft.voice().start();
      } else {
         VCraft.voice().stop();
      }
   }

   public static void trustNewKey(String uuid) {
      Secure.acceptPresentedKey(uuid);
      if (call != null && call.members().stream().anyMatch(m -> m.uuid().equals(uuid))) {
         send("call_rekey", null, null);
      }

      if (room != null && room.members().stream().anyMatch(m -> m.uuid().equals(uuid))) {
         send("room.rekey", null, null);
      }

      revision++;
   }

   public static void joinRoom(String key) {
      if (!key.equals(wantedRoom)) {
         wantedRoom = key;
         send("room.join", "key", key);
      }
   }

   public static void leaveRoom() {
      if (wantedRoom != null || room != null) {
         wantedRoom = null;
         send("room.leave", null, null);
         room = null;
         Secure.onRoom(null);
         updateVoice();
         revision++;
      }
   }

   public static void tick() {
      long now = System.currentTimeMillis();
      if (invites.values().removeIf(i -> i.expires() < now)) {
         revision++;
      }

      updateRinging();
      AddonHost.INSTANCE.tick();
      Social.tick();
      if (now >= nextListeningCheck && listeningSynced && VCraft.relay().connected()) {
         nextListeningCheck = now + 2000L;
         sharedSong = sharedSong();
         if (Social.changed()) {
            sendListening(onlineFriends());
         }
      }
   }

   /** The song being shared with friends right now. */
   public static String sharedSongNow() {
      return sharedSong;
   }

   /** Muted by the pause menu or another window: updates the relay when it changes. */
   public static void setAutoMuted(boolean on) {
      if (on != autoMuted) {
         autoMuted = on;
         sendVoiceState();
         revision++;
      }
   }

   private static String sharedSong() {
      return VCraft.config().shareListening && !"invisible".equals(myStatus) && NowPlaying.sharingEnabled() ? NowPlaying.current() : null;
   }

   private static List<String> onlineFriends() {
      return friends.stream().filter(State.Profile::online).map(State.Profile::uuid).toList();
   }

   /** The song, and where you play and whether you're in a call (2.5.0). */
   private static void sendListening(List<String> to) {
      Social.sendActivity(to, null);
   }

   private static void updateRinging() {
      boolean shouldRing = false;

      for (State.Invite i : invites.values()) {
         if (!quiet(i.from())) {
            shouldRing = true;
         }
      }

      if (shouldRing != ringing) {
         ringing = shouldRing;
         if (shouldRing && !"none".equals(VCraft.config().ringtone)) {
            VCraft.platform().startRingtone(VCraft.config().ringtone);
         } else {
            VCraft.platform().stopRingtone();
         }
      }
   }

   private static boolean quiet(String from) {
      return "dnd".equals(myStatus) || from != null && VCraft.config().mutedUsers.contains(from);
   }

   public static void send(String type, String key, String value) {
      JsonObject o = new JsonObject();
      o.addProperty("t", type);
      if (key != null) {
         o.addProperty(key, value);
      }

      VCraft.relay().send(o);
   }

   public static boolean sendMessage(String conv, String text, State.Reply reply, State.ServerInvite invite) {
      return sendMessage(conv, text, reply, invite, null);
   }

   public static void sendSong(String conv) {
      String song = NowPlaying.current();
      if (song == null) {
         notice(NowPlaying.installed() ? "Nothing is playing right now." : "Install the Now Playing mod to share songs.", true);
      } else {
         sendMessage(conv, "♪ Listening to " + song, null, null, song);
      }
   }

   private static boolean sendMessage(String conv, String text, State.Reply reply, State.ServerInvite invite, String song) {
      String blocked = AddonHost.INSTANCE.checkSend(conv, text);
      if (blocked != null) {
         notice(blocked, true);
         return false;
      } else if ("lobby".equals(conv)) {
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
      } else {
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

         if (song != null) {
            env.addProperty("song", song);
         }

         String plain = env.toString();
         JsonObject o = new JsonObject();
         if (conv.startsWith("g:")) {
            State.Group g = group(conv);
            if (g == null) {
               return false;
            }

            JsonObject payloads = new JsonObject();
            List<String> missed = new ArrayList<>();

            for (State.Profile p : g.members()) {
               String sealed = Secure.seal(p.uuid(), Secure.groupAad(g.id(), myUuid, p.uuid()), plain);
               if (sealed != null) {
                  payloads.addProperty(p.uuid(), sealed);
               } else if (!p.uuid().equals(myUuid)) {
                  missed.add(p.name());
               }
            }

            if (!missed.isEmpty()) {
               notice("Not sent to " + String.join(", ", missed) + " (encryption key not trusted).", true);
            }

            o.addProperty("t", "gmsg");
            o.addProperty("group", g.id());
            o.add("payloads", payloads);
         } else {
            String sealed = Secure.seal(conv, Secure.dmAad(myUuid, conv), plain);
            if (sealed == null) {
               notice(
                  Secure.trust(conv) == Secure.Trust.CHANGED
                     ? "Not sent: their encryption key changed. Check the safety number, then Trust."
                     : "Not sent: no encryption key for them yet.",
                  true
               );
               return false;
            }

            o.addProperty("t", "dm");
            o.addProperty("to", conv);
            o.addProperty("text", sealed);
         }

         VCraft.relay().send(o);
         return true;
      }
   }

   public static void exportData() {
      send("export_data", null, null);
   }

   public static void deleteAccount() {
      send("delete_account", "confirm", "DELETE");
   }

   private static void saveExport(JsonObject data) {
      try {
         Path dir = VCraft.platform().configDir().resolve("vcraft");
         Files.createDirectories(dir);
         String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
         Path file = dir.resolve("data-export-" + stamp + ".json");
         Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(data));
         notice("Saved your data to config/vcraft/" + file.getFileName(), false);
      } catch (Exception var4) {
         notice("Couldn't save your data export: " + var4.getMessage(), true);
      }
   }

   private static State.Message lobbyMessage(JsonObject o) {
      setRank(str(o, "from"), str(o, "fromRank"));
      State.Reply reply = null;
      if (o.has("reply") && o.get("reply").isJsonObject()) {
         JsonObject r = o.getAsJsonObject("reply");
         reply = new State.Reply(str(r, "id"), str(r, "name"), str(r, "text"));
      }

      return new State.Message(str(o, "id"), str(o, "from"), str(o, "fromName"), str(o, "text"), o.get("ts").getAsLong(), true, reply, null, false, null);
   }

   private static void setRank(String uuid, String rank) {
      if (uuid != null) {
         if (rank != null && !rank.isEmpty()) {
            ranks.put(uuid, rank);
         } else {
            ranks.remove(uuid);
         }
      }
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
      if (arg != null) {
         o.addProperty("arg", arg);
      }

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
      if ("lobby".equals(conv)) {
         send("lobby_delete", "id", id);
      } else {
         JsonObject o = new JsonObject();
         o.addProperty("t", "delete");
         o.addProperty("id", id);
         if (conv.startsWith("g:")) {
            o.addProperty("group", conv.substring(2));
         } else {
            o.addProperty("with", conv);
         }

         VCraft.relay().send(o);
      }
   }

   public static boolean inviteToServer(String conv) {
      Platform.ServerInfo server = VCraft.platform().currentServer();
      if (server == null) {
         notice("Join a multiplayer server first, then invite people to it.", true);
         return false;
      } else {
         sendMessage(conv, "Come play with me on " + server.name() + "!", null, new State.ServerInvite(server.name(), server.address()));
         return true;
      }
   }

   public static void openConversation(String conv) {
      viewing = conv;
      if (conv != null) {
         unread.remove(conv);
         JsonObject o = new JsonObject();
         o.addProperty("t", "history");
         if ("lobby".equals(conv)) {
            send("lobby_join", null, null);
            revision++;
         } else {
            if (conv.startsWith("g:")) {
               o.addProperty("group", conv.substring(2));
            } else {
               o.addProperty("with", conv);
            }

            VCraft.relay().send(o);
            revision++;
         }
      }
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
      if (key != null) {
         o.addProperty(key, value);
      }

      VCraft.relay().send(o);
   }

   public static void call(String uuid) {
      send("call_invite", "to", uuid);
   }

   public static void callGroup(State.Group g) {
      int rung = 0;

      for (State.Profile p : g.members()) {
         if (!p.uuid().equals(myUuid) && p.online() && !inCall(p.uuid())) {
            call(p.uuid());
            rung++;
         }
      }

      if (rung == 0) {
         notice("Nobody else in " + g.name() + " is online.", true);
      }
   }

   public static boolean inCall(String uuid) {
      State.Call c = call;
      if (c == null) {
         return false;
      } else {
         for (State.CallMember m : c.members()) {
            if (m.uuid().equals(uuid)) {
               return true;
            }
         }

         for (State.Profile p : c.ringing()) {
            if (p.uuid().equals(uuid)) {
               return true;
            }
         }

         return false;
      }
   }

   public static void acceptInvite(State.Invite inv) {
      send("call_accept", "callId", inv.callId());
      invites.remove(inv.callId());
      updateRinging();
      revision++;
   }

   public static void declineInvite(State.Invite inv) {
      send("call_decline", "callId", inv.callId());
      invites.remove(inv.callId());
      updateRinging();
      revision++;
   }

   public static State.Invite latestInvite() {
      State.Invite latest = null;

      for (State.Invite inv : invites.values()) {
         latest = inv;
      }

      return latest;
   }

   public static void reconnectVoice() {
      State.Call c = call;
      if (c != null || room != null) {
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

   public static void sendVoiceState() {
      JsonObject o = new JsonObject();
      o.addProperty("t", "state");
      o.addProperty("muted", muted || deafened || autoMuted);
      o.addProperty("deafened", deafened);
      VCraft.relay().send(o);
   }

   public static void toggleUserMute(String uuid) {
      Set<String> set = VCraft.config().mutedUsers;
      if (!set.remove(uuid)) {
         set.add(uuid);
      }

      VCraft.config().save();
      updateRinging();
      revision++;
   }

   public static State.Profile friend(String uuid) {
      for (State.Profile f : friends) {
         if (f.uuid().equals(uuid)) {
            return f;
         }
      }

      return null;
   }

   public static State.Profile friendByName(String name) {
      for (State.Profile f : friends) {
         if (f.name().equalsIgnoreCase(name)) {
            return f;
         }
      }

      return null;
   }

   public static State.Group group(String conv) {
      if (conv != null && conv.startsWith("g:")) {
         String id = conv.substring(2);

         for (State.Group g : groups) {
            if (g.id().equals(id)) {
               return g;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   public static boolean isBlocked(String uuid) {
      for (State.Profile p : blocked) {
         if (p.uuid().equals(uuid)) {
            return true;
         }
      }

      return false;
   }

   public static String convName(String conv) {
      if ("lobby".equals(conv)) {
         return "VCraft Community";
      } else {
         State.Group g = group(conv);
         if (g != null) {
            return g.name();
         } else {
            State.Profile f = friend(conv);
            return f == null ? "Unknown" : Social.displayName(f.uuid(), f.name());
         }
      }
   }

   public static boolean conversationExists(String conv) {
      return conv != null && ("lobby".equals(conv) || (conv.startsWith("g:") ? group(conv) != null : friend(conv) != null));
   }

   public static boolean isSpeaking(String uuid) {
      Long t = lastSpoke.get(uuid);
      return t != null && System.currentTimeMillis() - t < 300L;
   }

   public static String statusLabel(String status) {
      String var1 = status == null ? "offline" : status;

      return switch (var1) {
         case "online" -> "Online";
         case "idle" -> "Idle";
         case "dnd" -> "Do Not Disturb";
         case "invisible" -> "Offline (invisible)";
         default -> "Offline";
      };
   }

   public static int statusColor(String status) {
      String var1 = status == null ? "offline" : status;

      return switch (var1) {
         case "online" -> -12868260;
         case "idle" -> -350694;
         case "dnd" -> -1228219;
         default -> -9142387;
      };
   }

   public static void notice(String text, boolean error) {
      notice = text;
      noticeIsError = error;
      noticeTime = System.currentTimeMillis();
      revision++;
   }

   private static void notifyUser(String title, String body, String from) {
      if (!quiet(from) && VCraft.config().notifications) {
         VCraft.platform().toast(title, body);
      }
   }

   private static List<State.Profile> sorted(List<State.Profile> list) {
      List<State.Profile> out = new ArrayList<>(list);
      out.sort(Comparator.<State.Profile, Boolean>comparing(p -> !p.online()).thenComparing(p -> p.name().toLowerCase()));
      return out;
   }

   private static List<State.Profile> profiles(JsonArray arr) {
      List<State.Profile> out = new ArrayList<>();
      if (arr == null) {
         return out;
      } else {
         for (JsonElement e : arr) {
            JsonObject j = e.getAsJsonObject();
            setRank(str(j, "uuid"), str(j, "rank"));
            Secure.observe(str(j, "uuid"), str(j, "key"));
            out.add(new State.Profile(str(j, "uuid"), str(j, "name"), j.has("status") ? str(j, "status") : "offline"));
         }

         return out;
      }
   }

   private static String str(JsonObject o, String key) {
      JsonElement e = o.get(key);
      return e != null && !e.isJsonNull() ? e.getAsString() : null;
   }

   private static boolean bool(JsonObject o, String key) {
      JsonElement e = o.get(key);
      return e != null && !e.isJsonNull() && e.getAsBoolean();
   }

   private State() {
   }

   public static record Call(String id, List<State.CallMember> members, List<State.Profile> ringing) {
   }

   public static record CallMember(String uuid, String name, boolean muted, boolean deafened) {
   }

   public static record Group(String id, String name, String owner, List<State.Profile> members) {
      public String conv() {
         return "g:" + this.id;
      }
   }

   public static record Invite(String callId, String from, String fromName, long expires) {
   }

   public static record Message(
      String id, String from, String fromName, String text, long ts, boolean secure, State.Reply reply, State.ServerInvite invite, boolean system, String song
   ) {
   }

   public static record Profile(String uuid, String name, String status) {
      public boolean online() {
         return !"offline".equals(this.status);
      }
   }

   public static record Reply(String id, String name, String text) {
   }

   public static record Room(String key, String id, List<State.CallMember> members) {
   }

   public static record ServerInvite(String name, String address) {
   }
}
