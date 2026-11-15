package dev.vcraft.core;

import java.util.List;

/**
 * Voice effects for fun, applied to your microphone before it's sent: a higher
 * or deeper voice (a two-head delay-line pitch shifter) and a walkie-talkie
 * radio (a narrow band-pass and a little overdrive).
 */
public final class VoiceFx {
   public static final List<String> EFFECTS = List.of("none", "high", "low", "radio");
   private static final float RATE = 16000.0F;
   private static final float WINDOW = 960.0F;
   private static final int LINE = 2048;

   public static String label(String name) {
      return switch (name == null ? "none" : name) {
         case "high" -> "Higher voice";
         case "low" -> "Deeper voice";
         case "radio" -> "Radio";
         default -> "None";
      };
   }

   public static String next(String name, boolean forward) {
      int i = Math.max(0, EFFECTS.indexOf(name));
      int n = EFFECTS.size();
      return EFFECTS.get(forward ? (i + 1) % n : (i + n - 1) % n);
   }

   private static final class Biquad {
      final float b0, b1, b2, a1, a2;
      float z0, z1;

      Biquad(boolean lowPass, float freq, float q) {
         double w = 2.0 * Math.PI * freq / RATE;
         double sin = Math.sin(w), cos = Math.cos(w);
         double alpha = sin / (2.0 * q);
         double a0 = 1.0 + alpha;
         double c0, c1, c2;
         if (lowPass) {
            c0 = (1.0 - cos) / 2.0;
            c1 = 1.0 - cos;
            c2 = (1.0 - cos) / 2.0;
         } else {
            c0 = (1.0 + cos) / 2.0;
            c1 = -(1.0 + cos);
            c2 = (1.0 + cos) / 2.0;
         }
         b0 = (float)(c0 / a0);
         b1 = (float)(c1 / a0);
         b2 = (float)(c2 / a0);
         a1 = (float)(-2.0 * cos / a0);
         a2 = (float)((1.0 - alpha) / a0);
      }

      float run(float x) {
         float y = b0 * x + z0;
         z0 = b1 * x - a1 * y + z1;
         z1 = b2 * x - a2 * y;
         return y;
      }
   }

   private String current = "none";
   private float[] line = new float[LINE];
   private int write;
   private float offset;
   private Biquad[] filters = new Biquad[0];

   private float read(float delay) {
      float pos = write - delay;
      pos = ((pos % LINE) + LINE) % LINE;
      int i = (int)pos;
      float frac = pos - i;
      float a = line[i % LINE];
      float b = line[(i + 1) % LINE];
      return a + (b - a) * frac;
   }

   private float pitch(float x, float ratio) {
      line[write] = x;
      offset = (((offset + 1.0F - ratio) % WINDOW) + WINDOW) % WINDOW;
      float d1 = offset + 2.0F;
      float d2 = ((offset + WINDOW / 2.0F) % WINDOW) + 2.0F;
      float g1 = (float)Math.sin(Math.PI * (d1 - 2.0F) / WINDOW);
      float g2 = (float)Math.sin(Math.PI * (d2 - 2.0F) / WINDOW);
      float y = (read(d1) * g1 + read(d2) * g2) / Math.max(0.5F, (float)Math.sqrt(g1 * g1 + g2 * g2));
      write = (write + 1) % LINE;
      return y;
   }

   /** Runs {@code effect} over one frame. */
   public void process(String effect, short[] frame) {
      String e = EFFECTS.contains(effect) ? effect : "none";
      if (!e.equals(current)) {
         current = e;
         line = new float[LINE];
         write = 0;
         offset = 0;
         filters = new Biquad[]{new Biquad(false, 450.0F, 0.7F), new Biquad(true, 2600.0F, 0.7F), new Biquad(false, 300.0F, 0.7F)};
      }
      switch (e) {
         case "high", "low" -> {
            float ratio = "high".equals(e) ? 1.45F : 0.72F;
            for (int i = 0; i < frame.length; i++) {
               float y = pitch(frame[i] / 32768.0F, ratio);
               frame[i] = (short)(Math.max(-1.0F, Math.min(1.0F, y)) * 32767.0F);
            }
         }
         case "radio" -> {
            for (int i = 0; i < frame.length; i++) {
               float y = frame[i] / 32768.0F;
               for (Biquad f : filters) {
                  y = f.run(y);
               }
               y = (float)Math.tanh(y * 3.0F) * 0.6F;
               frame[i] = (short)(y * 32767.0F);
            }
         }
         default -> {
         }
      }
   }
}
