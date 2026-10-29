package dev.xcrafttm.ddbeam.client.audio;

import dev.xcrafttm.ddbeam.DdBeam;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays a cast's audio from its display, in the game's own OpenAL context: a streaming mono source at the display
 * (mono, so OpenAL places it in the world), fading out with distance like a jukebox. Samples come in from any
 * thread; {@link #pump} feeds them to OpenAL on the client thread.
 */
public final class AudioPlayer {
    /** Up to this much audio waits; above it the oldest is dropped (keeps the delay short after a hiccup). */
    private static final int MAX_QUEUED = AudioCapture.RATE / 2;
    /** Playback starts (and restarts after running dry) once this much is buffered. */
    private static final int START_SAMPLES = AudioCapture.RATE * 3 / 20;
    private static final int MAX_BUFFERS = 24;

    private final ConcurrentLinkedQueue<short[]> incoming = new ConcurrentLinkedQueue<>();
    private final AtomicInteger incomingSamples = new AtomicInteger();
    private final ArrayDeque<Integer> freeBuffers = new ArrayDeque<>();
    private int source;
    private int queuedBuffers;
    private boolean failed;
    private volatile long lastPushNs = System.nanoTime();
    private volatile long pushed, played;

    public void push(short[] pcm) {
        if (failed || pcm.length == 0) return;
        lastPushNs = System.nanoTime();
        pushed += pcm.length;
        incoming.add(pcm);
        if (incomingSamples.addAndGet(pcm.length) > MAX_QUEUED) {
            short[] old = incoming.poll();
            if (old != null) incomingSamples.addAndGet(-old.length);
        }
    }

    /** Seconds of audio received and handed to OpenAL. */
    public String stats() {
        return String.format(java.util.Locale.ROOT, "%.1f s in, %.1f s played%s", pushed / (double) AudioCapture.RATE,
                played / (double) AudioCapture.RATE, failed && source == 0 ? " (no OpenAL source)" : "");
    }

    public long lastPushNs() {
        return lastPushNs;
    }

    /** Client thread: moves waiting audio into OpenAL, places the source and starts it. */
    public void pump(Vec3 at, float gain, float range) {
        if (failed) return;
        if (source == 0 && !create()) return;
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            freeBuffers.add(AL10.alSourceUnqueueBuffers(source));
            queuedBuffers--;
        }
        boolean wasEmpty = queuedBuffers == 0;
        // ~40 ms per OpenAL buffer
        while (!incoming.isEmpty() && queuedBuffers < MAX_BUFFERS) {
            int want = AudioCapture.RATE / 25, n = 0;
            ShortBuffer data = MemoryUtil.memAllocShort(want * 2);
            try {
                short[] next;
                while (n < want && (next = incoming.poll()) != null) {
                    incomingSamples.addAndGet(-next.length);
                    if (data.remaining() < next.length) break;
                    data.put(next);
                    n += next.length;
                }
                data.flip();
                if (n == 0) break;
                int buffer = freeBuffers.isEmpty() ? AL10.alGenBuffers() : freeBuffers.poll();
                AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, data, AudioCapture.RATE);
                AL10.alSourceQueueBuffers(source, buffer);
                queuedBuffers++;
                played += n;
            } finally {
                MemoryUtil.memFree(data);
            }
        }
        AL10.alSource3f(source, AL10.AL_POSITION, (float) at.x, (float) at.y, (float) at.z);
        AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0, gain));
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, range);
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        int buffered = queuedBuffers * (AudioCapture.RATE / 25);
        if (state != AL10.AL_PLAYING && (buffered >= START_SAMPLES || (!wasEmpty && queuedBuffers > 0 && state == AL10.AL_PAUSED))) {
            AL10.alSourcePlay(source);
        }
    }

    private boolean create() {
        AL10.alGetError();
        source = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR || source == 0) {
            DdBeam.LOGGER.warn("No OpenAL source free for cast audio");
            source = 0;
            failed = true;
            return false;
        }
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        // like the game's own sounds: linear fade from the source to the range
        AL10.alSourcei(source, 0xD000 /* AL_DISTANCE_MODEL */, AL11.AL_LINEAR_DISTANCE_CLAMPED);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1f);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 2f);
        AL10.alGetError();
        return true;
    }

    /** Client thread. */
    public void close() {
        failed = true;
        incoming.clear();
        if (source == 0) return;
        AL10.alSourceStop(source);
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        for (int i = 0; i < queued; i++) freeBuffers.add(AL10.alSourceUnqueueBuffers(source));
        AL10.alDeleteSources(source);
        for (int buffer : freeBuffers) AL10.alDeleteBuffers(buffer);
        freeBuffers.clear();
        source = 0;
        AL10.alGetError();
    }
}
