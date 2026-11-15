package dev.vcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vcraft.api.VCraftAddon;
import dev.vcraft.api.VCraftApi;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/** Loads VCraft addons and implements {@link VCraftApi} for them. With no addons installed, nothing here runs. */
public final class AddonHost implements VCraftApi {
    public static final AddonHost INSTANCE = new AddonHost();

    private final List<String> loaded = new ArrayList<>();
    private final List<Runnable> connected = new CopyOnWriteArrayList<>();
    private final List<Consumer<ChatMessage>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> callJoined = new CopyOnWriteArrayList<>();
    private final List<Runnable> callLeft = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<String, Boolean>> talking = new CopyOnWriteArrayList<>();
    private final List<Runnable> ticks = new CopyOnWriteArrayList<>();
    private final Map<String, List<Consumer<JsonObject>>> handlers = new ConcurrentHashMap<>();
    public final List<Page> pages = new CopyOnWriteArrayList<>();
    public final List<ChatButton> chatButtons = new CopyOnWriteArrayList<>();
    public final List<Hud> huds = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Predicate<String>> speakingIcons = new CopyOnWriteArrayList<>();
    private final List<MessageFilter> filters = new CopyOnWriteArrayList<>();
    private final List<SendCheck> sendChecks = new CopyOnWriteArrayList<>();
    private volatile ToDoubleFunction<String> roomGain = uuid -> 1.0;
    private Set<String> wasTalking = new HashSet<>();
    private KeyPair signingKey;

    public static void loadAddons() {
        for (EntrypointContainer<VCraftAddon> c : FabricLoader.getInstance().getEntrypointContainers("vcraft", VCraftAddon.class)) {
            String id = c.getProvider().getMetadata().getId();
            try {
                c.getEntrypoint().onInitialize(INSTANCE);
                INSTANCE.loaded.add(id);
                VCraft.LOGGER.info("Loaded VCraft addon {}", id);
            } catch (Throwable t) {
                VCraft.LOGGER.error("VCraft addon {} failed to start and was skipped", id, t);
            }
        }
    }

    public boolean any() {
        return !loaded.isEmpty();
    }

    // ---- Hooks called by VCraft ----

