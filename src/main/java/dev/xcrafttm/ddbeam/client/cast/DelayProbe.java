package dev.xcrafttm.ddbeam.client.cast;

import dev.xcrafttm.ddbeam.client.BeamClientNet;
import dev.xcrafttm.ddbeam.net.BeamPayloads;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * How much longer than usual the game connection takes while casting: small probes the server sends straight back,
 * five a second. What the video makes wait anywhere on the way (Beam's queue, Netty's, the system's send buffer, a
 * router) shows in their round trip, which is what the game's own packets go through too. The usual round trip is
 * the shortest one of the last 15 seconds; a probe overdue counts as soon as it is late, before its answer comes.
 * Against a server that does not answer probes it says 0 (only Beam's and Netty's queues count then).
 */
final class DelayProbe {
    private static final long INTERVAL_NS = 200_000_000L, WINDOW_NS = 15_000_000_000L;
    private static final int MAX_OUTSTANDING = 64;

    /** id, sent at */
    private final ArrayDeque<long[]> outstanding = new ArrayDeque<>();
    /** answered at, round trip */
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();
    private long nextId, lastSentNs, lastRttNs;
    private boolean answered;
    private volatile long extraMs;

    /** Client thread, every tick: sends the next probe, works out the extra delay. */
    void tick() {
        long now = System.nanoTime();
        if (now - lastSentNs >= INTERVAL_NS && outstanding.size() < MAX_OUTSTANDING && BeamClientNet.canSend(BeamPayloads.PROBE)) {
            long id = nextId++;
            outstanding.add(new long[]{id, now});
            lastSentNs = now;
            BeamClientNet.send(BeamPayloads.PROBE, ByteBuffer.allocate(8).putLong(id).array());
        }
        if (!answered || samples.isEmpty()) {
            extraMs = 0;
            return;
        }
        long base = Long.MAX_VALUE;
        for (long[] s : samples) base = Math.min(base, s[1]);
        long now2 = System.nanoTime();
        long waiting = outstanding.isEmpty() ? 0 : now2 - outstanding.peek()[1];
        extraMs = Math.max(0, (Math.max(lastRttNs, waiting) - base) / 1_000_000);
    }

    /** Client thread: the server sent a probe back. */
    void answer(byte[] data) {
        if (data.length != 8) return;
        long id = ByteBuffer.wrap(data).getLong(), now = System.nanoTime();
        while (!outstanding.isEmpty() && outstanding.peek()[0] <= id) {
            long[] probe = outstanding.poll();
            if (probe[0] == id) {
                lastRttNs = now - probe[1];
                samples.add(new long[]{now, lastRttNs});
                answered = true;
            }
        }
        while (!samples.isEmpty() && now - samples.peek()[0] > WINDOW_NS) samples.poll();
    }

    /** How much longer than usual the connection takes now, in milliseconds (0 when unknown). Any thread. */
    long extraMs() {
        return extraMs;
    }
}
