import dev.vcraft.core.Denoiser;

/** The Horizon denoise.rs unit tests, against the Java port. */
public class DenoiserCheck {
   static long s;
   static float next() {
      s = s * 6364136223846793005L + 1442695040888963407L;
      return ((s >>> 33) / (float)(1L << 31)) * 2.0F - 1.0F;
   }
   static double rms(short[] x, int from, int to) {
      double sum = 0; for (int i = from; i < to; i++) sum += (double)x[i] * x[i];
      return Math.sqrt(sum / Math.max(to - from, 1));
   }
   public static void main(String[] args) {
      // silence
      Denoiser d = new Denoiser();
      for (int f = 0; f < 50; f++) { short[] fr = new short[320]; d.process(fr); for (short v : fr) if (v != 0) throw new AssertionError("silence"); }
      System.out.println("silence stays silent: ok");
      // steady noise
      d = new Denoiser(); s = 7; short[] in = new short[100 * 320]; short[] out = new short[100 * 320];
      for (int f = 0; f < 250; f++) {
         short[] fr = new short[320]; for (int n = 0; n < 320; n++) fr[n] = (short)(next() * 2000.0F);
         if (f >= 150) System.arraycopy(fr, 0, in, (f - 150) * 320, 320);
         d.process(fr);
         if (f >= 150) System.arraycopy(fr, 0, out, (f - 150) * 320, 320);
      }
      double r = rms(out, 0, out.length) / rms(in, 0, in.length);
      System.out.printf("steady noise: out/in = %.3f (%.1f dB)%n", r, 20 * Math.log10(r));
      if (!(r < 0.35)) throw new AssertionError("noise");
      // speech-like bursts
      d = new Denoiser(); s = 11; short[] tone = new short[100 * 320]; out = new short[100 * 320]; int t = 0;
      for (int f = 0; f < 250; f++) {
         boolean on = (f / 10) % 2 == 0; short[] clean = new short[320]; short[] fr = new short[320];
         for (int n = 0; n < 320; n++) {
            float v = on ? (float)Math.sin(2 * Math.PI * 440.0 * t / 16000.0) * 8000.0F : 0.0F; t++;
            clean[n] = (short)v; fr[n] = (short)(v + next() * 200.0F);
         }
         d.process(fr);
         if (f >= 150) { System.arraycopy(clean, 0, tone, (f - 150) * 320, 320); System.arraycopy(fr, 0, out, (f - 150) * 320, 320); }
      }
      r = rms(out, 0, out.length) / rms(tone, 0, tone.length);
      System.out.printf("speech-like bursts: out/clean = %.3f%n", r);
      if (!(r > 0.7)) throw new AssertionError("bursts");
      // speed: one frame must take well under 20 ms
      long t0 = System.nanoTime(); for (int f = 0; f < 500; f++) d.process(new short[320]);
      System.out.printf("speed: %.3f ms per 20 ms frame%n", (System.nanoTime() - t0) / 500 / 1e6);
      System.out.println("ALL OK");
   }
}
