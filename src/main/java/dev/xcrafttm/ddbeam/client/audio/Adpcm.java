package dev.xcrafttm.ddbeam.client.audio;

/**
 * IMA ADPCM, 4 bits per sample: a quarter of 16-bit PCM (48 kHz mono = 24 KB/s), cheap enough to run per packet.
 * Every packet starts with its own decoder state (predictor, step index), so a lost packet costs only itself.
 */
public final class Adpcm {
    private static final int[] INDEX = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};
    private static final int[] STEP = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
            107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428,
            4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350,
            22385, 24623, 27086, 29794, 32767};

    private Adpcm() {
    }

    /** Encodes an even number of samples. */
    public static byte[] encode(short[] pcm) {
        int n = pcm.length & ~1;
        byte[] out = new byte[4 + n / 2];
        int predictor = n > 0 ? pcm[0] : 0, index = 0;
        out[0] = (byte) predictor;
        out[1] = (byte) (predictor >> 8);
        out[2] = (byte) index;
        for (int i = 0; i < n; i++) {
            int step = STEP[index];
            int diff = pcm[i] - predictor;
            int code = 0;
            if (diff < 0) {
                code = 8;
                diff = -diff;
            }
            int delta = step >> 3;
            if (diff >= step) { code |= 4; diff -= step; delta += step; }
            step >>= 1;
            if (diff >= step) { code |= 2; diff -= step; delta += step; }
            step >>= 1;
            if (diff >= step) { code |= 1; delta += step; }
            predictor += (code & 8) != 0 ? -delta : delta;
            predictor = Math.max(-32768, Math.min(32767, predictor));
            index = Math.max(0, Math.min(88, index + INDEX[code]));
            if ((i & 1) == 0) out[4 + i / 2] = (byte) code;
            else out[4 + i / 2] |= (byte) (code << 4);
        }
        return out;
    }

    /** Null for a malformed packet. */
    public static short[] decode(byte[] data) {
        if (data.length < 4) return null;
        int predictor = (short) ((data[0] & 0xFF) | (data[1] << 8));
        int index = data[2] & 0xFF;
        if (index > 88) return null;
        short[] out = new short[(data.length - 4) * 2];
        for (int i = 0; i < out.length; i++) {
            int code = (data[4 + i / 2] >> ((i & 1) * 4)) & 0xF;
            int step = STEP[index];
            int delta = step >> 3;
            if ((code & 4) != 0) delta += step;
            if ((code & 2) != 0) delta += step >> 1;
            if ((code & 1) != 0) delta += step >> 2;
            predictor += (code & 8) != 0 ? -delta : delta;
            predictor = Math.max(-32768, Math.min(32767, predictor));
            index = Math.max(0, Math.min(88, index + INDEX[code]));
            out[i] = (short) predictor;
        }
        return out;
    }
}
