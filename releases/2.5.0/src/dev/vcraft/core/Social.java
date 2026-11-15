package dev.vcraft.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VCraft 2.5's social features (the same protocol as VCraft for Horizon, so
 * both talk to each other): what friends are doing and requests to join calls
 * or groups ride in the end-to-end encrypted {@code activity} message that
 * already carries "Now listening"; last seen, pins and nicknames stay on this
 * computer.
 */
public final class Social {
   private static final long REJOIN_WINDOW_MS = 180_000L;
   private static final long REJOIN_TRY_MS = 120_000L;
   private static final long REQUEST_MS = 60_000L;
   private static final String CODE_PREFIX = "vc1.";
   private static final int MAX_PINS = 50;

   public record Activity(String server, boolean inCall) {
   }

   /** kind is "call", or "group" with the group's id. */
   public record Request(String from, String name, String kind, String group, long at) {
   }

   public record Pin(String id, String fromName, String text, long ts) {
   }

   public static final Map<String, Activity> activity = new ConcurrentHashMap<>();
   public static final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
   public static final Map<String, List<Pin>> pins = new ConcurrentHashMap<>();
   public static final List<Request> requests = new ArrayList<>();
   private static final Map<String, Long> waitingJoin = new HashMap<>();
   private static final Map<String, Long> recentMembers = new HashMap<>();
   private static List<String> rejoinMembers;
   private static long rejoinDropped;
   private static long rejoinNextTry;
   private static String sent;
   private static long nextSeenSave;
   private static boolean loaded;

   private Social() {
   }

   // ---- Files ----

   private static Path data(String name) {
      return VCraft.platform().configDir().resolve("vcraft").resolve(name);
   }

   public static void load() {
      if (loaded) {
         return;
      }
      loaded = true;
      try {
         Path seen = data("last-seen.json");
         if (Files.exists(seen)) {
            for (Map.Entry<String, JsonElement> e : JsonParser.parseString(Files.readString(seen)).getAsJsonObject().entrySet()) {
               lastSeen.put(e.getKey(), e.getValue().getAsLong());
            }
         }
         Path p = data("pins.json");
         if (Files.exists(p)) {
            for (Map.Entry<String, JsonElement> e : JsonParser.parseString(Files.readString(p)).getAsJsonObject().entrySet()) {
               List<Pin> list = new ArrayList<>();
               for (JsonElement el : e.getValue().getAsJsonArray()) {
                  JsonObject o = el.getAsJsonObject();
                  list.add(new Pin(str(o, "id"), str(o, "fromName"), str(o, "text"), o.has("ts") ? o.get("ts").getAsLong() : 0L));
               }
               if (!list.isEmpty()) {
                  pins.put(e.getKey(), list);
               }
            }
         }
      } catch (Exception e) {
         VCraft.LOGGER.warn("VCraft couldn't read its pins or last-seen file", e);
      }
   }

   private static void write(String name, JsonObject o) {
      try {
         Path f = data(name);
         Files.createDirectories(f.getParent());
         Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }
   }

   // ---- What you share ----

   private static String myPlace() {
      Platform.ServerInfo s = VCraft.platform().currentServer();
      if (s != null) {
         return s.name();
      }
      return VCraft.platform().inWorld() ? "Singleplayer" : null;
   }

   private static boolean sharing() {
      return VCraft.config().shareServer && !"invisible".equals(State.myStatus);
   }

   private static String env(JsonObject req) {
      JsonObject env = new JsonObject();
      String song = State.sharedSongNow();
      if (song != null) {
         env.addProperty("song", song);
      }
      if (sharing()) {
         String place = myPlace();
         if (place != null) {
            env.addProperty("server", place);
         }
         env.addProperty("inCall", State.call != null);
      }
      if (req != null) {
         env.add("req", req);
      }
      return env.toString();
   }

   public static boolean changed() {
      String place = sharing() ? myPlace() : null;
      String sig = State.sharedSongNow() + "|" + place + "|" + (sharing() && State.call != null);
      if (sig.equals(sent)) {
         return false;
      }
      sent = sig;
      return true;
   }

