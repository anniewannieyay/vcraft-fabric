package dev.vcraft.core;


import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Mic capture with voice activation or push-to-talk, and mixing of everyone in the call. 16 kHz mono, 20 ms frames. */
public final class VoiceEngine {
    public static final float SAMPLE_RATE = 16000f;
    public static final int FRAME_SAMPLES = 320;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private static final int JITTER_FRAMES = 3;
    private static final int MAX_QUEUE = 12;
    private static final long VOICE_HANGOVER_MS = 400;

    private static final class Speaker {
        final ArrayDeque<short[]> queue = new ArrayDeque<>();
        boolean primed;
        long lastFrame;
    }

    private final Map<String, Speaker> speakers = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile int generation;
    private volatile boolean micTest;
    private volatile boolean pushToTalkHeld;
    private volatile float micLevelDb = -100;
    private volatile String error;
    private Thread captureThread;
    private Thread playbackThread;
    private volatile TargetDataLine mic;
    private volatile SourceDataLine speaker;

    public synchronized void start() {
        if (running) return;
        running = true;
        error = null;
        int gen = ++generation;
        captureThread = new Thread(() -> captureLoop(gen), "VCraft Mic");
        playbackThread = new Thread(() -> playbackLoop(gen), "VCraft Speaker");
        captureThread.setDaemon(true);
        playbackThread.setDaemon(true);
        captureThread.start();
        playbackThread.start();
    }

    public synchronized void stop() {
        if (!running) return;
        if (micTest && !State.voiceActive()) return;
        running = false;
        TargetDataLine m = mic;
        SourceDataLine s = speaker;
        if (m != null) m.close();
        if (s != null) s.close();
        speakers.clear();
        micLevelDb = -100;
    }

    public void setMicTest(boolean on) {
        micTest = on;
        if (on) start();
        else if (!State.voiceActive()) stop();
    }

    public boolean micTest() {
        return micTest;
    }

    public void setPushToTalkHeld(boolean held) {
        pushToTalkHeld = held;
    }

    public float micLevelDb() {
        return micLevelDb;
    }

    public String error() {
        return error;
    }

    public boolean running() {
        return running;
    }

    // [16-byte speaker uuid][encrypted frame]
    public void receive(byte[] data) {
        if (!running || State.deafened || data.length < 16 + ImaAdpcm.HEADER) return;
        ByteBuffer buf = ByteBuffer.wrap(data);
        String uuid = new UUID(buf.getLong(), buf.getLong()).toString();
        if (VCraft.config().mutedUsers.contains(uuid)) return;
        byte[] frame = Secure.decryptVoice(uuid, data, 16, data.length - 16);
        if (frame == null) return;
        short[] pcm = ImaAdpcm.decode(frame, 0, frame.length);
        if (State.call == null) {
            // Voice room: the addon sets each speaker's volume (e.g. by distance).
            double gain = AddonHost.INSTANCE.roomGain(uuid);
            if (gain < 0.01) return;
            if (gain < 0.999) for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (pcm[i] * gain);
        }
        enqueue(uuid, pcm);
    }

    private void enqueue(String uuid, short[] pcm) {
        Speaker s = speakers.computeIfAbsent(uuid, k -> new Speaker());
        synchronized (s) {
            if (s.queue.size() >= MAX_QUEUE) s.queue.pollFirst();
            s.queue.addLast(pcm);
            s.lastFrame = System.currentTimeMillis();
        }
        State.lastSpoke.put(uuid, System.currentTimeMillis());
    }

    private boolean alive(int gen) {
        return running && generation == gen;
    }

    private void captureLoop(int gen) {
        TargetDataLine line;
        try {
            line = AudioSystem.getTargetDataLine(FORMAT);
            line.open(FORMAT, FRAME_SAMPLES * 2 * 4);
            line.start();
            mic = line;
        } catch (Exception e) {
            error = "Microphone unavailable: " + e.getMessage();
            VCraft.LOGGER.warn("VCraft could not open microphone", e);
            return;
        }
        ImaAdpcm.Encoder encoder = new ImaAdpcm.Encoder();
        byte[] raw = new byte[FRAME_SAMPLES * 2];
        short[] pcm = new short[FRAME_SAMPLES];
        long hangoverUntil = 0;
        try {
            while (alive(gen)) {
                int read = 0;
                while (read < raw.length && alive(gen)) {
                    int n = line.read(raw, read, raw.length - read);
                    if (n <= 0) break;
                    read += n;
                }
                if (read < raw.length) continue;

                Config cfg = VCraft.config();
                double gain = cfg.micVolume;
                double sumSquares = 0;
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    int s = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
                    s = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s * gain));
                    pcm[i] = (short) s;
                    sumSquares += (double) s * s;
                }
                double rms = Math.sqrt(sumSquares / FRAME_SAMPLES) / 32768.0;
                float db = (float) (rms <= 0 ? -100 : 20 * Math.log10(rms));
                micLevelDb = db;

                long now = System.currentTimeMillis();
                boolean talking;
                if (cfg.pushToTalk) {
                    talking = pushToTalkHeld;
                } else {
                    if (db >= cfg.voiceThresholdDb) hangoverUntil = now + VOICE_HANGOVER_MS;
                    talking = now < hangoverUntil;
                }
                if (!talking) continue;

                if (micTest && !State.voiceActive()) {
                    enqueue("self-test", pcm.clone());
                    continue;
                }
                if (State.muted || State.deafened || !State.voiceActive()) continue;
                byte[] sealed = Secure.encryptVoice(encoder.encode(pcm));
                if (sealed == null) continue;
                VCraft.relay().sendVoice(sealed);
                if (State.myUuid != null) State.lastSpoke.put(State.myUuid, now);
            }
        } finally {
            line.close();
            mic = null;
        }
    }

    private void playbackLoop(int gen) {
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, FRAME_SAMPLES * 2 * 4);
            line.start();
            speaker = line;
        } catch (Exception e) {
            error = "Speaker unavailable: " + e.getMessage();
            VCraft.LOGGER.warn("VCraft could not open audio output", e);
            return;
        }
        int[] mix = new int[FRAME_SAMPLES];
        byte[] out = new byte[FRAME_SAMPLES * 2];
        try {
            while (alive(gen)) {
                java.util.Arrays.fill(mix, 0);
                long now = System.currentTimeMillis();
                for (Map.Entry<String, Speaker> e : speakers.entrySet()) {
                    Speaker s = e.getValue();
                    short[] frame = null;
                    synchronized (s) {
                        if (!s.primed && s.queue.size() >= JITTER_FRAMES) s.primed = true;
                        if (s.primed) {
                            frame = s.queue.pollFirst();
                            if (frame == null) s.primed = false;
                        }
                        if (s.queue.isEmpty() && now - s.lastFrame > 5000) speakers.remove(e.getKey());
                    }
                    if (frame != null) {
                        for (int i = 0; i < Math.min(frame.length, FRAME_SAMPLES); i++) mix[i] += frame[i];
                    }
                }
                double volume = State.deafened ? 0 : VCraft.config().outputVolume;
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    int v = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mix[i] * volume));
                    out[i * 2] = (byte) v;
                    out[i * 2 + 1] = (byte) (v >> 8);
                }
                // write() blocks until the device has room, which paces the loop.
                line.write(out, 0, out.length);
            }
        } finally {
            line.close();
            speaker = null;
        }
    }
}
