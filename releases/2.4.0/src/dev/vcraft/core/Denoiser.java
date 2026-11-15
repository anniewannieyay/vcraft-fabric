package dev.vcraft.core;

import java.util.Arrays;

/**
 * Microphone noise suppression. Removes steady background noise (fans, hum,
 * hiss, room rumble) from the 20 ms microphone frames before the voice
 * activity gate and the encoder, so the gate stops opening on noise and
 * friends hear less of it.
 *
 * <p>A short-time spectral Wiener filter: 40 ms sqrt-Hann windows at 50%
 * overlap, a noise floor per frequency taken from the quietest moments of
 * the last ~1.5 s (minimum statistics), and a decision-directed gain with a
 * floor so the noise left over stays smooth instead of chirping. Adds one
 * frame (20 ms) of delay. Same algorithm and constants as VCraft for
 * Horizon's denoise.rs.
 */
public final class Denoiser {
   private static final int HOP = VoiceEngine.FRAME_SAMPLES;
   private static final int WIN = 2 * HOP;
   private static final int BINS = WIN / 2 + 1;
   /** Spectrum smoothing for the noise floor: quick to fall, slow to rise. */
   private static final float SMOOTH_DOWN = 0.4F;
   private static final float SMOOTH_UP = 0.8F;
   /** The floor is the quietest point of the last 3-4 blocks of 25 frames. */
   private static final int BLOCK_FRAMES = 25;
   private static final int BLOCKS = 3;
   /** The minimum of a noisy spectrum sits below its mean; this lifts it. */
   private static final float NOISE_BIAS = 4.0F;
   /** Decision-directed a-priori SNR smoothing (Ephraim-Malah). */
   private static final float DD_ALPHA = 0.98F;
   /** The most a frequency is turned down: 0.12 is about -18 dB. */
   private static final float GAIN_FLOOR = 0.12F;
   /** Bins below 75 Hz (25 Hz each), always turned down: DC and rumble. */
   private static final int RUMBLE_BINS = 3;

   private static final float[] COS = new float[WIN];
   private static final float[] SIN = new float[WIN];
   private static final float[] WINDOW = new float[WIN];

   static {
      for (int i = 0; i < WIN; i++) {
         double a = 2.0 * Math.PI * i / WIN;
         COS[i] = (float)Math.cos(a);
         SIN[i] = (float)Math.sin(a);
         // sqrt of a periodic Hann window: analysis times synthesis is Hann,
         // which sums to exactly 1 at 50% overlap.
         WINDOW[i] = (float)Math.sin(Math.PI * i / WIN);
      }
   }

   private final float[] input = new float[WIN];
   private final float[] overlap = new float[HOP];
   private final float[] buf = new float[WIN];
   private final float[] re = new float[BINS];
   private final float[] im = new float[BINS];
   private final float[] gain = new float[BINS];
   private final float[] smoothed = new float[BINS];
   private final float[] blockMin = new float[BINS];
   private final float[][] history = new float[BLOCKS][BINS];
   private final float[] clean = new float[BINS];
   private int slot;
   private int frames;

   public Denoiser() {
      this.reset();
   }

   /** Forgets the noise it learned and the audio it holds. */
   public void reset() {
      Arrays.fill(this.input, 0.0F);
      Arrays.fill(this.overlap, 0.0F);
      Arrays.fill(this.gain, 1.0F);
      Arrays.fill(this.smoothed, 0.0F);
      Arrays.fill(this.blockMin, Float.POSITIVE_INFINITY);
      for (float[] h : this.history) {
         Arrays.fill(h, Float.POSITIVE_INFINITY);
      }
      Arrays.fill(this.clean, 0.0F);
      this.slot = 0;
      this.frames = 0;
   }

