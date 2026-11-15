package dev.vcraft.addon.announcements;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.vcraft.api.VCraftAddon;
import dev.vcraft.api.VCraftApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event announcements from the VCraft owner. Public, not end-to-end encrypted, but signed: the relay only
 * accepts them from the Owner rank, and every client checks the owner's signature and remembers their key.
 */
public final class AnnouncementsAddon implements VCraftAddon {
    static final String ID = "vcraft-announcements";
    private static final Logger LOG = LoggerFactory.getLogger("VCraft Announcements");
    private static final Gson GSON = new Gson();

    enum Trust { VERIFIED, KEY_CHANGED, INVALID }

    record Item(String id, String author, String authorName, String title, String body, Long when, String serverName,
                String serverAddress, long created, String pub, String sig, int going, boolean me, Trust trust) {
        Item withRsvp(int going, boolean me) {
            return new Item(id, author, authorName, title, body, when, serverName, serverAddress, created, pub, sig, going, me, trust);
        }
    }

    static final class Settings {
        boolean popups = true;
        long lastSeen;
    }

    VCraftApi api;
    private Path dir;
    Settings settings = new Settings();
    private Map<String, String> pins = new HashMap<>();
    volatile List<Item> items = List.of();
    private boolean loaded;

    @Override
    public void onInitialize(VCraftApi api) {
        this.api = api;
        dir = api.dataDir(ID);
        settings = read("settings.json", Settings.class, new Settings());
        Map<String, String> p = read("pins.json", new TypeToken<HashMap<String, String>>() {}.getType(), null);
        if (p != null) pins = p;

        api.events().onRelayConnected(() -> api.relay().send("ann.list", null));
        api.relay().on("ann.list", o -> {
            List<Item> list = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("items")) list.add(parse(e.getAsJsonObject()));
            items = list;
            if (!loaded && settings.lastSeen == 0 && !list.isEmpty()) markSeen();
            loaded = true;
        });
        api.relay().on("ann.new", o -> {
            Item it = parse(o.getAsJsonObject("item"));
            List<Item> list = new ArrayList<>(items);
            list.removeIf(i -> i.id().equals(it.id()));
            list.add(0, it);
            items = list;
            if (it.trust() == Trust.INVALID) return;
            api.chat("Announcement", 0xFFF0B232, it.title());
            if (settings.popups) api.toast("VCraft announcement", it.title());
        });
        api.relay().on("ann.deleted", o -> {
            String id = o.get("id").getAsString();
            List<Item> list = new ArrayList<>(items);
            list.removeIf(i -> i.id().equals(id));
            items = list;
        });
        api.relay().on("ann.rsvp", o -> {
            String id = o.get("id").getAsString();
            List<Item> list = new ArrayList<>();
            for (Item i : items) list.add(i.id().equals(id) ? i.withRsvp(o.get("going").getAsInt(), o.get("me").getAsBoolean()) : i);
            items = list;
        });
        api.ui().addPage(new AnnouncementsPage(this));
    }

    /** The exact text the owner signs. The relay checks the same string before accepting an announcement. */
    static String signedText(String author, long created, String title, String body, Long when, String serverName, String serverAddress) {
        return "vcraft-announcement\n" + author + "\n" + created + "\n" + title + "\n" + body + "\n"
                + (when == null ? "" : when) + "\n" + nz(serverName) + "\n" + nz(serverAddress);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private Item parse(JsonObject o) {
        String author = str(o, "author");
        String title = str(o, "title");
        String body = str(o, "body");
        Long when = o.has("when") ? o.get("when").getAsLong() : null;
        String serverName = str(o, "serverName");
        String serverAddress = str(o, "serverAddress");
        long created = o.get("created").getAsLong();
        String pub = str(o, "pub");
        String sig = str(o, "sig");
        Trust trust;
        if (pub == null || sig == null || !api.signing().verify(pub, signedText(author, created, title, body, when, serverName, serverAddress), sig)) {
            trust = Trust.INVALID;
        } else {
            String pinned = pins.get(author);
            if (pinned == null) {
                pins.put(author, pub);
                write("pins.json", pins);
                trust = Trust.VERIFIED;
            } else {
                trust = pinned.equals(pub) ? Trust.VERIFIED : Trust.KEY_CHANGED;
            }
        }
        return new Item(str(o, "id"), author, str(o, "authorName"), title, body == null ? "" : body, when, serverName, serverAddress,
                created, pub, sig, o.has("going") ? o.get("going").getAsInt() : 0, o.has("me") && o.get("me").getAsBoolean(), trust);
    }

    /** After the owner moved to a new computer: accept their new signing key. */
    void trustNewKey(Item it) {
        pins.put(it.author(), it.pub());
        write("pins.json", pins);
        api.relay().send("ann.list", null);
    }

    void post(String title, String body, Long when, boolean inviteHere) {
        String serverName = inviteHere ? api.game().serverName() : null;
        String serverAddress = inviteHere ? api.game().serverAddress() : null;
        long created = System.currentTimeMillis();
        String sig = api.signing().sign(signedText(api.myUuid(), created, title, body, when, serverName, serverAddress));
        if (sig == null) {
            api.notice("Couldn't sign the announcement.", true);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("title", title);
        o.addProperty("body", body);
        if (when != null) o.addProperty("when", when);
        if (serverAddress != null) {
            o.addProperty("serverName", serverName);
            o.addProperty("serverAddress", serverAddress);
        }
        o.addProperty("created", created);
        o.addProperty("pub", api.signing().publicKey());
        o.addProperty("sig", sig);
        api.relay().send("ann.post", o);
    }

    void delete(Item it) {
        JsonObject o = new JsonObject();
        o.addProperty("id", it.id());
        api.relay().send("ann.delete", o);
    }

    void rsvp(Item it, boolean going) {
        JsonObject o = new JsonObject();
        o.addProperty("id", it.id());
        o.addProperty("going", going);
        api.relay().send("ann.rsvp", o);
    }

    int unseen() {
        int n = 0;
        for (Item i : items) if (i.created() > settings.lastSeen && i.trust() != Trust.INVALID) n++;
        return n;
    }

    void markSeen() {
        long newest = 0;
        for (Item i : items) newest = Math.max(newest, i.created());
        if (newest <= settings.lastSeen) return;
        settings.lastSeen = newest;
        write("settings.json", settings);
    }

    void setPopups(boolean on) {
        settings.popups = on;
        write("settings.json", settings);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private <T> T read(String file, java.lang.reflect.Type type, T fallback) {
        try {
            Path p = dir.resolve(file);
            if (!Files.exists(p)) return fallback;
            T v = GSON.fromJson(Files.readString(p), type);
            return v == null ? fallback : v;
        } catch (Exception e) {
            LOG.warn("Couldn't read {}", file, e);
            return fallback;
        }
    }

    private void write(String file, Object value) {
        try {
            Files.writeString(dir.resolve(file), GSON.toJson(value));
        } catch (Exception e) {
            LOG.warn("Couldn't save {}", file, e);
        }
    }
}
