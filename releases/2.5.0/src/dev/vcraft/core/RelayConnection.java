package dev.vcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class RelayConnection implements Listener {
   private static final int MAX_QUEUED_VOICE = 8;
   private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
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
   private volatile RelayConnection.Status status = RelayConnection.Status.DISCONNECTED;
   private volatile String lastError;
   private volatile String authError;
   private volatile boolean wanted;
   private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
   private int generation;
   private long retryDelayMs = 2000L;

   public RelayConnection.Status status() {
      return this.status;
   }

   /** The player wants to be connected (it wasn't turned off). */
   public boolean wanted() {
      return this.wanted;
   }

   public boolean connected() {
      return this.status == RelayConnection.Status.CONNECTED;
   }

   public String lastError() {
      return this.lastError;
   }

   public synchronized void connect() {
      this.wanted = true;
      if (this.status == RelayConnection.Status.DISCONNECTED) {
         URI uri;
         try {
            uri = URI.create(VCraft.config().serverUrl.trim());
         } catch (IllegalArgumentException var3) {
            this.lastError = "Invalid relay address";
            return;
         }

         this.status = RelayConnection.Status.CONNECTING;
         int gen = ++this.generation;
         this.http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10L)).buildAsync(uri, this).whenComplete((ws, ex) -> {
            if (ex != null) {
               this.failed(gen, "Can't reach relay: " + rootMessage(ex));
            }
         });
      }
   }

   public synchronized void disconnect() {
      this.wanted = false;
      this.generation++;
      WebSocket ws = this.socket;
      this.socket = null;
      this.status = RelayConnection.Status.DISCONNECTED;
      if (ws != null) {
         ws.sendClose(1000, "bye");
      }

      VCraft.platform().runOnClient(State::reset);
   }

   public void reconnect() {
      this.disconnect();
      this.retryDelayMs = 2000L;
      this.connect();
   }

   public void send(JsonObject message) {
      WebSocket ws = this.socket;
      if (ws != null && (this.status == RelayConnection.Status.CONNECTED || "login".equals(message.get("t").getAsString()))) {
         String text = message.toString();
         this.enqueue(ws, w -> w.sendText(text, true));
      }
   }

   public void sendVoice(byte[] frame) {
      WebSocket ws = this.socket;
      if (ws != null && this.status == RelayConnection.Status.CONNECTED) {
         if (this.queuedVoice.get() < 8) {
            this.queuedVoice.incrementAndGet();
            this.enqueue(ws, w -> w.sendBinary(ByteBuffer.wrap(frame), true)).whenComplete((r, e) -> this.queuedVoice.decrementAndGet());
         }
      }
   }

   private CompletableFuture<?> enqueue(WebSocket ws, Function<WebSocket, CompletableFuture<WebSocket>> op) {
      synchronized (this.sendLock) {
         CompletableFuture<?> next = this.sendChain.<Object>handle((r, e) -> null).thenCompose(v -> op.apply(ws));
         this.sendChain = next;
         return next;
      }
   }

   @Override
   public void onOpen(WebSocket webSocket) {
      this.socket = webSocket;
      this.status = RelayConnection.Status.AUTHENTICATING;
      webSocket.request(1L);
   }

   @Override
   public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      this.textBuffer.append(data);
      if (last) {
         String text = this.textBuffer.toString();
         this.textBuffer.setLength(0);

         try {
            this.handle(JsonParser.parseString(text).getAsJsonObject());
         } catch (Exception var6) {
            VCraft.LOGGER.warn("Bad relay message", var6);
         }
      }

      webSocket.request(1L);
      return null;
   }

   @Override
   public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      byte[] chunk = new byte[data.remaining()];
      data.get(chunk);
      this.binaryBuffer.writeBytes(chunk);
      if (last) {
         VCraft.voice().receive(this.binaryBuffer.toByteArray());
         this.binaryBuffer.reset();
      }

      webSocket.request(1L);
      return null;
   }

   @Override
   public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      if ("Banned".equals(reason) || "Account deleted".equals(reason)) {
         this.wanted = false;
      }

      if (webSocket == this.socket) {
         this.failed(this.generation, reason != null && !reason.isEmpty() ? reason : "Disconnected from relay");
      }

      return null;
   }

   @Override
   public void onError(WebSocket webSocket, Throwable error) {
      if (webSocket == this.socket || this.socket == null) {
         this.failed(this.generation, "Relay error: " + rootMessage(error));
      }
   }

   private void handle(JsonObject o) {
      String type = o.get("t").getAsString();
      if (!"challenge".equals(type)) {
         if ("welcome".equals(type)) {
            this.status = RelayConnection.Status.CONNECTED;
            this.lastError = null;
            this.retryDelayMs = 2000L;
            AutoUpdate.checkOnce();
         }

         if ("error".equals(type) && this.status == RelayConnection.Status.AUTHENTICATING) {
            this.authError = o.get("text").getAsString();
            this.lastError = this.authError;
         }

         VCraft.platform().runOnClient(() -> State.handle(o));
      } else {
         String serverId = o.get("serverId").getAsString();
         boolean offline = o.has("offline") && o.get("offline").getAsBoolean();
         CompletableFuture.runAsync(() -> this.login(serverId, offline), this.scheduler);
      }
   }

   private void login(String serverId, boolean offline) {
      Platform p = VCraft.platform();

      try {
         Secure.init(p.configDir(), p.accountId());
      } catch (Exception var6) {
         VCraft.LOGGER.error("VCraft could not load its encryption keys", var6);
      }

      if (!offline) {
         try {
            p.authenticate(serverId);
         } catch (Exception var5) {
            VCraft.LOGGER.warn("Mojang session join failed", var5);
         }
      }

      JsonObject login = new JsonObject();
      login.addProperty("t", "login");
      login.addProperty("name", p.username());
      login.addProperty("key", Secure.publicKey());
      login.addProperty("status", VCraft.config().status);
      this.send(login);
   }

   private synchronized void failed(int gen, String reason) {
      if (gen == this.generation) {
         this.generation++;
         this.socket = null;
         this.status = RelayConnection.Status.DISCONNECTED;
         if (this.authError == null) {
            this.lastError = reason;
         }

         this.authError = null;
         VCraft.platform().runOnClient(State::reset);
         if (this.wanted) {
            long delay = this.retryDelayMs;
            this.retryDelayMs = Math.min(this.retryDelayMs * 2L, 60000L);
            this.scheduler.schedule(() -> {
               if (this.wanted) {
                  this.connect();
               }
            }, delay, TimeUnit.MILLISECONDS);
         }
      }
   }

   private static String rootMessage(Throwable t) {
      while (t.getCause() != null) {
         t = t.getCause();
      }

      return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
   }

   public static enum Status {
      DISCONNECTED,
      CONNECTING,
      AUTHENTICATING,
      CONNECTED;
   }
}
