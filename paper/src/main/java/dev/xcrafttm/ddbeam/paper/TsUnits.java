package dev.xcrafttm.ddbeam.paper;

// a copy of the mod's dev.xcrafttm.ddbeam.net.TsUnits (the plugin is built on its own)

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Where a cast's video stream (MPEG-TS as Beam's encoder writes it: H.264 or HEVC on PID 0x100, its tables on PID 0
 * and 0x1000) may be thinned out without broken pictures. The stream is cut into units, each one frame with the tables
 * written before it, and sent in pieces that start at a unit or continue one. A connection that cannot keep up drops
 * whole units, and after a dropped unit everything up to the next key frame (a unit with the stream's parameters, SPS),
 * so its players see the last whole picture a little longer instead of a broken one.
 */
final class TsUnits {
    public static final int TS = 188;
    /** The video's packet id, set on FFmpeg's muxer ({@code -mpegts_start_pid}). */
    public static final int VIDEO_PID = 0x100;

    private TsUnits() {
    }

    static int pid(byte[] d, int p) {
        return ((d[p + 1] & 0x1F) << 8) | (d[p + 2] & 0xFF);
    }

    /** Whether the TS packet at [p] starts a payload (a table or a frame). */
    static boolean unitStart(byte[] d, int p) {
        return (d[p + 1] & 0x40) != 0;
    }

    /** Whether a piece begins a unit: its first packet is a PAT or the start of a frame. */
    public static boolean startsUnit(byte[] piece) {
        if (piece.length < TS || piece[0] != 0x47) return false;
        int pid = pid(piece, 0);
        return pid == 0 || pid == VIDEO_PID && unitStart(piece, 0);
    }

    /** Whether the TS packets of [piece] hold a sequence parameter set: H.264 NAL type 7, or HEVC's VPS or SPS. */
    public static boolean holdsKey(byte[] piece) {
        for (int p = 0; p + TS <= piece.length; p += TS) {
            if (piece[p] != 0x47 || pid(piece, p) != VIDEO_PID) continue;
            int afc = (piece[p + 3] >> 4) & 0x3;
            if (afc == 2) continue;
            int payload = p + 4 + (afc == 3 ? 1 + (piece[p + 4] & 0xFF) : 0);
            for (int i = payload; i + 4 < p + TS; i++) {
                if (piece[i] == 0 && piece[i + 1] == 0 && piece[i + 2] == 1 && isKeyNal(piece[i + 3] & 0xFF, piece[i + 4] & 0xFF)) return true;
            }
        }
        return false;
    }

    /**
     * Whether a NAL header (the two bytes after a start code) begins a key frame's parameters: an H.264 SPS (type 7,
     * which never has nal_ref_idc 0), or HEVC's VPS or SPS (types 32, 33 of layer 0, temporal id 0). The two codecs'
     * headers do not overlap there: an H.264 slice read as HEVC can look like type 32, but its second byte never is 1.
     */
    public static boolean isKeyNal(int b0, int b1) {
        if (b0 == 0x27 || b0 == 0x47 || b0 == 0x67) return true;
        return (b0 == 0x40 || b0 == 0x42) && b1 == 0x01;
    }

    /** A unit: one frame of the video with the tables before it. */
    public record Unit(byte[] data, boolean key) {
    }

    /**
     * Cuts the byte stream from FFmpeg into units. A unit is handed on once the next one begins (a frame later), so
     * it is whole when it is sent.
     */
    public static final class Splitter {
        private final byte[] partial = new byte[TS];
        private int partialLength = -1;
        private final ByteArrayOutputStream unit = new ByteArrayOutputStream(64 * 1024);
        private boolean unitHasVideo;

        public void feed(byte[] data, Consumer<Unit> out) {
            int i = 0;
            // find the packet grid once (the stream starts on it, so this is for safety)
            if (partialLength < 0) {
                while (i < data.length && data[i] != 0x47) i++;
                partialLength = 0;
            }
            while (i < data.length) {
                int take = Math.min(TS - partialLength, data.length - i);
                System.arraycopy(data, i, partial, partialLength, take);
                partialLength += take;
                i += take;
                if (partialLength < TS) break;
                packet(partial, out);
                partialLength = 0;
            }
        }

        private void packet(byte[] p, Consumer<Unit> out) {
            if (p[0] != 0x47) return;
            int pid = pid(p, 0);
            boolean video = pid == VIDEO_PID;
            // a PAT, or a frame after the current unit already has one, begins the next unit
            if ((pid == 0 || video && unitStart(p, 0) && unitHasVideo) && unit.size() > 0) flush(out);
            unit.write(p, 0, TS);
            if (video) unitHasVideo = true;
        }

        /** Hands on the unit collected so far. */
        public void flush(Consumer<Unit> out) {
            if (unit.size() == 0) return;
            byte[] data = unit.toByteArray();
            unit.reset();
            unitHasVideo = false;
            out.accept(new Unit(data, holdsKey(data)));
        }
    }

    /** One connection's gate: whole units only; after a dropped unit nothing until a key frame with room for it. */
    public static final class Gate {
        /** Set by the stream's thread and, when a connection breaks, by another one. */
        private volatile boolean dropping;
        private long dropped;

        /**
         * Whether a piece goes out. [unitStart] and [key] describe the piece (see {@link #startsUnit}, {@link #holdsKey}),
         * [congested] whether the connection is too far behind now, [roomForKey] whether a key frame fits.
         */
        public boolean pass(boolean unitStart, boolean key, boolean congested, boolean roomForKey) {
            if (unitStart) {
                if (dropping) {
                    if (key && roomForKey) dropping = false;
                } else if (congested) {
                    dropping = true;
                }
            }
            if (dropping) dropped++;
            return !dropping;
        }

        /** What came before was lost on the way: nothing until the next key frame. */
        public void drop() {
            dropping = true;
        }

        public long dropped() {
            return dropped;
        }
    }
}
