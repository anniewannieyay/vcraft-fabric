package dev.vcraft.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.vcraft.relay.RelayServer.bool;
import static dev.vcraft.relay.RelayServer.msg;
import static dev.vcraft.relay.RelayServer.send;
import static dev.vcraft.relay.RelayServer.str;

/**
 * Relay side of the optional VCraft addons: voice rooms (Proximity), announcements and group rules.
 * Runs on the relay's logic thread like everything else. Clients without the addons never send these.
 */
final class RelayAddons {
    static final String PROXIMITY = "vcraft-proximity";
    static final String ANNOUNCEMENTS = "vcraft-announcements";
    static final String GROUP_RULES = "vcraft-grouprules";

    private static final Pattern ADDON_ID = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");
    private static final Pattern ROOM_KEY = Pattern.compile("^[A-Za-z0-9:-]{3,80}$");
    private static final int MAX_ROOM = 32;
    private static final int MAX_ANNOUNCEMENTS = 50;
    private static final int MAX_BLOB = 16 * 1024;
    private static final long MAX_CLOCK_SKEW_MS = 10 * 60_000;

    private final RelayServer relay;
    private final Map<String, Room> rooms = new HashMap<>();
    private final Map<String, Long> lastGroupPost = new HashMap<>();

    static final class Room {
        final String id;
        final String key;
        final Set<RelayServer.Client> members = new LinkedHashSet<>();

        Room(String id, String key) {
            this.id = id;
            this.key = key;
        }
    }

    RelayAddons(RelayServer relay) {
        this.relay = relay;
    }

