package dev.vcraft.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VCraft {
    public static final String MOD_ID = "vcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger("VCraft");

    private static Platform platform;
    private static Config config;
    private static final RelayConnection RELAY = new RelayConnection();
    private static final VoiceEngine VOICE = new VoiceEngine();

    public static void init(Platform p) {
        platform = p;
        config = Config.load(p.configDir());
        State.myStatus = config.status;
    }

    public static Platform platform() {
        return platform;
    }

    public static Config config() {
        return config;
    }

    public static RelayConnection relay() {
        return RELAY;
    }

    public static VoiceEngine voice() {
        return VOICE;
    }

    private VCraft() {
    }
}
