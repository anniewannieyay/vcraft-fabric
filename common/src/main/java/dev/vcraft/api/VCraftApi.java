package dev.vcraft.api;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/**
 * What VCraft offers addons. The same API on every supported Minecraft version, and it never exposes
 * Minecraft classes, so an addon built against it is one jar for all of them.
 * Callbacks run on the client thread unless noted otherwise.
 */
public interface VCraftApi {
    int API_VERSION = 1;

    String vcraftVersion();

    /** The signed-in player, or null fields while not connected. */
    String myUuid();

    String myName();

    /** "owner", "mod", "tester" or null. */
    String myRank();

    boolean connected();

    Events events();

    Relay relay();

    Ui ui();

    Messages messages();

    Voice voice();

    Game game();

    Crypto crypto();

    Signing signing();

    List<GroupInfo> groups();

    /** Group for a conversation id ("g:" + group id), or null. */
    GroupInfo group(String conv);

    /** A pop-up in the corner of the screen. */
    void toast(String title, String body);

    /** A line in Minecraft chat, tagged like VCraft's own. */
    void chat(String tag, int tagColor, String body);

    /** A short message at the top of the VCraft screen. */
    void notice(String text, boolean error);

    /** A private folder for the addon's own files (config/vcraft/addons/&lt;id&gt;). */
    Path dataDir(String addonId);

    void runOnClient(Runnable task);

    record ChatMessage(String conv, String id, String from, String fromName, String text, long ts, boolean encrypted) {}

    record Member(String uuid, String name, boolean online) {}

    record GroupInfo(String id, String conv, String name, String owner, List<Member> members) {}

    interface Events {
        /** Each time VCraft signs in to the relay (also after reconnecting). */
        void onRelayConnected(Runnable listener);

        /** A message arrived in a DM, group or the community chat (after message filters). */
        void onMessage(Consumer<ChatMessage> listener);

        void onCallJoined(Consumer<String> listener);

        void onCallLeft(Runnable listener);

        /** Someone in your call or voice room started (true) or stopped (false) talking. You're included. */
        void onTalking(BiConsumer<String, Boolean> listener);

        /** Every client tick (20 per second). */
        void onTick(Runnable listener);
    }

    /**
     * The addon's own relay messages. Types must contain a dot ("ann.post") so they never clash with VCraft's.
     * The relay only understands the types it has handlers for; others are ignored.
     */
    interface Relay {
        void send(String type, JsonObject body);

        void on(String type, Consumer<JsonObject> handler);
    }

    interface Ui {
        void addPage(Page page);

        void addChatButton(ChatButton button);

        void addHud(Hud hud);

        /** Opens the VCraft screen on an addon page. {@code arg} is passed to {@link Page#opened}. */
        void openPage(String pageId, String arg);

        /** Asks the player to confirm, then connects to a Minecraft server. */
        void confirmJoinServer(String name, String address);

        /** Opens a link after Minecraft's usual "are you sure" prompt. */
        void openLink(String url);

        /**
         * Draws VCraft's speaker icon above a player's head in the world (above the nametag, facing the camera)
         * while {@code showFor} returns true for their uuid. VCraft skips players whose nametag would be hidden,
         * such as invisible or sneaking players. Called on the render thread every frame, so keep it fast.
         */
        void addSpeakingIcon(java.util.function.Predicate<String> showFor);
    }

    /** A page in the VCraft screen. Shown in the sidebar unless {@link #inSidebar} is false. */
    interface Page {
        String id();

        String title();

        /** 9x9 pixel icon, '#' for filled pixels. */
        String[] icon();

        default boolean inSidebar() {
            return true;
        }

        default int badge() {
            return 0;
        }

        default void opened(String arg) {
        }

        void render(Canvas canvas, int x, int y, int width, int height);
    }

    /** An extra button in a conversation's header bar. */
    interface ChatButton {
        String[] icon();

        String tooltip(String conv);

        boolean visible(String conv);

        void click(String conv);
    }

    /** Draws on the in-game overlay, under VCraft's call list. Returns the y to continue from. */
    interface Hud {
        int render(Canvas canvas, int x, int y);
    }

    interface Messages {
        /** Decide how a received message is shown. Applies to new messages and history. */
        void addFilter(MessageFilter filter);

        /** Runs before a message is sent. Return an error to block it, or null to allow it. */
        void addSendCheck(SendCheck check);
    }

    interface MessageFilter {
        Verdict check(ChatMessage message);
    }

    interface SendCheck {
        String check(String conv, String text);
    }

    record Verdict(Action action, String text) {
        public enum Action { SHOW, HIDE, REPLACE }

        public static final Verdict SHOW = new Verdict(Action.SHOW, null);
        public static final Verdict HIDE = new Verdict(Action.HIDE, null);

        public static Verdict replace(String text) {
            return new Verdict(Action.REPLACE, text);
        }
    }

    /**
     * Voice rooms: everyone who joins the same room key hears each other, end-to-end encrypted with the same
     * per-speaker keys as calls. Paused while you're in a normal call. One room at a time.
     */
    interface Voice {
        /** Key of letters, digits, ':' and '-' (3 to 80 chars). The relay sees it, so hash anything private. */
        void joinRoom(String roomKey);

        void leaveRoom();

        String room();

        List<Member> roomMembers();

        /** Volume per speaker in the room, 0 (silent) to 1. Called from the audio thread, so keep it fast. */
        void setRoomGain(ToDoubleFunction<String> gain);

        boolean isTalking(String uuid);

        boolean inCall();

        boolean muted();

        boolean deafened();
    }

    /** Read-only facts about the game. */
    interface Game {
        record Position(double x, double y, double z, String dimension) {
            public double distanceTo(Position o) {
                double dx = x - o.x, dy = y - o.y, dz = z - o.z;
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }

        /** The multiplayer server's address and name, or null in singleplayer or the menus. */
        String serverAddress();

        String serverName();

        Position myPosition();

        /** Other players loaded in your world, by uuid. */
        Map<String, Position> playerPositions();
    }

    /**
     * End-to-end encryption between you and one other player, using VCraft's identity keys. Only works with
     * players whose key is trusted. The context string is bound into the ciphertext, so a payload sealed for
     * one purpose can't be replayed as another.
     */
    interface Crypto {
        /** Returns null if their key isn't trusted. */
        String seal(String toUuid, String context, String plaintext);

        /** Returns null if it doesn't decrypt. */
        String open(String fromUuid, String context, String sealed);

        enum Trust { TRUSTED, CHANGED, NONE }

        /**
         * Whether you can encrypt to this player. CHANGED means their key differs from the one you saw first
         * (they reinstalled, or someone is impersonating them): nothing is sent to them until you trust the new key.
         */
        Trust trust(String uuid);

        /** Numbers both players can compare to check a key, or null if there's no key. */
        String safetyNumber(String uuid);

        /** Accepts a player's changed key. Only after the player confirms the safety number, ideally. */
        void trustNewKey(String uuid);
    }

    /** An Ed25519 signing key that belongs to this player, kept on this computer. */
    interface Signing {
        String publicKey();

        String sign(String text);

        boolean verify(String publicKey, String text, String signature);
    }
}