   public static void sendActivity(List<String> to, JsonObject req) {
      String plain = env(req);
      JsonObject payloads = new JsonObject();
      for (String uuid : to) {
         String sealed = Secure.seal(uuid, Secure.activityAad(State.myUuid, uuid), plain);
         if (sealed != null) {
            payloads.addProperty(uuid, sealed);
         }
      }
      if (payloads.size() > 0) {
         JsonObject o = new JsonObject();
         o.addProperty("t", "activity");
         o.add("payloads", payloads);
         VCraft.relay().send(o);
      }
   }

   private static JsonObject req(String type, String group) {
      JsonObject r = new JsonObject();
      r.addProperty("type", type);
      if (group != null) {
         r.addProperty("group", group);
      }
      return r;
   }

   /** A friend's activity, opened. */
   public static void onActivity(String from, JsonObject env) {
      String server = str(env, "server");
      if (server != null) {
         server = server.replaceAll("\\p{Cntrl}", "");
         if (server.length() > 48) {
            server = server.substring(0, 48);
         }
      }
      boolean inCall = env.has("inCall") && !env.get("inCall").isJsonNull() && env.get("inCall").getAsBoolean();
      if (server == null && !inCall) {
         activity.remove(from);
      } else {
         activity.put(from, new Activity(server, inCall));
      }
      if (env.has("req") && env.get("req").isJsonObject()) {
         onRequest(from, env.getAsJsonObject("req"));
      }
   }

   private static void onRequest(String from, JsonObject req) {
      State.Profile f = State.friend(from);
      if (f == null) {
         return;
      }
      String name = displayName(f.uuid(), f.name());
      boolean quiet = "dnd".equals(State.myStatus) || VCraft.config().mutedUsers.contains(from);
      String type = str(req, "type");
      if ("rejoin".equals(type) && State.call != null) {
         Long t = recentMembers.get(from);
         if (t != null && System.currentTimeMillis() - t < REJOIN_WINDOW_MS) {
            DebugLog.line("call: letting " + DebugLog.who(from) + " back in after a dropped connection");
            State.call(from);
            State.notice(I18n.tf("{0} is rejoining the call", name), false);
         } else {
            addRequest(from, name, "call", null, quiet);
         }
      } else if ("join".equals(type) && State.call != null) {
         addRequest(from, name, "call", null, quiet);
      } else if ("group".equals(type)) {
         String gid = str(req, "group");
         State.Group g = gid == null ? null : State.group("g:" + gid);
         if (g != null && g.members().stream().noneMatch(m -> m.uuid().equals(from))) {
            addRequest(from, name, "group", gid, quiet);
         }
      }
   }

   private static void addRequest(String from, String name, String kind, String group, boolean quiet) {
      requests.removeIf(r -> r.from().equals(from) && r.kind().equals(kind) && Objects.equals(r.group(), group));
      String body;
      if ("call".equals(kind)) {
         body = I18n.tf("{0} wants to join your call", name);
      } else {
         State.Group g = State.group("g:" + group);
         body = I18n.tf("{0} wants to join {1}", name, g == null ? "" : g.name());
      }
      requests.add(new Request(from, name, kind, group, System.currentTimeMillis()));
      if (!quiet) {
         VCraft.platform().toast(I18n.t("Request"), body);
         VCraft.platform().chat("VCraft", -11141291, body + ". " + I18n.t("Open VCraft to let them in."));
         if (VCraft.config().messageSound) {
            VCraft.platform().playDing();
         }
      }
      State.changed();
   }

   public static void accept(Request r) {
      if ("call".equals(r.kind())) {
         State.call(r.from());
      } else {
         State.groupAction("group_add", r.group(), "uuid", r.from());
      }
      requests.remove(r);
      State.changed();
   }

   public static void dismiss(Request r) {
      requests.remove(r);
      State.changed();
   }

   public static Request latestRequest() {
      return requests.isEmpty() ? null : requests.get(requests.size() - 1);
   }

