package dev.vcraft.core;

import java.util.Arrays;

/**
 * Echo cancellation for people on speakers (the same design as VCraft for
 * Horizon): an NLMS adaptive filter learns the path from the speakers to the
 * microphone after the delay between them is found from the two signals'
 * loudness, subtracts the echo it predicts, and a suppressor turns down what is
 * left while only the far end talks. 16 kHz, a 20 ms frame at a time.
 */
public final class EchoCanceller {
   private static final int TAPS = 512;
   private static final int LEAD = 128;
   private static final int MAX_DELAY = 8000;
   private static final int BLOCK = 160;
   private static final int SEARCH_BLOCKS = 400;
   private static final float MU = 0.35F;
   private static final int FAR_KEEP = 32000;

   /** What the speakers played: the playback thread writes, the microphone reads. */
   public static final class FarEnd {
      private final float[] ring = new float[FAR_KEEP];
      private long total;

      public synchronized void push(short[] frame) {
         for (short s : frame) {
            ring[(int)(total % FAR_KEEP)] = s / 32768.0F;
            total++;
         }
      }

      public synchronized long total() {
         return total;
      }

      /** Samples after {@code since} (at most what's kept), into {@code out}; returns how many. */
      synchronized int since(long since, float[][] out) {
         long from = Math.max(since, total - FAR_KEEP);
         int n = (int)(total - from);
         float[] a = new float[n];
         for (int i = 0; i < n; i++) {
            a[i] = ring[(int)((from + i) % FAR_KEEP)];
         }
         out[0] = a;
         return (int)(from - since);
      }
   }

   // Twice what the filter looks back over, so dropping a quarter keeps enough.
   private final float[] hist = new float[2 * (MAX_DELAY + TAPS + 4 * 320)];
   private int histLen;
   private long base;
   private long read;
   private boolean started;
   private final float[] w = new float[TAPS];
   private int delay;
   private final float[] farEnv = new float[2 * (SEARCH_BLOCKS + MAX_DELAY / BLOCK + 8)];
   private int farEnvLen;
   private long farEnvBase;
   private float farAccSum;
   private int farAccN;
   private final float[] micE = new float[SEARCH_BLOCKS];
   private final long[] micAt = new long[SEARCH_BLOCKS];
   private int micLen;
   private long frames;
   private float gain = 1.0F;
   private boolean converged;

   public void reset() {
      histLen = 0;
      base = 0;
      read = 0;
      started = false;
      Arrays.fill(w, 0.0F);
      delay = 0;
      farEnvLen = 0;
      farEnvBase = 0;
      farAccSum = 0;
      farAccN = 0;
      micLen = 0;
      frames = 0;
      gain = 1.0F;
      converged = false;
   }

   public int delayMs() {
      return delay * 1000 / 16000;
   }

   private void restartAt(long t) {
      histLen = 0;
      base = t;
      farEnvLen = 0;
      farEnvBase = t / BLOCK;
      farAccSum = 0;
      farAccN = (int)(t % BLOCK);
      micLen = 0;
   }

   /** Takes the far-end samples played since the last frame. */
   public void feedFar(FarEnd far) {
      if (!started) {
         started = true;
         read = far.total();
         restartAt(read);
      }
      float[][] box = new float[1][];
      int skipped = far.since(read, box);
      float[] fresh = box[0];
      if (skipped > 0) {
         restartAt(read + skipped);
      }
      read += skipped + fresh.length;
      for (float s : fresh) {
         if (histLen == hist.length) {
            int drop = hist.length / 4;
            System.arraycopy(hist, drop, hist, 0, histLen - drop);
            histLen -= drop;
            base += drop;
         }
         hist[histLen++] = s;
         farAccSum += s * s;
         if (++farAccN == BLOCK) {
            if (farEnvLen == farEnv.length) {
               int drop = farEnv.length / 4;
               System.arraycopy(farEnv, drop, farEnv, 0, farEnvLen - drop);
               farEnvLen -= drop;
               farEnvBase += drop;
            }
            farEnv[farEnvLen++] = (float)Math.sqrt(farAccSum / BLOCK);
            farAccSum = 0;
            farAccN = 0;
         }
      }
   }

   private float farAt(long index) {
      long i = index - base;
      return i < 0 || i >= histLen ? 0.0F : hist[(int)i];
   }

