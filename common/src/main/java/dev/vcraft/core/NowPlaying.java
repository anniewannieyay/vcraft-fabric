package dev.vcraft.core;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional support for the Now Playing mod, which detects the song playing on the computer.
 * Accessed by reflection so VCraft works the same with or without it installed.
 */
public final class NowPlaying {
    public static final String MOD_ID = "nowplaying";
    private static final int MAX_LENGTH = 120;

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded(MOD_ID);
    private static Method current;
    private static Field sharing;
    private static boolean broken;

    public static boolean installed() {
        return LOADED && !broken;
    }

    /** The song Now Playing detected, or null if nothing is playing (or the mod isn't installed). */
    public static synchronized String current() {
        if (!installed()) return null;
        try {
            if (current == null) current = Class.forName("io.github.nowplaying.client.MediaWatcher").getMethod("current");
            return current.invoke(null) instanceof String s ? clean(s) : null;
        } catch (Throwable t) {
            broken = true;
            VCraft.LOGGER.warn("Couldn't read the song from Now Playing; disabling the integration", t);
            return null;
        }
    }

    /** Whether song sharing is switched on in Now Playing itself. VCraft respects it too. */
    public static synchronized boolean sharingEnabled() {
        if (!installed()) return false;
        try {
            if (sharing == null) sharing = Class.forName("io.github.nowplaying.client.NowPlayingConfig").getField("sharing");
            return sharing.getBoolean(null);
        } catch (Throwable t) {
            return true;
        }
    }

    public static String clean(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder();
        s.codePoints().filter(cp -> !Character.isISOControl(cp) && cp != 0xA7).forEach(b::appendCodePoint);
        String out = b.toString().trim();
        if (out.length() > MAX_LENGTH) out = out.substring(0, MAX_LENGTH).trim();
        return out.isEmpty() ? null : out;
    }

    private NowPlaying() {
    }
}