   /** Asks a friend who is in a call to ring you in. */
   public static void askToJoin(String uuid) {
      State.Profile f = State.friend(uuid);
      if (f == null) {
         return;
      }
      String name = displayName(f.uuid(), f.name());
      Activity a = activity.get(uuid);
      if (a == null || !a.inCall()) {
         State.notice(I18n.tf("{0} isn't in a call.", name), true);
         return;
      }
      waitingJoin.put(uuid, System.currentTimeMillis() + REQUEST_MS);
      sendActivity(List.of(uuid), req("join", null));
      State.notice(I18n.tf("Asked {0} to let you into their call.", name), false);
   }

   /** A call invite answering your own request is accepted by itself. */
   public static boolean autoAccept(State.Invite inv) {
      Long until = waitingJoin.get(inv.from());
      if (until == null || until < System.currentTimeMillis() || State.call != null) {
         return false;
      }
      waitingJoin.remove(inv.from());
      rejoinMembers = null;
      DebugLog.line("call: joining " + DebugLog.who(inv.from()) + "'s call");
      State.send("call_accept", "callId", inv.callId());
      return true;
   }

   // ---- Calls ----

   public static void noteCallMembers() {
      State.Call c = State.call;
      if (c == null) {
         return;
      }
      long now = System.currentTimeMillis();
      for (State.CallMember m : c.members()) {
         if (!m.uuid().equals(State.myUuid)) {
            recentMembers.put(m.uuid(), now);
         }
      }
   }

   /** The relay connection dropped: remember the call, to get back in. */
   public static void prepareRejoin() {
      State.Call c = State.call;
      if (c == null || !VCraft.config().autoRejoin || !VCraft.relay().wanted()) {
         return;
      }
      List<String> others = new ArrayList<>();
      for (State.CallMember m : c.members()) {
         if (!m.uuid().equals(State.myUuid)) {
            others.add(m.uuid());
         }
      }
      if (others.isEmpty()) {
         return;
      }
      DebugLog.line("call: connection dropped with " + others.size() + " others in the call, will try to rejoin");
      rejoinMembers = others;
      rejoinDropped = System.currentTimeMillis();
      rejoinNextTry = rejoinDropped;
   }

   public static void cancelRejoin() {
      rejoinMembers = null;
   }

   public static void resetConnection() {
      activity.clear();
      requests.clear();
      waitingJoin.clear();
   }

   public static void tick() {
      long now = System.currentTimeMillis();
      requests.removeIf(r -> now - r.at() > REQUEST_MS);
      waitingJoin.values().removeIf(t -> t < now);
      recentMembers.values().removeIf(t -> now - t > REJOIN_WINDOW_MS);
      if (State.call != null) {
         noteCallMembers();
      }
      tryRejoin(now);
      if (now >= nextSeenSave && VCraft.relay().connected()) {
         nextSeenSave = now + 60_000L;
         for (State.Profile f : State.friends) {
            if (f.online()) {
               lastSeen.put(f.uuid(), now);
            }
         }
         saveLastSeen();
      }
   }

   private static void tryRejoin(long now) {
      List<String> members = rejoinMembers;
      if (members == null) {
         return;
      }
      if (State.call != null || now - rejoinDropped > REJOIN_TRY_MS) {
         if (State.call == null) {
            DebugLog.line("call: couldn't rejoin in time");
            State.notice(I18n.t("Couldn't get back into the call."), true);
         }
         rejoinMembers = null;
         return;
      }
      if (now < rejoinNextTry || !VCraft.relay().connected() || State.myUuid == null) {
         return;
      }
      String pick = null;
      for (String u : members) {
         State.Profile f = State.friend(u);
         if (f != null && f.online()) {
            pick = u;
            break;
         }
      }
      if (pick != null) {
         members.remove(pick);
         waitingJoin.put(pick, now + 20_000L);
         sendActivity(List.of(pick), req("rejoin", null));
         DebugLog.line("call: asked " + DebugLog.who(pick) + " to let us back in");
         State.notice(I18n.t("Reconnected. Getting you back into the call..."), false);
         rejoinNextTry = now + 12_000L;
      } else if (!State.friends.isEmpty() && members.stream().allMatch(u -> State.friend(u) == null)) {
         State.notice(I18n.t("Reconnected. Ask someone in the call to invite you again."), false);
         rejoinMembers = null;
      } else {
         rejoinNextTry = now + 2000L;
      }
   }

   // ---- Last seen ----

