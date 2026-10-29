package dev.xcrafttm.ddbeam.client.cast;

import dev.xcrafttm.ddbeam.Texts;
import dev.xcrafttm.ddbeam.client.BeamConfig;
import dev.xcrafttm.ddbeam.client.audio.Adpcm;
import dev.xcrafttm.ddbeam.client.audio.AudioPlayer;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import net.minecraft.client.Minecraft;
//#if MC >= 1.21.11
import net.minecraft.client.renderer.rendertype.RenderType;
//#else
import net.minecraft.client.renderer.RenderType;
//#endif
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every cast this client shows: its own one ({@link LocalCast}) and the ones other players stream. Displays with a
 * cast show its picture instead of their video (see the ScreenRenderer mixin).
 */
public final class Casts {
    /** A cast from another player is over when nothing came for this long (out of range, caster gone). */
    private static final long REMOTE_TIMEOUT_NS = 4_000_000_000L;

    private static final Map<UUID, CastFeed> FEEDS = new ConcurrentHashMap<>();
    private static final Map<UUID, AudioPlayer> REMOTE_AUDIO = new HashMap<>();
    private static LocalCast local;

    private Casts() {
    }

    public static LocalCast local() {
        return local;
    }

    public static void message(Component text) {
        dev.xcrafttm.ddbeam.client.Mc.chat(Texts.tr("ddbeam.prefix").append(text));
    }

    // ---- for the renderer (render thread) ----

    /** What draws the cast on this display, null when it shows its own video. */
    public static RenderType renderTypeFor(UUID display) {
        CastFeed feed = FEEDS.get(display);
        return feed == null ? null : feed.prepare();
    }

    public static boolean covers(UUID display) {
        CastFeed feed = FEEDS.get(display);
        return feed != null && feed.hasPicture();
    }

    public static CastFeed feed(UUID display) {
        return FEEDS.get(display);
    }

    // ---- this player's cast (client thread) ----

    public static void start(Targets.Target target, LocalCast.Settings settings) {
        stop();
        CastFeed old = FEEDS.remove(target.id());
        if (old != null) old.close();
        AudioPlayer oldAudio = REMOTE_AUDIO.remove(target.id());
        if (oldAudio != null) oldAudio.close();
        LocalCast cast = new LocalCast(target, settings);
        local = cast;
        FEEDS.put(cast.display, cast.feed);
        cast.start();
        message(Texts.tr("ddbeam.cast.started", settings.source().label(), cast.width, cast.height, settings.fps()));
    }

    public static void stop() {
        LocalCast cast = local;
        if (cast == null) return;
        local = null;
        FEEDS.remove(cast.display, cast.feed);
        cast.stop();
        message(Texts.tr("ddbeam.cast.stopped"));
    }

    // ---- from the server (client thread) ----

    /** A part of another player's JPEG frame (casts without H.264); the feed puts the parts together. */
    public static void onFrame(BeamPayloads.Frame frame) {
        if (!BeamConfig.get().showOtherCasts) return;
        LocalCast cast = local;
        if (cast != null && cast.display.equals(frame.display())) return;
        CastFeed feed = FEEDS.get(frame.display());
        if (feed == null || feed.caster == null || !feed.caster.equals(frame.caster())) {
            if (feed != null) feed.close();
            feed = new CastFeed(frame.display(), frame.caster());
            FEEDS.put(frame.display(), feed);
        }
        byte[] jpeg = feed.parts.add(frame.seq(), frame.part(), frame.parts(), frame.data());
        if (jpeg != null) feed.submit(jpeg);
    }

    /** A piece of another player's H.264 cast: decoded at the cast's own size and frame rate. */
    public static void onStream(BeamPayloads.Stream piece) {
        if (!BeamConfig.get().showOtherCasts) return;
        LocalCast cast = local;
        if (cast != null && cast.display.equals(piece.display())) return;
        int w = piece.width(), h = piece.height();
        if (w <= 0 || h <= 0 || w > JpegDecoder.MAX_SIDE || h > JpegDecoder.MAX_SIDE) return;
        CastFeed feed = FEEDS.get(piece.display());
        if (feed == null || feed.caster == null || !feed.caster.equals(piece.caster())) {
            if (feed != null) feed.close();
            feed = new CastFeed(piece.display(), piece.caster());
            FEEDS.put(piece.display(), feed);
        }
        StreamDecoder decoder = feed.decoder;
        if (decoder == null || decoder.dead() || decoder.width != w || decoder.height != h) {
            // the first piece, a new cast size or a decoder that gave up: a new decoder (it waits for the next key frame)
            if (decoder != null) decoder.close();
            decoder = new StreamDecoder(feed, w, h);
            feed.decoder = decoder;
        }
        feed.touch();
        decoder.write(piece.data());
    }