    /** Returns true if it handled the message type. */
    boolean handle(RelayServer.Client c, String type, JsonObject o) {
        switch (type) {
            case "addons" -> setAddons(c, o);
            case "room.join" -> roomJoin(c, str(o, "key"));
            case "room.leave" -> roomLeave(c, true);
            case "room.key" -> roomKey(c, o);
            case "room.rekey" -> roomRekey(c);
            case "ann.list" -> announcementList(c);
            case "ann.post" -> announcementPost(c, o);
            case "ann.delete" -> announcementDelete(c, str(o, "id"));
            case "ann.rsvp" -> announcementRsvp(c, str(o, "id"), bool(o, "going"));
            case "grule.set" -> rulesSet(c, o);
            case "grule.get" -> rulesGet(c, str(o, "group"), bool(o, "refresh"));
            case "grule.share" -> rulesShare(c, str(o, "group"), str(o, "to"), str(o, "blob"));
            case "grule.accept" -> rulesAccept(c, str(o, "group"), o.has("version") ? o.get("version").getAsInt() : -1);
            case "grule.mute" -> rulesMute(c, str(o, "group"), str(o, "uuid"), o.has("minutes") ? o.get("minutes").getAsInt() : 0);
            case "grule.kick" -> relay.groupKick(c, str(o, "group"), str(o, "uuid"));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void setAddons(RelayServer.Client c, JsonObject o) {
        Set<String> ids = new LinkedHashSet<>();
        JsonElement list = o.get("list");
        if (list != null && list.isJsonArray()) {
            for (JsonElement e : list.getAsJsonArray()) {
                if (ids.size() >= 32) break;
                if (e.isJsonPrimitive() && ADDON_ID.matcher(e.getAsString()).matches()) ids.add(e.getAsString());
            }
        }
        c.addons = ids;
    }

    void disconnected(RelayServer.Client c) {
        roomLeave(c, false);
    }

    // ---- Voice rooms ----

    private void roomJoin(RelayServer.Client c, String key) {
        if (key == null || !ROOM_KEY.matcher(key).matches() || !c.callInvites.take()) return;
        if (c.room != null && c.room.key.equals(key)) {
            broadcastRoom(c.room);
            return;
        }
        Room room = rooms.get(key);
        if (room != null && room.members.size() >= MAX_ROOM) {
            relay.error(c, "That proximity room is full (" + MAX_ROOM + " players).");
            return;
        }
        roomLeave(c, false);
        if (room == null) {
            room = new Room(relay.newId(), key);
            rooms.put(key, room);
        }
        room.members.add(c);
        c.room = room;
        broadcastRoom(room);
    }

    private void roomLeave(RelayServer.Client c, boolean notify) {
        Room room = c.room;
        if (room == null) return;
        room.members.remove(c);
        c.room = null;
        if (notify) send(c, roomJson(null, c));
        if (room.members.isEmpty()) rooms.remove(room.key);
        else broadcastRoom(room);
    }

    void broadcastRoom(Room room) {
        for (RelayServer.Client m : room.members) send(m, roomJson(room, m));
    }

    private JsonObject roomJson(Room room, RelayServer.Client viewer) {
        JsonObject o = msg("room.state");
        if (room == null) {
            o.add("room", null);
            return o;
        }
        JsonObject r = new JsonObject();
        r.addProperty("id", room.id);
        r.addProperty("key", room.key);
        JsonArray members = new JsonArray();
        for (RelayServer.Client m : room.members) {
            if (m != viewer && relay.blockedEitherWay(m.uuid, viewer.uuid)) continue;
            JsonObject e = new JsonObject();
            e.addProperty("uuid", m.uuid);
            e.addProperty("name", m.name);
            e.addProperty("key", relay.store.user(m.uuid).key);
            e.addProperty("muted", m.muted);
            e.addProperty("deafened", m.deafened);
            members.add(e);
        }
        r.add("members", members);
        o.add("room", r);
        return o;
    }

    private void roomKey(RelayServer.Client c, JsonObject o) {
        String to = str(o, "to");
        String key = str(o, "key");
        RelayServer.Client target = to == null ? null : relay.online.get(to);
        if (c.room == null || target == null || target.room != c.room || key == null || key.length() > 200
                || relay.blockedEitherWay(c.uuid, to) || !o.has("epoch")) return;
        JsonObject out = msg("room.key");
        out.addProperty("from", c.uuid);
        out.addProperty("room", c.room.id);
        out.addProperty("epoch", o.get("epoch").getAsInt());
        out.addProperty("key", key);
        send(target, out);
    }

    private void roomRekey(RelayServer.Client c) {
        if (c.room == null || !c.callInvites.take()) return;
        JsonObject r = msg("room.rekey");
        r.addProperty("from", c.uuid);
        for (RelayServer.Client m : c.room.members) if (m != c) send(m, r);
    }

    /** Voice from someone who isn't in a call: goes to their room, skipping people who are in a call. */
    void roomVoice(RelayServer.Client c, byte[] frame) {
        Room room = c.room;
        if (room == null) return;
        for (RelayServer.Client m : room.members) {
            if (m == c || m.call != null || m.deafened || !m.conn.isOpen() || relay.blockedEitherWay(c.uuid, m.uuid)) continue;
            m.conn.send(frame);
        }
    }

    // ---- Announcements ----

    /** The exact text the owner signs; the addon builds the same string. */
    static String announcementText(String author, long created, String title, String body, Long when, String serverName, String serverAddress) {
        return "vcraft-announcement\n" + author + "\n" + created + "\n" + title + "\n" + body + "\n"
                + (when == null ? "" : when) + "\n" + nz(serverName) + "\n" + nz(serverAddress);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void announcementList(RelayServer.Client c) {
        JsonObject o = msg("ann.list");
        JsonArray items = new JsonArray();
        for (Store.Announcement a : relay.store.announcements) items.add(announcementJson(a, c.uuid));
        o.add("items", items);
        send(c, o);
    }

    private void announcementPost(RelayServer.Client c, JsonObject o) {
        if (!"owner".equals(relay.store.rank(c.uuid))) {
            relay.error(c, "Only the VCraft owner can post announcements.");
            return;
        }
        if (relay.rateLimited(c)) return;
        String title = str(o, "title");
        String body = str(o, "body");
        String pub = str(o, "pub");
        String sig = str(o, "sig");
        String serverName = str(o, "serverName");
        String serverAddress = str(o, "serverAddress");
        Long when = o.has("when") && !o.get("when").isJsonNull() ? o.get("when").getAsLong() : null;
        long created = o.has("created") ? o.get("created").getAsLong() : 0;
        if (title == null || title.isBlank() || title.length() > 80 || body == null || body.length() > 1000
                || pub == null || pub.length() > 200 || sig == null || sig.length() > 200
                || (serverName != null && serverName.length() > 64) || (serverAddress != null && serverAddress.length() > 255)
                || Math.abs(System.currentTimeMillis() - created) > MAX_CLOCK_SKEW_MS) {
            relay.error(c, "That announcement isn't valid.");
            return;
        }
        if (!verify(pub, announcementText(c.uuid, created, title, body, when, serverName, serverAddress), sig)) {
            relay.error(c, "The announcement's signature didn't check out.");
            return;
        }
        Store.Announcement a = new Store.Announcement();
        a.id = relay.newId();
        a.author = c.uuid;
        a.title = title;
        a.body = body;
        a.when = when;
        a.serverName = serverName;
        a.serverAddress = serverAddress;
        a.created = created;
        a.pub = pub;
        a.sig = sig;
        relay.store.announcements.add(0, a);
        while (relay.store.announcements.size() > MAX_ANNOUNCEMENTS) relay.store.announcements.remove(relay.store.announcements.size() - 1);
        relay.store.markDirty();
        System.out.println("[relay] " + c.name + " posted an announcement: " + title);
        for (RelayServer.Client m : relay.online.values()) {
            if (!m.addons.contains(ANNOUNCEMENTS)) continue;
            JsonObject out = msg("ann.new");
            out.add("item", announcementJson(a, m.uuid));
            send(m, out);
        }
    }

    private void announcementDelete(RelayServer.Client c, String id) {
        if (!"owner".equals(relay.store.rank(c.uuid)) || id == null) return;
        if (!relay.store.announcements.removeIf(a -> id.equals(a.id))) return;
        relay.store.markDirty();
        JsonObject out = msg("ann.deleted");
        out.addProperty("id", id);
        for (RelayServer.Client m : relay.online.values()) if (m.addons.contains(ANNOUNCEMENTS)) send(m, out);
    }

    private void announcementRsvp(RelayServer.Client c, String id, boolean going) {
        if (id == null || !c.actions.take()) return;
        for (Store.Announcement a : relay.store.announcements) {
            if (!id.equals(a.id)) continue;
            if (a.going == null) a.going = new LinkedHashSet<>();
            boolean changed = going ? a.going.add(c.uuid) : a.going.remove(c.uuid);
            if (!changed) return;
            relay.store.markDirty();
            for (RelayServer.Client m : relay.online.values()) {
                if (!m.addons.contains(ANNOUNCEMENTS)) continue;
                JsonObject out = msg("ann.rsvp");
                out.addProperty("id", id);
                out.addProperty("going", a.going.size());
                out.addProperty("me", a.going.contains(m.uuid));
                send(m, out);
            }
            return;
        }
    }

    private JsonObject announcementJson(Store.Announcement a, String viewer) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id);
        o.addProperty("author", a.author);
        o.addProperty("authorName", relay.store.nameOf(a.author));
        o.addProperty("title", a.title);
        o.addProperty("body", a.body);
        if (a.when != null) o.addProperty("when", a.when);
        if (a.serverName != null) o.addProperty("serverName", a.serverName);
        if (a.serverAddress != null) o.addProperty("serverAddress", a.serverAddress);
        o.addProperty("created", a.created);
        o.addProperty("pub", a.pub);
        o.addProperty("sig", a.sig);
        o.addProperty("going", a.going == null ? 0 : a.going.size());
        o.addProperty("me", a.going != null && a.going.contains(viewer));
        return o;
    }

    private static boolean verify(String pub, String text, String sig) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pub))));
            s.update(text.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(sig));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Group rules ----

    private Store.Group memberGroup(RelayServer.Client c, String groupId) {
        Store.Group g = groupId == null ? null : relay.store.groups.get(groupId);
        return g == null || !g.members.contains(c.uuid) ? null : g;
    }

    private static boolean isAdmin(Store.Group g, String uuid) {
        return uuid.equals(g.owner) || (g.rules != null && g.rules.admins != null && g.rules.admins.contains(uuid));
    }

    private void rulesSet(RelayServer.Client c, JsonObject o) {
        Store.Group g = memberGroup(c, str(o, "group"));
        if (g == null) return;
        if (!c.uuid.equals(g.owner)) {
            relay.error(c, "Only the group owner can change its rules.");
            return;
        }
        if (!c.groupChanges.take()) {
            relay.error(c, "Slow down! Try again in a moment.");
            return;
        }
        int version = o.has("version") ? o.get("version").getAsInt() : 0;
        Store.Rules old = g.rules;
        if (old != null && version <= old.version) {
            relay.error(c, "Those rules are out of date. Reopen the rules page and try again.");
            return;
        }
        Store.Rules r = new Store.Rules();
        r.version = version;
        r.acceptVersion = o.has("acceptVersion") ? o.get("acceptVersion").getAsInt() : version;
        r.slowSeconds = Math.max(0, Math.min(3600, o.has("slowSeconds") ? o.get("slowSeconds").getAsInt() : 0));
        r.adminsOnly = bool(o, "adminsOnly");
        r.requireAccept = bool(o, "requireAccept");
        if (o.has("admins") && o.get("admins").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("admins")) {
                if (e.isJsonPrimitive() && g.members.contains(e.getAsString()) && !e.getAsString().equals(g.owner)) r.admins.add(e.getAsString());
            }
        }
        if (o.has("blobs") && o.get("blobs").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("blobs").entrySet()) {
                if (g.members.contains(e.getKey()) && e.getValue().isJsonPrimitive() && e.getValue().getAsString().length() <= MAX_BLOB) {
                    r.blobs.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        if (old != null) {
            r.mutes = old.mutes == null ? r.mutes : old.mutes;
            // Accepting stays valid until the owner changes the rules text.
            if (old.accepted != null) old.accepted.forEach((u, v) -> {
                if (v >= r.acceptVersion) r.accepted.put(u, v);
            });
        }
        r.accepted.put(g.owner, r.acceptVersion);
        g.rules = r;
        relay.store.markDirty();
        for (String member : g.members) {
            RelayServer.Client mc = relay.online.get(member);
            if (mc != null) send(mc, rulesJson(g, member));
        }
        relay.groupNotice(g, c.name + " updated the group rules.");
        requestMissingBlobs(g);
    }

    private JsonObject rulesJson(Store.Group g, String viewer) {
        JsonObject o = msg("grule.update");
        o.addProperty("group", g.id);
        Store.Rules r = g.rules;
        if (r == null) {
            o.add("rules", null);
            return o;
        }
        JsonObject p = new JsonObject();
        p.addProperty("version", r.version);
        p.addProperty("acceptVersion", r.acceptVersion);
        p.addProperty("slowSeconds", r.slowSeconds);
        p.addProperty("adminsOnly", r.adminsOnly);
        p.addProperty("requireAccept", r.requireAccept);
        JsonArray admins = new JsonArray();
        r.admins.forEach(admins::add);
        p.add("admins", admins);
        JsonObject mutes = new JsonObject();
        long now = System.currentTimeMillis();
        if (r.mutes != null) r.mutes.forEach((u, until) -> {
            if (until > now) mutes.addProperty(u, until);
        });
        p.add("mutes", mutes);
        String blob = r.blobs == null ? null : r.blobs.get(viewer);
        if (blob != null) p.addProperty("blob", blob);
        Integer acc = r.accepted == null ? null : r.accepted.get(viewer);
        p.addProperty("accepted", acc != null && acc >= r.acceptVersion);
        o.add("rules", p);
        return o;
    }

    private void rulesGet(RelayServer.Client c, String groupId, boolean refresh) {
        Store.Group g = memberGroup(c, groupId);
        if (g == null || !c.history.take()) return;
        if (refresh && g.rules != null && g.rules.blobs != null && g.rules.blobs.remove(c.uuid) != null) relay.store.markDirty();
        send(c, rulesJson(g, c.uuid));
        if (g.rules != null && (g.rules.blobs == null || !g.rules.blobs.containsKey(c.uuid))) requestMissingBlobs(g);
    }

    /** Members who can read the rules re-seal them for members who joined later. The owner's signature stops tampering. */
    private void requestMissingBlobs(Store.Group g) {
        Store.Rules r = g.rules;
        if (r == null || r.blobs == null || r.blobs.isEmpty()) return;
        for (String missing : g.members) {
            if (r.blobs.containsKey(missing)) continue;
            JsonObject need = msg("grule.need");
            need.addProperty("group", g.id);
            need.addProperty("uuid", missing);
            for (String holder : r.blobs.keySet()) {
                RelayServer.Client hc = relay.online.get(holder);
                if (hc != null && hc.addons.contains(GROUP_RULES)) send(hc, need);
            }
        }
    }

    private void rulesShare(RelayServer.Client c, String groupId, String to, String blob) {
        Store.Group g = memberGroup(c, groupId);
        if (g == null || g.rules == null || to == null || blob == null || blob.length() > MAX_BLOB || !g.members.contains(to)) return;
        if (g.rules.blobs == null) g.rules.blobs = new java.util.LinkedHashMap<>();
        if (!g.rules.blobs.containsKey(c.uuid) || g.rules.blobs.containsKey(to)) return;
        g.rules.blobs.put(to, blob);
        relay.store.markDirty();
        RelayServer.Client target = relay.online.get(to);
        if (target != null) send(target, rulesJson(g, to));
    }

    private void rulesAccept(RelayServer.Client c, String groupId, int version) {
        Store.Group g = memberGroup(c, groupId);
        if (g == null || g.rules == null || version != g.rules.acceptVersion) return;
        g.rules.accepted.put(c.uuid, version);
        relay.store.markDirty();
        send(c, rulesJson(g, c.uuid));
    }

    private void rulesMute(RelayServer.Client c, String groupId, String target, int minutes) {
        Store.Group g = memberGroup(c, groupId);
        if (g == null || target == null || !g.members.contains(target) || target.equals(c.uuid)) return;
        boolean owner = c.uuid.equals(g.owner);
        if (!isAdmin(g, c.uuid) || target.equals(g.owner) || (!owner && isAdmin(g, target))) {
            relay.error(c, "You can't mute them in this group.");
            return;
        }
        if (g.rules == null) {
            g.rules = new Store.Rules();
            g.rules.accepted.put(g.owner, 0);
        }
        if (g.rules.mutes == null) g.rules.mutes = new java.util.LinkedHashMap<>();
        minutes = Math.max(0, Math.min(60 * 24 * 7, minutes));
        if (minutes == 0) g.rules.mutes.remove(target);
        else g.rules.mutes.put(target, System.currentTimeMillis() + minutes * 60_000L);
        relay.store.markDirty();
        relay.groupNotice(g, minutes == 0 ? c.name + " unmuted " + relay.store.nameOf(target) + "."
                : c.name + " muted " + relay.store.nameOf(target) + " for " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".");
        for (String member : g.members) {
            RelayServer.Client mc = relay.online.get(member);
            if (mc != null) send(mc, rulesJson(g, member));
        }
    }

    /** Rules that don't depend on message content. Owners are never limited; admins skip slow mode and admins-only. */
    boolean allowGroupPost(RelayServer.Client c, Store.Group g) {
        Store.Rules r = g.rules;
        if (r == null || c.uuid.equals(g.owner)) return true;
        long now = System.currentTimeMillis();
        Long until = r.mutes == null ? null : r.mutes.get(c.uuid);
        if (until != null && until > now) {
            long mins = Math.max(1, (until - now + 59_999) / 60_000);
            relay.error(c, "You're muted in this group for " + mins + " more minute" + (mins == 1 ? "" : "s") + ".");
            return false;
        }
        boolean admin = isAdmin(g, c.uuid);
        if (r.adminsOnly && !admin) {
            relay.error(c, "Only admins can post in this group.");
            return false;
        }
        if (r.requireAccept) {
            Integer acc = r.accepted == null ? null : r.accepted.get(c.uuid);
            if (acc == null || acc < r.acceptVersion) {
                relay.error(c, "Accept this group's rules before posting. You need the VCraft Group Rules addon to read them.");
                return false;
            }
        }
        if (r.slowSeconds > 0 && !admin) {
            String key = g.id + "|" + c.uuid;
            Long last = lastGroupPost.get(key);
            if (last != null && now - last < r.slowSeconds * 1000L) {
                long wait = (r.slowSeconds * 1000L - (now - last) + 999) / 1000;
                relay.error(c, "Slow mode is on: wait " + wait + "s.");
                return false;
            }
            lastGroupPost.put(key, now);
        }
        return true;
    }

    /** Owners and admins can remove members; admins can't remove the owner or other admins. */
    boolean canKick(Store.Group g, String by, String target) {
        if (by.equals(g.owner)) return true;
        return isAdmin(g, by) && !target.equals(g.owner) && !isAdmin(g, target);
    }

    void memberRemoved(Store.Group g, String uuid) {
        Store.Rules r = g.rules;
        if (r == null) return;
        if (r.admins != null) r.admins.remove(uuid);
        if (r.blobs != null) r.blobs.remove(uuid);
        if (r.accepted != null) r.accepted.remove(uuid);
        if (r.mutes != null) r.mutes.remove(uuid);
        lastGroupPost.remove(g.id + "|" + uuid);
    }

    void memberAdded(Store.Group g) {
        requestMissingBlobs(g);
    }
}