   public static void notePresence(String uuid, boolean wasOnline, String status) {
      if ("offline".equals(status)) {
         activity.remove(uuid);
         if (wasOnline) {
            lastSeen.put(uuid, System.currentTimeMillis());
            saveLastSeen();
         }
      }
   }

   private static void saveLastSeen() {
      JsonObject o = new JsonObject();
      lastSeen.forEach(o::addProperty);
      write("last-seen.json", o);
   }

   public static String ago(long ms, long now) {
      long secs = Math.max(0, (now - ms) / 1000);
      if (secs < 60) {
         return I18n.t("just now");
      } else if (secs < 3600) {
         return I18n.tf("{0}m ago", String.valueOf(secs / 60));
      } else if (secs < 86400) {
         return I18n.tf("{0}h ago", String.valueOf(secs / 3600));
      }
      return I18n.tf("{0}d ago", String.valueOf(secs / 86400));
   }

   /** The line under a friend: "Playing on X", "In a call", "Last seen 2h ago". */
   public static String detail(String uuid, boolean online) {
      if (!online) {
         Long t = lastSeen.get(uuid);
         return t == null ? null : I18n.tf("Last seen {0}", ago(t, System.currentTimeMillis()));
      }
      Activity a = activity.get(uuid);
      if (a == null) {
         return null;
      }
      boolean sp = "Singleplayer".equals(a.server());
      if (a.inCall()) {
         if (a.server() == null) {
            return I18n.t("In a call");
         }
         return sp ? I18n.t("In a call, in single player") : I18n.tf("In a call, on {0}", a.server());
      }
      if (a.server() == null) {
         return null;
      }
      return sp ? I18n.t("Playing single player") : I18n.tf("Playing on {0}", a.server());
   }

   public static boolean friendInCall(String uuid) {
      Activity a = activity.get(uuid);
      return a != null && a.inCall() && !State.inCall(uuid);
   }

   // ---- Pins ----

   public static List<Pin> pins(String conv) {
      if (conv == null || "lobby".equals(conv)) {
         return List.of();
      }
      return pins.getOrDefault(conv, List.of());
   }

   public static boolean isPinned(String conv, String id) {
      return id != null && pins(conv).stream().anyMatch(p -> p.id().equals(id));
   }

   public static void togglePin(String conv, State.Message m) {
      if (m.id() == null) {
         return;
      }
      List<Pin> list = new ArrayList<>(pins.getOrDefault(conv, List.of()));
      if (!list.removeIf(p -> p.id().equals(m.id()))) {
         String text = m.invite() != null ? "Server invite: " + m.invite().name() : (m.song() != null ? "♪ " + m.song() : m.text());
         list.add(new Pin(m.id(), m.fromName(), text, m.ts()));
         list.sort((a, b) -> Long.compare(a.ts(), b.ts()));
         while (list.size() > MAX_PINS) {
            list.remove(0);
         }
      }
      if (list.isEmpty()) {
         pins.remove(conv);
      } else {
         pins.put(conv, list);
      }
      savePins();
      State.changed();
   }

   public static void unpin(String conv, String id) {
      List<Pin> list = new ArrayList<>(pins.getOrDefault(conv, List.of()));
      list.removeIf(p -> p.id().equals(id));
      if (list.isEmpty()) {
         pins.remove(conv);
      } else {
         pins.put(conv, list);
      }
      savePins();
      State.changed();
   }

   private static void savePins() {
      JsonObject o = new JsonObject();
      pins.forEach((conv, list) -> {
         com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
         for (Pin p : list) {
            JsonObject j = new JsonObject();
            j.addProperty("id", p.id());
            j.addProperty("fromName", p.fromName());
            j.addProperty("text", p.text());
            j.addProperty("ts", p.ts());
            arr.add(j);
         }
         o.add(conv, arr);
      });
      write("pins.json", o);
   }

   // ---- Group codes ----

