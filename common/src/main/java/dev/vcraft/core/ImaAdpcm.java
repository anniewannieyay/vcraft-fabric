package dev.vcraft.core;

/** IMA ADPCM. Each frame starts with the codec state so frames decode independently. */
public final class ImaAdpcm {
    public static final int HEADER = 3;

    private static final int[] INDEX_TABLE = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};
    private static final int[] STEP_TABLE = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
            253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
            1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
            3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
            12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    public static final class Encoder {
        private int predictor;
        private int index;

            public byte[] encode(short[] pcm) {
            byte[] out = new byte[HEADER + pcm.length / 2];
            out[0] = (byte) (predictor >> 8);
            out[1] = (byte) predictor;
            out[2] = (byte) index;
            for (int i = 0; i < pcm.length; i += 2) {
                int lo = encodeSample(pcm[i]);
                int hi = encodeSample(pcm[i + 1]);
                out[HEADER + i / 2] = (byte) (lo | (hi << 4));
            }
            return out;
        }

        private int encodeSample(int sample) {
            int step = STEP_TABLE[index];
            int diff = sample - predictor;
            int nibble = 0;
            if (diff < 0) {
                nibble = 8;
                diff = -diff;
            }
            int delta = step >> 3;
            if (diff >= step) { nibble |= 4; diff -= step; delta += step; }
            step >>= 1;
            if (diff >= step) { nibble |= 2; diff -= step; delta += step; }
            step >>= 1;
            if (diff >= step) { nibble |= 1; delta += step; }
            predictor = clamp16((nibble & 8) != 0 ? predictor - delta : predictor + delta);
            index = clampIndex(index + INDEX_TABLE[nibble]);
            return nibble;
        }
    }

    public static short[] decode(byte[] data, int offset, int length) {
        if (length < HEADER) return new short[0];
        int predictor = (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
        int index = clampIndex(data[offset + 2] & 0xFF);
        int bytes = length - HEADER;
        short[] out = new short[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            int b = data[offset + HEADER + i] & 0xFF;
            for (int half = 0; half < 2; half++) {
                int nibble = half == 0 ? b & 0x0F : b >> 4;
                int step = STEP_TABLE[index];
                int delta = step >> 3;
                if ((nibble & 4) != 0) delta += step;
                if ((nibble & 2) != 0) delta += step >> 1;
                if ((nibble & 1) != 0) delta += step >> 2;
                predictor = clamp16((nibble & 8) != 0 ? predictor - delta : predictor + delta);
                index = clampIndex(index + INDEX_TABLE[nibble]);
                out[i * 2 + half] = (short) predictor;
            }
        }
        return out;
    }

    private static int clamp16(int v) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private static int clampIndex(int i) {
        return Math.max(0, Math.min(88, i));
    }

    private ImaAdpcm() {
    }
}
