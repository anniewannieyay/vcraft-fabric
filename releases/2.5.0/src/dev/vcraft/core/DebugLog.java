package dev.vcraft.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The debug log, {@code config/vcraft/debug.log}: what VCraft did (connecting,
 * disconnecting and why, audio devices, calls joined and left), never what
 * anyone said or typed. Each line is written and flushed on its own, so the log
 * survives the game crashing.
 */
public final class DebugLog {
   private static final long MAX_BYTES = 512L * 1024L;
   private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

   private DebugLog() {
   }

   public static Path path() {
      Platform p = VCraft.platform();
      return p == null ? null : p.configDir().resolve("vcraft").resolve("debug.log");
   }

   public static synchronized void line(String text) {
      Config c = VCraft.config();
      Path file = path();
      if (c == null || !c.debugLog || file == null) {
         return;
      }
      try {
         Files.createDirectories(file.getParent());
         if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
            Files.move(file, file.resolveSibling("debug.old.log"), StandardCopyOption.REPLACE_EXISTING);
         }
         String clean = text.replaceAll("\\p{Cntrl}", " ");
         Files.writeString(file, "[" + LocalDateTime.now().format(STAMP) + "] " + clean + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
      } catch (IOException ignored) {
      }
   }

   /** Opens the folder holding the log in the system's file browser. */
   public static void openFolder() throws IOException {
      Path file = path();
      if (file == null) {
         return;
      }
      Path dir = file.getParent();
      Files.createDirectories(dir);
      String os = System.getProperty("os.name", "").toLowerCase();
      String opener = os.contains("win") ? "explorer" : (os.contains("mac") ? "open" : "xdg-open");
      new ProcessBuilder(opener, dir.toAbsolutePath().toString()).start();
   }

   /** A short, stable stand-in for a player's UUID in the log. */
   public static String who(String uuid) {
      String s = uuid == null ? "" : uuid.replace("-", "");
      return s.length() > 8 ? s.substring(0, 8) : s;
   }
}
