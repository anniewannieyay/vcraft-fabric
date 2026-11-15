import dev.vcraft.core.EchoCanceller;
import dev.vcraft.core.I18n;
import dev.vcraft.core.Social;
import dev.vcraft.core.VoiceFx;

/** Checks the 2.5.0 core logic inside the built jar, without the game. */
public final class Check25 {
   private static int seed = 12345;

   private static float noise() {
      seed ^= seed << 13;
      seed ^= seed >>> 17;
      seed ^= seed << 5;
      return ((seed & 0xffffffffL) / 4294967295f - 0.5f) * 0.6f;
   }

   private static void check(boolean ok, String what) {
      System.out.println((ok ? "ok    " : "FAIL  ") + what);
      if (!ok) {
         System.exit(1);
      }
   }

   public static void main(String[] args) {
      // Echo: far-end noise heard back 150 ms later through a small room.
      EchoCanceller.FarEnd far = new EchoCanceller.FarEnd();
      EchoCanceller aec = new EchoCanceller();
      float[] played = new float[400 * 320];
      int delay = 2400;
      double before = 0, after = 0;
      for (int frame = 0; frame < 400; frame++) {
         short[] out = new short[320];
         for (int i = 0; i < 320; i++) {
            float v = noise();
            played[frame * 320 + i] = v;
            out[i] = (short)(v * 32768f);
         }
         far.push(out);
         float[] mic = new float[320];
         for (int i = 0; i < 320; i++) {
            int t = frame * 320 + i;
            float a = t >= delay ? played[t - delay] : 0;
            float b = t >= delay + 7 ? played[t - delay - 7] : 0;
            mic[i] = 0.5f * a + 0.2f * b;
         }
         double eIn = 0;
         for (float s : mic) eIn += s * s;
         aec.feedFar(far);
         aec.process(mic);
         double eOut = 0;
         for (float s : mic) eOut += s * s;
         if (frame >= 300) {
            before += eIn;
            after += eOut;
         }
      }
      double db = 10 * Math.log10(after / before);
      check(db < -15, String.format("echo removed: %.1f dB (delay found %d ms)", db, aec.delayMs()));

      // Effects: the radio cuts lows; pitch moves the tone.
      short[] low = tone(80, 16000), mid = tone(1000, 16000);
      new VoiceFx().process("radio", low);
      new VoiceFx().process("radio", mid);
      check(energy(mid, 4000) > energy(low, 4000) * 20, "radio cuts the lows");
      short[] up = tone(300, 32000), down = tone(300, 32000);
      new VoiceFx().process("high", up);
      new VoiceFx().process("low", down);
      int base = crossings(tone(300, 16000), 0);
      check(crossings(up, 16000) > base * 1.25 && crossings(down, 16000) < base * 0.85, "pitch moves the tone");

      // Group codes match VCraft for Horizon's format.
      String code = Social.encodeCode("grp-1", "0123", "Steve", "Builders");
      String[] c = Social.decodeCode("  " + code + " ");
      check(c != null && c[0].equals("grp-1") && c[1].equals("0123") && c[2].equals("Steve") && c[3].equals("Builders"), "group codes round trip");
      check(Social.decodeCode("vc1.!!!") == null && Social.decodeCode("hello") == null, "bad codes are refused");
      // A code made by VCraft for Horizon (serde sorts the keys) reads the same.
      String[] h = Social.decodeCode("vc1." + java.util.Base64.getUrlEncoder().withoutPadding()
         .encodeToString("{\"g\":\"grp-1\",\"gn\":\"Builders\",\"n\":\"Steve\",\"o\":\"0123\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      check(h != null && h[0].equals("grp-1") && h[1].equals("0123") && h[3].equals("Builders"), "reads Horizon's codes");

      // Translations.
      I18n.set("fr_fr");
      check(I18n.t("Friends").equals("Amis") && I18n.tf("{0} is calling you", "Alex").equals("Alex vous appelle"), "French");
      I18n.set("zh_cn");
      check(I18n.t("Settings").equals("设置"), "Chinese");
      I18n.set("pt_br");
      check(I18n.t("Friends").equals("Friends"), "unknown language falls back to English");
      check(I18n.cycle("game", true).equals("en") && I18n.cycle("game", false).equals("pl"), "language cycle");
      System.out.println("all checks passed");
   }

   private static short[] tone(float f, int n) {
      short[] s = new short[n];
      for (int i = 0; i < n; i++) s[i] = (short)(Math.sin(2 * Math.PI * f * i / 16000.0) * 8000);
      return s;
   }

   private static double energy(short[] s, int from) {
      double e = 0;
      for (int i = from; i < s.length; i++) e += (double)s[i] * s[i];
      return e;
   }

   private static int crossings(short[] s, int from) {
      int n = 0;
      for (int i = from + 1; i < s.length; i++) if ((s[i - 1] < 0) != (s[i] < 0)) n++;
      return n;
   }
}