   private void pushMic(float e, long at) {
      if (micLen == SEARCH_BLOCKS) {
         System.arraycopy(micE, 1, micE, 0, SEARCH_BLOCKS - 1);
         System.arraycopy(micAt, 1, micAt, 0, SEARCH_BLOCKS - 1);
         micLen--;
      }
      micE[micLen] = e;
      micAt[micLen] = at;
      micLen++;
   }

   /** Cancels the echo in one microphone frame (samples in -1..1). */
   public void process(float[] mic) {
      int n = mic.length;
      long now = read;
      for (int c = 0; c * BLOCK < n; c++) {
         float sum = 0;
         int end = Math.min(n, (c + 1) * BLOCK);
         for (int i = c * BLOCK; i < end; i++) {
            sum += mic[i] * mic[i];
         }
         pushMic((float)Math.sqrt(sum / (end - c * BLOCK)), Math.max(0, read + (long)(c + 1) * BLOCK - n));
      }
      if (++frames % 25 == 0) {
         estimateDelay();
      }
      long start = Math.max(0, delay - LEAD);
      float eMic = 0, eErr = 0, eFar = 0, eHat = 0;
      float[] x = new float[TAPS];
      for (int k = 0; k < n; k++) {
         long t = now - n + k - start;
         float power = 1e-6F;
         float y = 0;
         for (int j = 0; j < TAPS; j++) {
            float v = farAt(t - j);
            x[j] = v;
            power += v * v;
            y += w[j] * v;
         }
         float d = mic[k];
         float err = d - y;
         eMic += d * d;
         eErr += err * err;
         eHat += y * y;
         eFar += x[0] * x[0];
         boolean farActive = power / TAPS > 1e-6F;
         boolean doubleTalk = converged && d * d > 4.0F * (y * y) + 1e-4F;
         if (farActive && !doubleTalk) {
            float step = MU * err / power;
            for (int j = 0; j < TAPS; j++) {
               w[j] += step * x[j];
            }
         }
         mic[k] = err;
      }
      eMic /= n;
      eErr /= n;
      eFar /= n;
      eHat /= n;
      if (eFar > 1e-5F && eErr < eMic * 0.25F) {
         converged = true;
      }
      boolean farTalks = eFar > 1e-5F;
      boolean nearTalks = eErr > 2.0F * Math.max(eHat, 1e-7F) && eErr > 1e-5F;
      float target = farTalks && !nearTalks ? 0.15F : 1.0F;
      for (int k = 0; k < n; k++) {
         gain += (target - gain) * (target < gain ? 0.02F : 0.004F);
         mic[k] *= gain;
      }
   }

   private void estimateDelay() {
      if (micLen < 100 || farEnvLen == 0) {
         return;
      }
      float farMean = 0;
      for (int i = 0; i < farEnvLen; i++) {
         farMean += farEnv[i];
      }
      farMean /= farEnvLen;
      float farVar = 0;
      for (int i = 0; i < farEnvLen; i++) {
         farVar += (farEnv[i] - farMean) * (farEnv[i] - farMean);
      }
      if (farVar < 1e-6F) {
         return;
      }
      float micMean = 0;
      for (int i = 0; i < micLen; i++) {
         micMean += micE[i];
      }
      micMean /= micLen;
      float bestCorr = 0;
      int bestLag = 0;
      for (int lag = 0; lag <= MAX_DELAY / BLOCK; lag++) {
         float num = 0, fv = 0, mv = 0;
         for (int m = 0; m < micLen; m++) {
            long i = micAt[m] / BLOCK - lag - 1 - farEnvBase;
            if (i < 0 || i >= farEnvLen) {
               continue;
            }
            float f = farEnv[(int)i] - farMean;
            float mm = micE[m] - micMean;
            num += f * mm;
            fv += f * f;
            mv += mm * mm;
         }
         float corr = num / (float)Math.max(Math.sqrt(fv * mv), 1e-9);
         if (corr > bestCorr) {
            bestCorr = corr;
            bestLag = lag;
         }
      }
      if (bestCorr > 0.4F) {
         int d = bestLag * BLOCK;
         if (Math.abs(d - delay) > BLOCK) {
            delay = d;
            Arrays.fill(w, 0.0F);
            converged = false;
         }
      }
   }
}