    public static void onAudio(BeamPayloads.Audio packet) {
        if (!BeamConfig.get().showOtherCasts) return;
        LocalCast cast = local;
        if (cast != null && cast.display.equals(packet.display())) return;
        CastFeed feed = FEEDS.get(packet.display());
        if (feed == null || feed.caster == null) return;
        short[] pcm = Adpcm.decode(packet.data());
        if (pcm != null) REMOTE_AUDIO.computeIfAbsent(packet.display(), id -> new AudioPlayer()).push(pcm);
    }

    public static void onStop(UUID display) {
        LocalCast cast = local;
        if (cast != null && cast.display.equals(display)) return;
        dropRemote(display);
    }

    public static void onNotice(BeamPayloads.Notice notice) {
        LocalCast cast = local;
        if (cast != null && cast.display.equals(notice.display())) cast.onNotice(notice.key());
    }

    private static void dropRemote(UUID display) {
        CastFeed feed = FEEDS.get(display);
        if (feed != null && feed.caster != null) {
            FEEDS.remove(display);
            feed.close();
        }
        AudioPlayer audio = REMOTE_AUDIO.remove(display);
        if (audio != null) audio.close();
    }

    /** Client tick: ends casts that went quiet, feeds the audio to OpenAL at the displays. */
    public static void tick(Minecraft mc) {
        long now = System.nanoTime();
        for (Iterator<CastFeed> it = FEEDS.values().iterator(); it.hasNext(); ) {
            CastFeed feed = it.next();
            if (feed.caster != null && now - feed.lastFrameNs() > REMOTE_TIMEOUT_NS) {
                it.remove();
                feed.close();
                AudioPlayer audio = REMOTE_AUDIO.remove(feed.display);
                if (audio != null) audio.close();
            }
        }
        LocalCast cast = local;
        if (cast != null) cast.tick();
        if (REMOTE_AUDIO.isEmpty() && (cast == null || cast.localAudio == null)) return;

        BeamConfig config = BeamConfig.get();
        float gain = config.volume * mc.options.getSoundSourceVolume(SoundSource.RECORDS);
        //#if MC >= 1.21.11
        // the master volume is part of each sound's own volume here (before, the game set it as the listener's gain)
        gain *= mc.options.getSoundSourceVolume(SoundSource.MASTER);
        //#endif
        for (Map.Entry<UUID, AudioPlayer> e : REMOTE_AUDIO.entrySet()) {
            Vec3 at = centerOf(e.getKey());
            if (at != null) e.getValue().pump(at, gain, config.audioRange);
        }
        if (cast != null && cast.localAudio != null) {
            Vec3 at = centerOf(cast.display);
            if (at != null) cast.localAudio.pump(at, gain, config.audioRange);
        }
    }

    private static Vec3 centerOf(UUID display) {
        Targets.Target target = Targets.byId(display);
        return target == null ? null : target.center();
    }

    /** Audio of a display's cast: other players' casts, or this player's camera cast. */
    public static String audioStats(UUID display) {
        AudioPlayer player = REMOTE_AUDIO.get(display);
        LocalCast cast = local;
        if (player == null && cast != null && cast.display.equals(display)) player = cast.localAudio;
        return player == null ? "-" : player.stats();
    }

    public static Map<UUID, CastFeed> feeds() {
        return FEEDS;
    }

    /** Leaving the world: everything ends. */
    public static void clear() {
        LocalCast cast = local;
        local = null;
        if (cast != null) cast.stop();
        for (CastFeed feed : FEEDS.values()) feed.close();
        FEEDS.clear();
        for (AudioPlayer audio : REMOTE_AUDIO.values()) audio.close();
        REMOTE_AUDIO.clear();
    }
}
