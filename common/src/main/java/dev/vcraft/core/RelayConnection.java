package dev.vcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class RelayConnection implements WebSocket.Listener {
    public enum Status { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED }

    private static final int MAX_QUEUED_VOICE = 8;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VCraft Relay");
        t.setDaemon(true);
        return t;
    });
    private final StringBuilder textBuffer = new StringBuilder();
    private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
    private final AtomicInteger queuedVoice = new AtomicInteger();
    private final Object sendLock = new Object();

    private volatile WebSocket socket;
    private volatile Status status = Status.DISCONNECTED;
    private volatile String lastError;
    private volatile String authError;
    private volatile boolean wanted;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private int generation;
    private long retryDelayMs = 2000;

    public Status status() {
        return status;
    }

    public boolean connected() {
        return status == Status.CONNECTED;
    }

    public String lastError() {
        return lastError;
    }

    public synchronized void connect() {
        wanted = true;
        if (status != Status.DISCONNECTED) return;
        URI uri;
        try {
            uri = URI.create(VCraft.config().serverUrl.trim());
        } catch (IllegalArgumentException e) {
            lastError = "Invalid relay address";
            return;
        }
        status = Status.CONNECTING;
        int gen = ++generation;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, this)
                .whenComplete((ws, ex) -> {
                    if (ex != null) failed(gen, "Can't reach relay: " + rootMessage(ex));
                });
    }

    public synchronized void disconnect() {
        wanted = false;
        generation++;
        WebSocket ws = socket;
        socket = null;
        status = Status.DISCONNECTED;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        VCraft.platform().runOnClient(State::reset);
    }

    public void reconnect() {
        disconnect();
        retryDelayMs = 2000;
        connect();
    }

    public void send(JsonObject message) {
        WebSocket ws = socket;
        if (ws == null || status != Status.CONNECTED && !"login".equals(message.get("t").getAsString())) return;
        String text = message.toString();
        enqueue(ws, w -> w.sendText(text, true));
    }

    public void sendVoice(byte[] frame) {
        WebSocket ws = socket;
        if (ws == null || status != Status.CONNECTED) return;
        // Stale voice is worthless, so drop it instead of letting latency build up.
        if (queuedVoice.get() >= MAX_QUEUED_VOICE) return;
        queuedVoice.incrementAndGet();
        enqueue(ws, w -> w.sendBinary(ByteBuffer.wrap(frame), true)).whenComplete((r, e) -> queuedVoice.decrementAndGet());
    }

    // java.net.http only allows one outstanding send per socket.
    private CompletableFuture<?> enqueue(WebSocket ws, Function<WebSocket, CompletableFuture<WebSocket>> op) {
        synchronized (sendLock) {
            CompletableFuture<?> next = sendChain.handle((r, e) -> null).thenCompose(v -> op.apply(ws));
            sendChain = next;
            return next;
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        status = Status.AUTHENTICATING;
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuffer.append(data);
        if (last) {
            String text = textBuffer.toString();
            textBuffer.setLength(0);
            try {
                handle(JsonParser.parseString(text).getAsJsonObject());
            } catch (Exception e) {
                VCraft.LOGGER.warn("Bad relay message", e);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        binaryBuffer.writeBytes(chunk);
        if (last) {
            VCraft.voice().receive(binaryBuffer.toByteArray());
            binaryBuffer.reset();
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if ("Banned".equals(reason) || "Account deleted".equals(reason)) wanted = false;
        if (webSocket == socket) failed(generation, reason == null || reason.isEmpty() ? "Disconnected from relay" : reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (webSocket == socket || socket == null) failed(generation, "Relay error: " + rootMessage(error));
    }

    private void handle(JsonObject o) {
        String type = o.get("t").getAsString();
        if ("challenge".equals(type)) {
            String serverId = o.get("serverId").getAsString();
            boolean offline = o.has("offline") && o.get("offline").getAsBoolean();
            CompletableFuture.runAsync(() -> login(serverId, offline), scheduler);
            return;
        }
        if ("welcome".equals(type)) {
            status = Status.CONNECTED;
            lastError = null;
            retryDelayMs = 2000;
            AutoUpdate.checkOnce();
        }
        if ("error".equals(type) && status == Status.AUTHENTICATING) {
            authError = o.get("text").getAsString();
            lastError = authError;
        }
        VCraft.platform().runOnClient(() -> State.handle(o));
    }

    // Same check a Minecraft server does; the access token never leaves this machine.
    private void login(String serverId, boolean offline) {
        Platform p = VCraft.platform();
        try {
            Secure.init(p.configDir(), p.accountId());
        } catch (Exception e) {
            VCraft.LOGGER.error("VCraft could not load its encryption keys", e);
        }
        if (!offline) {
            try {
                p.authenticate(serverId);
            } catch (Exception e) {
                VCraft.LOGGER.warn("Mojang session join failed", e);
            }
        }
        JsonObject login = new JsonObject();
        login.addProperty("t", "login");
        login.addProperty("name", p.username());
        login.addProperty("key", Secure.publicKey());
        login.addProperty("status", VCraft.config().status);
        send(login);
    }

    private synchronized void failed(int gen, String reason) {
        if (gen != generation) return;
        generation++;
        socket = null;
        status = Status.DISCONNECTED;
        if (authError == null) lastError = reason;
        authError = null;
        VCraft.platform().runOnClient(State::reset);
        if (wanted) {
            long delay = retryDelayMs;
            retryDelayMs = Math.min(retryDelayMs * 2, 60_000);
            scheduler.schedule(() -> {
                if (wanted) connect();
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
