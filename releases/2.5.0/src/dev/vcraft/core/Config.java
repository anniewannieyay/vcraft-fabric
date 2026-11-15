package dev.vcraft.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Config {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   public static final String DEFAULT_RELAY = "wss://vcraft.2-31-104-109.sslip.io";
   private static final String OLD_DEFAULT_RELAY = "ws://localhost:24470";
   public String serverUrl = "wss://vcraft.2-31-104-109.sslip.io";
   public boolean autoConnect = true;
   public boolean autoUpdate = true;
   public String status = "online";
   public boolean pushToTalk = false;
   public double voiceThresholdDb = -42.0;
   public double micVolume = 1.0;
   /** Run the microphone through {@link Denoiser} (new in 2.4.0). */
   public boolean noiseSuppression = true;
   public double outputVolume = 1.0;
   public boolean notifications = true;
   public boolean messageSound = true;
   public boolean shareListening = true;
   public boolean nowPlayingKeyMoved = false;
   public String ringtone = "minecraft:music_disc.pigstep";
   public Set<String> mutedUsers = ConcurrentHashMap.newKeySet();
   // New in 2.5.0 (the same names as VCraft for Horizon's vcraft.json).
   public boolean echoCancellation = false;
   public String voiceEffect = "none";
   public boolean autoMutePaused = false;
   public boolean autoMuteUnfocused = false;
   public boolean autoRejoin = true;
   public boolean shareServer = true;
   public boolean hudCompact = false;
   public boolean hudUnread = true;
   public Set<String> mutedGroups = ConcurrentHashMap.newKeySet();
   public Map<String, String> nicknames = new ConcurrentHashMap<>();
   public Map<String, String> notes = new ConcurrentHashMap<>();
   public String language = "game";
   public boolean debugLog = true;
   private transient Path file;

   public static Config load(Path dir) {
      Path file = dir.resolve("vcraft.json");
      Config c = null;
      if (Files.exists(file)) {
         try (Reader r = Files.newBufferedReader(file)) {
            c = (Config)GSON.fromJson(r, Config.class);
         } catch (Exception var8) {
            VCraft.LOGGER.warn("Could not read {}, using defaults", file, var8);
         }
      }

      if (c == null) {
         c = new Config();
      }

      if (c.serverUrl == null || c.serverUrl.isBlank() || "ws://localhost:24470".equals(c.serverUrl.trim())) {
         c.serverUrl = "wss://vcraft.2-31-104-109.sslip.io";
      }

      Set<String> muted = ConcurrentHashMap.newKeySet();
      if (c.mutedUsers != null) {
         muted.addAll(c.mutedUsers);
      }

      c.mutedUsers = muted;
      Set<String> groups = ConcurrentHashMap.newKeySet();
      if (c.mutedGroups != null) {
         groups.addAll(c.mutedGroups);
      }
      c.mutedGroups = groups;
      c.nicknames = c.nicknames == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(c.nicknames);
      c.notes = c.notes == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(c.notes);
      if (c.voiceEffect == null) {
         c.voiceEffect = "none";
      }
      if (c.language == null) {
         c.language = "game";
      }
      c.file = file;
      c.save();
      return c;
   }

   public void save() {
      try {
         Files.createDirectories(this.file.getParent());

         try (Writer w = Files.newBufferedWriter(this.file)) {
            GSON.toJson(this, w);
         }
      } catch (Exception var6) {
         VCraft.LOGGER.warn("Could not save {}", this.file, var6);
      }
   }
}