   /** Denoises one 20 ms frame in place; what comes out went in a call earlier. */
   public void process(short[] frame) {
      if (frame.length != HOP) {
         return;
      }
      System.arraycopy(this.input, HOP, this.input, 0, HOP);
      for (int n = 0; n < HOP; n++) {
         this.input[HOP + n] = frame[n] / 32768.0F;
      }
      for (int n = 0; n < WIN; n++) {
         this.buf[n] = this.input[n] * WINDOW[n];
      }
      this.forward();
      this.updateGains();
      for (int k = 0; k < BINS; k++) {
         float g;
         if (k < RUMBLE_BINS) {
            g = GAIN_FLOOR;
         } else {
            // A little smoothing across frequency against "musical noise".
            float lo = this.gain[k - 1];
            float hi = this.gain[Math.min(k + 1, BINS - 1)];
            g = 0.25F * lo + 0.5F * this.gain[k] + 0.25F * hi;
         }
         this.re[k] *= g;
         this.im[k] *= g;
      }
      this.inverse();
      for (int n = 0; n < HOP; n++) {
         float v = (this.overlap[n] + this.buf[n] * WINDOW[n]) * 32768.0F;
         frame[n] = (short)Math.max(-32768.0F, Math.min(32767.0F, v));
      }
      for (int n = 0; n < HOP; n++) {
         this.overlap[n] = this.buf[HOP + n] * WINDOW[HOP + n];
      }
      this.frames++;
   }

   /** The real DFT of buf into re/im, bins 0..WIN/2. */
   private void forward() {
      for (int k = 0; k < BINS; k++) {
         float r = 0.0F;
         float i = 0.0F;
         int idx = 0;
         for (int n = 0; n < WIN; n++) {
            float x = this.buf[n];
            r += x * COS[idx];
            i -= x * SIN[idx];
            idx += k;
            if (idx >= WIN) {
               idx -= WIN;
            }
         }
         this.re[k] = r;
         this.im[k] = i;
      }
   }

   /** The inverse of forward, from re/im back into buf. */
   private void inverse() {
      float scale = 1.0F / WIN;
      float nyquist = this.re[BINS - 1];
      for (int n = 0; n < WIN; n++) {
         float acc = this.re[0] + ((n & 1) == 0 ? nyquist : -nyquist);
         int idx = n;
         for (int k = 1; k < BINS - 1; k++) {
            acc += 2.0F * (this.re[k] * COS[idx] - this.im[k] * SIN[idx]);
            idx += n;
            if (idx >= WIN) {
               idx -= WIN;
            }
         }
         this.buf[n] = acc * scale;
      }
   }

   /** Tracks the noise floor and works out each bin's gain. */
   private void updateGains() {
      boolean first = this.frames == 0;
      for (int k = 0; k < BINS; k++) {
         float p = this.re[k] * this.re[k] + this.im[k] * this.im[k];
         float a = p < this.smoothed[k] ? SMOOTH_DOWN : SMOOTH_UP;
         float s = first ? p : a * this.smoothed[k] + (1.0F - a) * p;
         this.smoothed[k] = s;
         if (s < this.blockMin[k]) {
            this.blockMin[k] = s;
         }
         float floor = this.blockMin[k];
         for (float[] h : this.history) {
            floor = Math.min(floor, h[k]);
         }
         float noise = Math.max(floor * NOISE_BIAS, 1.0E-12F);
         float post = p / noise;
         float prio = DD_ALPHA * this.clean[k] / noise + (1.0F - DD_ALPHA) * Math.max(post - 1.0F, 0.0F);
         float g = Math.max(GAIN_FLOOR, Math.min(1.0F, prio / (1.0F + prio)));
         this.gain[k] = g;
         this.clean[k] = g * g * p;
      }
      if (this.frames % BLOCK_FRAMES == BLOCK_FRAMES - 1) {
         System.arraycopy(this.blockMin, 0, this.history[this.slot], 0, BINS);
         this.slot = (this.slot + 1) % BLOCKS;
         Arrays.fill(this.blockMin, Float.POSITIVE_INFINITY);
      }
   }
}