   public static String encodeCode(String group, String issuer, String issuerName, String groupName) {
      JsonObject o = new JsonObject();
      o.addProperty("g", group);
      o.addProperty("o", issuer);
      o.addProperty("n", issuerName);
      o.addProperty("gn", groupName);
      return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8));
   }

   /** [group, issuer, issuerName, groupName], or null. */
   public static String[] decodeCode(String code) {
      try {
         String c = code.trim();
         if (!c.startsWith(CODE_PREFIX)) {
            return null;
         }
         JsonObject o = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(c.substring(CODE_PREFIX.length())), StandardCharsets.UTF_8)).getAsJsonObject();
         String g = str(o, "g"), issuer = str(o, "o");
         if (g == null || g.isEmpty() || issuer == null || issuer.isEmpty()) {
            return null;
         }
         return new String[]{g, issuer, Objects.requireNonNullElse(str(o, "n"), ""), Objects.requireNonNullElse(str(o, "gn"), "")};
      } catch (Exception e) {
         return null;
      }
   }

   public static String groupCode(String conv) {
      State.Group g = State.group(conv);
      if (g == null || State.myUuid == null) {
         return null;
      }
      return encodeCode(g.id(), State.myUuid, State.myName == null ? "" : State.myName, g.name());
   }

   /** Pasting a code; returns the conversation to open when you're already in the group. */
   public static String joinWithCode(String code) {
      String[] c = decodeCode(code);
      if (c == null) {
         State.notice(I18n.t("That isn't a VCraft group code."), true);
         return null;
      }
      if (State.group("g:" + c[0]) != null) {
         return "g:" + c[0];
      }
      if (c[1].equals(State.myUuid)) {
         State.notice(I18n.t("That's your own code."), true);
         return null;
      }
      String groupName = c[3].isEmpty() ? I18n.t("the group") : c[3];
      State.Profile f = State.friend(c[1]);
      if (f != null && f.online()) {
         sendActivity(List.of(f.uuid()), req("group", c[0]));
         State.notice(I18n.tf("Asked {0} to add you to {1}.", displayName(f.uuid(), f.name()), groupName), false);
      } else if (f != null) {
         State.notice(I18n.tf("{0} is offline. Try again when they're online.", displayName(f.uuid(), f.name())), true);
      } else if (!c[2].isEmpty()) {
         State.send("friend_request", "name", c[2]);
         State.notice(I18n.tf("Sent {0} a friend request. Paste the code again once they accept.", c[2]), false);
      } else {
         State.notice(I18n.t("Add the person who sent the code as a friend first."), true);
      }
      return null;
   }

   // ---- Nicknames, notes, muted groups ----

   public static String displayName(String uuid, String name) {
      Map<String, String> n = VCraft.config().nicknames;
      String nick = n == null || uuid == null ? null : n.get(uuid);
      return nick == null || nick.isEmpty() ? name : nick;
   }

   public static String note(String uuid) {
      Map<String, String> n = VCraft.config().notes;
      return n == null || uuid == null ? null : n.get(uuid);
   }

   public static void setNickname(String uuid, String nick, String note) {
      Config c = VCraft.config();
      nick = nick == null ? "" : nick.trim();
      note = note == null ? "" : note.trim();
      if (nick.isEmpty()) {
         c.nicknames.remove(uuid);
      } else {
         c.nicknames.put(uuid, nick.length() > 24 ? nick.substring(0, 24) : nick);
      }
      if (note.isEmpty()) {
         c.notes.remove(uuid);
      } else {
         c.notes.put(uuid, note.length() > 160 ? note.substring(0, 160) : note);
      }
      c.save();
      State.changed();
   }

   public static boolean groupMuted(String conv) {
      return conv != null && conv.startsWith("g:") && VCraft.config().mutedGroups.contains(conv.substring(2));
   }

   public static void toggleGroupMute(String groupId) {
      if (!VCraft.config().mutedGroups.remove(groupId)) {
         VCraft.config().mutedGroups.add(groupId);
      }
      VCraft.config().save();
      State.changed();
   }

   public static int unreadTotal() {
      int total = 0;
      for (Map.Entry<String, Integer> e : State.unread.entrySet()) {
         if (!groupMuted(e.getKey())) {
            total += e.getValue();
         }
      }
      return total;
   }

   private static String str(JsonObject o, String key) {
      JsonElement e = o.get(key);
      return e != null && !e.isJsonNull() ? e.getAsString() : null;
   }
}
