package dev.vcraft.core;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public final class VoiceEngine {
   public static final float SAMPLE_RATE = 16000.0F;
   public static final int FRAME_SAMPLES = 320;
   private static final AudioFormat FORMAT = new AudioFormat(16000.0F, 16, 1, true, false);
   private static final int JITTER_FRAMES = 3;
   private static final int MAX_QUEUE = 12;
   private static final long VOICE_HANGOVER_MS = 400L;
   private final Map<String, VoiceEngine.Speaker> speakers = new ConcurrentHashMap<>();
   private volatile boolean running;
   private volatile int generation;
   private volatile boolean micTest;
   private volatile boolean pushToTalkHeld;
   private volatile float micLevelDb = -100.0F;
   private volatile String error;
   private Thread captureThread;
   private Thread playbackThread;
   private volatile TargetDataLine mic;
   private volatile SourceDataLine speaker;

   public synchronized void start() {
      if (!this.running) {
         this.running = true;
         this.error = null;
         int gen = ++this.generation;
         this.captureThread = new Thread(() -> this.captureLoop(gen), "VCraft Mic");
         this.playbackThread = new Thread(() -> this.playbackLoop(gen), "VCraft Speaker");
         this.captureThread.setDaemon(true);
         this.playbackThread.setDaemon(true);
         this.captureThread.start();
         this.playbackThread.start();
      }
   }

   public synchronized void stop() {
      if (this.running) {
         if (!this.micTest || State.voiceActive()) {
            this.running = false;
            TargetDataLine m = this.mic;
            SourceDataLine s = this.speaker;
            if (m != null) {
               m.close();
            }

            if (s != null) {
               s.close();
            }

            this.speakers.clear();
            this.micLevelDb = -100.0F;
         }
      }
   }

   public void setMicTest(boolean on) {
      this.micTest = on;
      if (on) {
         this.start();
      } else if (!State.voiceActive()) {
         this.stop();
      }
   }

   public boolean micTest() {
      return this.micTest;
   }

   public void setPushToTalkHeld(boolean held) {
      this.pushToTalkHeld = held;
   }

   public float micLevelDb() {
      return this.micLevelDb;
   }

   public String error() {
      return this.error;
   }

   public boolean running() {
      return this.running;
   }

   public void receive(byte[] data) {
      if (this.running && !State.deafened && data.length >= 19) {
         ByteBuffer buf = ByteBuffer.wrap(data);
         String uuid = new UUID(buf.getLong(), buf.getLong()).toString();
         if (!VCraft.config().mutedUsers.contains(uuid)) {
            byte[] frame = Secure.decryptVoice(uuid, data, 16, data.length - 16);
            if (frame != null) {
               short[] pcm = ImaAdpcm.decode(frame, 0, frame.length);
               if (State.call == null) {
                  double gain = AddonHost.INSTANCE.roomGain(uuid);
                  if (gain < 0.01) {
                     return;
                  }

                  if (gain < 0.999) {
                     for (int i = 0; i < pcm.length; i++) {
                        pcm[i] = (short)((int)((double)pcm[i] * gain));
                     }
                  }
               }

               this.enqueue(uuid, pcm);
            }
         }
      }
   }

   private void enqueue(String uuid, short[] pcm) {
      VoiceEngine.Speaker s = this.speakers.computeIfAbsent(uuid, k -> new VoiceEngine.Speaker());
      synchronized (s) {
         if (s.queue.size() >= 12) {
            s.queue.pollFirst();
         }

         s.queue.addLast(pcm);
         s.lastFrame = System.currentTimeMillis();
      }

      State.lastSpoke.put(uuid, System.currentTimeMillis());
   }

   private boolean alive(int gen) {
      return this.running && this.generation == gen;
   }

   private void captureLoop(int gen) {
      TargetDataLine line;
      try {
         line = AudioSystem.getTargetDataLine(FORMAT);
         line.open(FORMAT, 2560);
         line.start();
         this.mic = line;
      } catch (Exception var24) {
         this.error = "Microphone unavailable: " + var24.getMessage();
         VCraft.LOGGER.warn("VCraft could not open microphone", var24);
         return;
      }

      ImaAdpcm.Encoder encoder = new ImaAdpcm.Encoder();
      Denoiser denoiser = new Denoiser();
      boolean denoising = false;
      byte[] raw = new byte[640];
      short[] pcm = new short[320];
      long hangoverUntil = 0L;

      try {
         while (this.alive(gen)) {
            int read = 0;

            while (read < raw.length && this.alive(gen)) {
               int n = line.read(raw, read, raw.length - read);
               if (n <= 0) {
                  break;
               }

               read += n;
            }

            if (read >= raw.length) {
               Config cfg = VCraft.config();
               double gain = cfg.micVolume;
               double sumSquares = 0.0;

               for (int i = 0; i < 320; i++) {
                  pcm[i] = (short)(raw[i * 2] & 255 | raw[i * 2 + 1] << 8);
               }

               // Before the level meter and the voice activity gate, so noise
               // alone no longer opens the gate.
               if (cfg.noiseSuppression) {
                  if (!denoising) {
                     denoiser.reset();
                     denoising = true;
                  }

                  denoiser.process(pcm);
               } else {
                  denoising = false;
               }

               for (int i = 0; i < 320; i++) {
                  int s = pcm[i];
                  s = (int)Math.max(-32768.0, Math.min(32767.0, (double)s * gain));
                  pcm[i] = (short)s;
                  sumSquares += (double)s * (double)s;
               }

               double rms = Math.sqrt(sumSquares / 320.0) / 32768.0;
               float db = (float)(rms <= 0.0 ? -100.0 : 20.0 * Math.log10(rms));
               this.micLevelDb = db;
               long now = System.currentTimeMillis();
               boolean talking;
               if (cfg.pushToTalk) {
                  talking = this.pushToTalkHeld;
               } else {
                  if ((double)db >= cfg.voiceThresholdDb) {
                     hangoverUntil = now + 400L;
                  }

                  talking = now < hangoverUntil;
               }

               if (talking) {
                  if (this.micTest && !State.voiceActive()) {
                     this.enqueue("self-test", (short[])pcm.clone());
                  } else if (!State.muted && !State.deafened && State.voiceActive()) {
                     byte[] sealed = Secure.encryptVoice(encoder.encode(pcm));
                     if (sealed != null) {
                        VCraft.relay().sendVoice(sealed);
                        if (State.myUuid != null) {
                           State.lastSpoke.put(State.myUuid, now);
                        }
                     }
                  }
               }
            }
         }
      } finally {
         line.close();
         this.mic = null;
      }
   }

   private void playbackLoop(int gen) {
      SourceDataLine line;
      try {
         line = AudioSystem.getSourceDataLine(FORMAT);
         line.open(FORMAT, 2560);
         line.start();
         this.speaker = line;
      } catch (Exception var17) {
         this.error = "Speaker unavailable: " + var17.getMessage();
         VCraft.LOGGER.warn("VCraft could not open audio output", var17);
         return;
      }

      int[] mix = new int[320];
      byte[] out = new byte[640];

      try {
         while (this.alive(gen)) {
            Arrays.fill(mix, 0);
            long now = System.currentTimeMillis();

            for (Entry<String, VoiceEngine.Speaker> e : this.speakers.entrySet()) {
               VoiceEngine.Speaker s = e.getValue();
               short[] frame = null;
               synchronized (s) {
                  if (!s.primed && s.queue.size() >= 3) {
                     s.primed = true;
                  }

                  if (s.primed) {
                     frame = s.queue.pollFirst();
                     if (frame == null) {
                        s.primed = false;
                     }
                  }

                  if (s.queue.isEmpty() && now - s.lastFrame > 5000L) {
                     this.speakers.remove(e.getKey());
                  }
               }

               if (frame != null) {
                  for (int i = 0; i < Math.min(frame.length, 320); i++) {
                     mix[i] += frame[i];
                  }
               }
            }

            double volume = State.deafened ? 0.0 : VCraft.config().outputVolume;

            for (int i = 0; i < 320; i++) {
               int v = (int)Math.max(-32768.0, Math.min(32767.0, (double)mix[i] * volume));
               out[i * 2] = (byte)v;
               out[i * 2 + 1] = (byte)(v >> 8);
            }

            line.write(out, 0, out.length);
         }
      } finally {
         line.close();
         this.speaker = null;
      }
   }

   private static final class Speaker {
      final ArrayDeque<short[]> queue = new ArrayDeque<>();
      boolean primed;
      long lastFrame;
   }
}
