package dev.vcraft.addon.proximity;

import com.google.gson.Gson;
import dev.vcraft.api.Canvas;
import dev.vcraft.api.VCraftAddon;
import dev.vcraft.api.VCraftApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proximity chat: VCraft players on the same Minecraft server share a voice room, and each client turns
 * voices down by in-game distance. No server-side mod: positions come from the players your game can see.
 */
public final class ProximityAddon implements VCraftAddon {
    static final String ID = "vcraft-proximity";
    static final int MIN_RANGE = 8, MAX_RANGE = 128;
    private static final double FULL_VOLUME_BLOCKS = 6;
    private static final Logger LOG = LoggerFactory.getLogger("VCraft Proximity");
    private static final Gson GSON = new Gson();

    static final String[] ICON = {
            "...#.....",
            "..##..#..",
            "###.#..#.",
            "###.#.#.#",
            "###.#.#.#",
            "###.#.#.#",
            "###.#..#.",
            "..##..#..",
            "...#....."};

    static final class Settings {
        boolean enabled = false;
        int range = 48;
        boolean speakingIcons = true;
    }

    record Nearby(String uuid, String name, double distance, double gain, VCraftApi.Crypto.Trust trust) {}

    private VCraftApi api;
    private Path settingsFile;
    Settings settings = new Settings();
    private volatile Map<String, Double> gains = Map.of();
    private volatile List<Nearby> nearby = List.of();
    private int ticks;

    @Override
    public void onInitialize(VCraftApi api) {
        this.api = api;
        settingsFile = api.dataDir(ID).resolve("settings.json");
        load();
        api.voice().setRoomGain(uuid -> gains.getOrDefault(uuid, 0.0));
        api.events().onTick(this::tick);
        api.ui().addPage(new ProximityPage(this, api));
        api.ui().addHud(this::hud);
        api.ui().addSpeakingIcon(this::showSpeakingIcon);
    }

    private void tick() {
        if (++ticks % 5 != 0) return;
        String address = settings.enabled && api.connected() ? api.game().serverAddress() : null;
        if (address == null) {
            if (api.voice().room() != null) api.voice().leaveRoom();
            gains = Map.of();
            nearby = List.of();
            return;
        }
        api.voice().joinRoom(roomKey(address));
        updateGains();
    }

    /** Volume for everyone in the room from their distance to you; players you can't see are silent. */
    private void updateGains() {
        VCraftApi.Game.Position me = api.game().myPosition();
        Map<String, VCraftApi.Game.Position> players = api.game().playerPositions();
        Map<String, Double> g = new HashMap<>();
        List<Nearby> list = new ArrayList<>();
        for (VCraftApi.Member m : api.voice().roomMembers()) {
            if (m.uuid().equals(api.myUuid())) continue;
            VCraftApi.Game.Position p = players.get(m.uuid());
            double d = me == null || p == null || !p.dimension().equals(me.dimension()) ? Double.POSITIVE_INFINITY : me.distanceTo(p);
            double gain = gain(d, settings.range);
            g.put(m.uuid(), gain);
            list.add(new Nearby(m.uuid(), m.name(), d, gain, api.crypto().trust(m.uuid())));
        }
        list.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        gains = g;
        nearby = list;
    }

    static double gain(double distance, int range) {
        if (distance <= FULL_VOLUME_BLOCKS) return 1;
        if (distance >= range) return 0;
        double t = (distance - FULL_VOLUME_BLOCKS) / (range - FULL_VOLUME_BLOCKS);
        return 1 - t * t * (3 - 2 * t);
    }

    /**
     * The relay only sees a hash of the server address. Anyone who knows the address can compute the same
     * hash, so this hides it from casual view but doesn't make it secret.
     */
    static String roomKey(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        if (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("vcraft-proximity|" + a).getBytes(StandardCharsets.UTF_8));
            return "mc:" + HexFormat.of().formatHex(h).substring(0, 40);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Speaker icon above a nearby player who is talking to you in proximity chat right now. */
    private boolean showSpeakingIcon(String uuid) {
        if (!settings.enabled || !settings.speakingIcons || api.voice().room() == null || api.voice().inCall() || api.voice().deafened()) return false;
        // Players you muted or blocked never count as talking, because their audio is dropped before it plays.
        return gains.getOrDefault(uuid, 0.0) > 0 && api.voice().isTalking(uuid);
    }

    void setSpeakingIcons(boolean on) {
        settings.speakingIcons = on;
        save();
    }

    List<Nearby> nearby() {
        return nearby;
    }

    private int hud(Canvas c, int x, int y) {
        if (!settings.enabled || api.voice().room() == null) return y;
        if (api.voice().inCall()) {
            String s = "Proximity paused during your call";
            c.panel(x - 2, y - 2, c.width(s) + 6, 13, 0xA0000000);
            c.text(s, x + 1, y, Canvas.MUTED);
            return y + 15;
        }
        int inRange = 0;
        for (Nearby n : nearby) if (n.gain() > 0) inRange++;
        String head = "Proximity: " + inRange + " nearby";
        c.panel(x - 2, y - 2, c.width(head) + 6, 13, 0xA0000000);
        c.text(head, x + 1, y, 0xFF8FDDA8);
        y += 14;
        int unheard = 0;
        for (Nearby n : nearby) if (n.gain() > 0 && n.trust() != VCraftApi.Crypto.Trust.TRUSTED) unheard++;
        if (unheard > 0) {
            String warn = unheard + (unheard == 1 ? " player" : " players") + " can't be heard: key changed (see Proximity page)";
            c.panel(x - 2, y - 2, c.width(warn) + 6, 13, 0xA0000000);
            c.text(warn, x + 1, y, Canvas.GOLD);
            y += 14;
        }
        for (Nearby n : nearby) {
            if (n.gain() <= 0 || !api.voice().isTalking(n.uuid())) continue;
            String dist = Math.round(n.distance()) + "m";
            int w = c.width(n.name()) + c.width(dist) + 24;
            c.panel(x - 2, y - 2, w, 13, 0xA0000000);
            c.fill(x - 1, y - 1, 11, 11, Canvas.GREEN);
            c.face(n.uuid(), x, y, 9);
            c.text(n.name(), x + 13, y, Canvas.WHITE);
            c.text(dist, x + 17 + c.width(n.name()), y, Canvas.MUTED);
            y += 14;
        }
        return y;
    }

    void setEnabled(boolean on) {
        settings.enabled = on;
        save();
        if (!on) {
            api.voice().leaveRoom();
            gains = Map.of();
            nearby = List.of();
        }
    }

    void setRange(int range) {
        settings.range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, range));
        save();
    }

    private void load() {
        try {
            if (Files.exists(settingsFile)) {
                Settings s = GSON.fromJson(Files.readString(settingsFile), Settings.class);
                if (s != null) settings = s;
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read proximity settings", e);
        }
        settings.range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, settings.range));
    }

    private void save() {
        try {
            Files.writeString(settingsFile, GSON.toJson(settings));
        } catch (Exception e) {
            LOG.warn("Couldn't save proximity settings", e);
        }
    }
}