    void fireConnected() {
        if (loaded.isEmpty()) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "addons");
        JsonArray list = new JsonArray();
        loaded.forEach(list::add);
        o.add("list", list);
        VCraft.relay().send(o);
        for (Runnable r : connected) safely(r);
    }

    void fireMessage(String conv, State.Message m) {
        if (messageListeners.isEmpty() || m.system()) return;
        ChatMessage cm = toApi(conv, m);
        for (Consumer<ChatMessage> l : messageListeners) safely(() -> l.accept(cm));
    }

    void fireCall(State.Call before, State.Call after) {
        if (before == null && after != null) for (Consumer<String> l : callJoined) safely(() -> l.accept(after.id()));
        if (before != null && after == null) for (Runnable l : callLeft) safely(l);
    }

    void tick() {
        if (loaded.isEmpty()) return;
        if (!talking.isEmpty()) {
            Set<String> now = new HashSet<>();
            for (String uuid : State.lastSpoke.keySet()) if (State.isSpeaking(uuid)) now.add(uuid);
            for (String uuid : now) if (!wasTalking.contains(uuid)) for (BiConsumer<String, Boolean> l : talking) safely(() -> l.accept(uuid, true));
            for (String uuid : wasTalking) if (!now.contains(uuid)) for (BiConsumer<String, Boolean> l : talking) safely(() -> l.accept(uuid, false));
            wasTalking = now;
        }
        for (Runnable r : ticks) safely(r);
    }

    /** Relay messages VCraft doesn't handle itself. */
    void dispatch(String type, JsonObject o) {
        List<Consumer<JsonObject>> list = handlers.get(type);
        if (list == null) return;
        for (Consumer<JsonObject> h : list) safely(() -> h.accept(o));
    }

    /** Null if the message should be hidden. */
    State.Message filter(String conv, State.Message m) {
        if (filters.isEmpty() || m.system() || m.id() == null) return m;
        ChatMessage cm = toApi(conv, m);
        for (MessageFilter f : filters) {
            Verdict v;
            try {
                v = f.check(cm);
            } catch (Throwable t) {
                VCraft.LOGGER.warn("VCraft addon message filter failed", t);
                continue;
            }
            if (v == null || v.action() == Verdict.Action.SHOW) continue;
            if (v.action() == Verdict.Action.HIDE) return null;
            m = new State.Message(m.id(), m.from(), m.fromName(), v.text() == null ? "" : v.text(), m.ts(), m.secure(),
                    m.reply(), m.invite(), false, m.song());
            cm = toApi(conv, m);
        }
        return m;
    }

    /** An error to show instead of sending, or null. */
    String checkSend(String conv, String text) {
        for (SendCheck c : sendChecks) {
            try {
                String err = c.check(conv, text);
                if (err != null) return err;
            } catch (Throwable t) {
                VCraft.LOGGER.warn("VCraft addon send check failed", t);
            }
        }
        return null;
    }

    double roomGain(String uuid) {
        try {
            return Math.max(0, Math.min(1, roomGain.applyAsDouble(uuid)));
        } catch (Throwable t) {
            return 1;
        }
    }

    private static ChatMessage toApi(String conv, State.Message m) {
        return new ChatMessage(conv, m.id(), m.from(), m.fromName(), m.text(), m.ts(), !State.LOBBY.equals(conv) && m.secure());
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            VCraft.LOGGER.warn("A VCraft addon threw an exception", t);
        }
    }

    // ---- VCraftApi ----

    @Override
    public String vcraftVersion() {
        return AutoUpdate.currentVersion();
    }

    @Override
    public String myUuid() {
        return State.myUuid;
    }

    @Override
    public String myName() {
        return State.myName;
    }

    @Override
    public String myRank() {
        return State.myRank;
    }

    @Override
    public boolean connected() {
        return VCraft.relay().connected() && State.myUuid != null;
    }

    private final Events events = new Events() {
        public void onRelayConnected(Runnable l) {
            connected.add(l);
        }

        public void onMessage(Consumer<ChatMessage> l) {
            messageListeners.add(l);
        }

        public void onCallJoined(Consumer<String> l) {
            callJoined.add(l);
        }

        public void onCallLeft(Runnable l) {
            callLeft.add(l);
        }

        public void onTalking(BiConsumer<String, Boolean> l) {
            talking.add(l);
        }

        public void onTick(Runnable l) {
            ticks.add(l);
        }
    };

    @Override
    public Events events() {
        return events;
    }

    private final Relay relay = new Relay() {
        public void send(String type, JsonObject body) {
            checkType(type);
            JsonObject o = body == null ? new JsonObject() : body.deepCopy();
            o.addProperty("t", type);
            VCraft.relay().send(o);
        }

        public void on(String type, Consumer<JsonObject> handler) {
            checkType(type);
            handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        }

        private void checkType(String type) {
            if (type == null || !type.contains(".") || type.startsWith("room.")) {
                throw new IllegalArgumentException("Addon relay types need a dot and can't use room.*: " + type);
            }
        }
    };

    @Override
    public Relay relay() {
        return relay;
    }

    private final Ui ui = new Ui() {
        public void addPage(Page page) {
            pages.add(page);
        }

        public void addChatButton(ChatButton button) {
            chatButtons.add(button);
        }

        public void addHud(Hud hud) {
            huds.add(hud);
        }

        public void openPage(String pageId, String arg) {
            VCraft.platform().openAddonPage(pageId, arg);
        }

        public void confirmJoinServer(String name, String address) {
            VCraft.platform().confirmJoinServer(name, address);
        }

        public void openLink(String url) {
            VCraft.platform().openLink(url);
        }

        public void addSpeakingIcon(java.util.function.Predicate<String> showFor) {
            speakingIcons.add(showFor);
        }
    };

    /** Whether any addon wants the speaker icon above this player. */
    public boolean showSpeakingIcon(String uuid) {
        for (java.util.function.Predicate<String> p : speakingIcons) {
            try {
                if (p.test(uuid)) return true;
            } catch (Throwable t) {
                VCraft.LOGGER.warn("VCraft addon speaking icon check failed", t);
                speakingIcons.remove(p);
            }
        }
        return false;
    }

    public boolean hasSpeakingIcons() {
        return !speakingIcons.isEmpty();
    }

    @Override
    public Ui ui() {
        return ui;
    }

    private final Messages messages = new Messages() {
        public void addFilter(MessageFilter filter) {
            filters.add(filter);
        }

        public void addSendCheck(SendCheck check) {
            sendChecks.add(check);
        }
    };

    @Override
    public Messages messages() {
        return messages;
    }

    private final Voice voice = new Voice() {
        public void joinRoom(String roomKey) {
            if (roomKey == null || !roomKey.matches("[A-Za-z0-9:-]{3,80}")) throw new IllegalArgumentException("bad room key");
            State.joinRoom(roomKey);
        }

        public void leaveRoom() {
            State.leaveRoom();
        }

        public String room() {
            State.Room r = State.room;
            return r == null ? null : r.key();
        }

        public List<Member> roomMembers() {
            State.Room r = State.room;
            if (r == null) return List.of();
            return r.members().stream().map(m -> new Member(m.uuid(), m.name(), true)).toList();
        }

        public void setRoomGain(ToDoubleFunction<String> gain) {
            roomGain = gain == null ? uuid -> 1.0 : gain;
        }

        public boolean isTalking(String uuid) {
            return State.isSpeaking(uuid);
        }

        public boolean inCall() {
            return State.call != null;
        }

        public boolean muted() {
            return State.muted || State.deafened;
        }

        public boolean deafened() {
            return State.deafened;
        }
    };

    @Override
    public Voice voice() {
        return voice;
    }

    @Override
    public Game game() {
        return VCraft.platform().game();
    }

    private final Crypto crypto = new Crypto() {
        public String seal(String toUuid, String context, String plaintext) {
            String me = State.myUuid;
            if (me == null || toUuid == null) return null;
            return Secure.seal(toUuid, aad(context, me, toUuid), plaintext);
        }

        public String open(String fromUuid, String context, String sealed) {
            String me = State.myUuid;
            if (me == null || fromUuid == null) return null;
            Secure.Opened o = Secure.open(fromUuid, aad(context, fromUuid, me), sealed);
            return o.ok() ? o.json() : null;
        }

        private String aad(String context, String from, String to) {
            return "addon|" + context + "|" + from + "|" + to;
        }

        public Trust trust(String uuid) {
            return switch (Secure.trust(uuid)) {
                case TRUSTED -> Trust.TRUSTED;
                case CHANGED -> Trust.CHANGED;
                case NONE -> Trust.NONE;
            };
        }

        public String safetyNumber(String uuid) {
            return Secure.safetyNumber(uuid);
        }

        public void trustNewKey(String uuid) {
            State.trustNewKey(uuid);
        }
    };

    @Override
    public Crypto crypto() {
        return crypto;
    }

    private final Signing signing = new Signing() {
        public String publicKey() {
            KeyPair k = signingKey();
            return k == null ? null : Base64.getEncoder().encodeToString(k.getPublic().getEncoded());
        }

        public String sign(String text) {
            KeyPair k = signingKey();
            if (k == null) return null;
            try {
                Signature s = Signature.getInstance("Ed25519");
                s.initSign(k.getPrivate());
                s.update(text.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(s.sign());
            } catch (Exception e) {
                return null;
            }
        }

        public boolean verify(String publicKey, String text, String signature) {
            try {
                Signature s = Signature.getInstance("Ed25519");
                s.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
                s.update(text.getBytes(StandardCharsets.UTF_8));
                return s.verify(Base64.getDecoder().decode(signature));
            } catch (Exception e) {
                return false;
            }
        }
    };

    @Override
    public Signing signing() {
        return signing;
    }

    private synchronized KeyPair signingKey() {
        if (signingKey != null) return signingKey;
        try {
            Path dir = VCraft.platform().configDir().resolve("vcraft");
            Files.createDirectories(dir);
            Path file = dir.resolve("signing-" + VCraft.platform().accountId().replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
            if (Files.exists(file)) {
                JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                KeyFactory kf = KeyFactory.getInstance("Ed25519");
                signingKey = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(o.get("public").getAsString()))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(o.get("private").getAsString()))));
            } else {
                KeyPair k = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                JsonObject o = new JsonObject();
                o.addProperty("public", Base64.getEncoder().encodeToString(k.getPublic().getEncoded()));
                o.addProperty("private", Base64.getEncoder().encodeToString(k.getPrivate().getEncoded()));
                Files.writeString(file, o.toString());
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                }
                signingKey = k;
            }
        } catch (Exception e) {
            VCraft.LOGGER.warn("Couldn't load the VCraft signing key", e);
        }
        return signingKey;
    }

    @Override
    public List<GroupInfo> groups() {
        return State.groups.stream().map(AddonHost::toApi).toList();
    }

    @Override
    public GroupInfo group(String conv) {
        State.Group g = State.group(conv);
        return g == null ? null : toApi(g);
    }

    private static GroupInfo toApi(State.Group g) {
        return new GroupInfo(g.id(), g.conv(), g.name(), g.owner(),
                g.members().stream().map(p -> new Member(p.uuid(), p.name(), p.online())).toList());
    }

    @Override
    public void toast(String title, String body) {
        VCraft.platform().toast(title, body);
    }

    @Override
    public void chat(String tag, int tagColor, String body) {
        VCraft.platform().chat(tag, tagColor, body);
    }

    @Override
    public void notice(String text, boolean error) {
        State.notice(text, error);
    }

    @Override
    public Path dataDir(String addonId) {
        Path p = VCraft.platform().configDir().resolve("vcraft").resolve("addons").resolve(addonId.replaceAll("[^A-Za-z0-9_-]", "_"));
        try {
            Files.createDirectories(p);
        } catch (Exception ignored) {
        }
        return p;
    }

    @Override
    public void runOnClient(Runnable task) {
        VCraft.platform().runOnClient(task);
    }

    private AddonHost() {
    }
}
