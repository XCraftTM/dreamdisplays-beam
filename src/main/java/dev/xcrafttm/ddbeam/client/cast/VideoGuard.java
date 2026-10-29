package dev.xcrafttm.ddbeam.client.cast;

import com.dreamdisplays.api.Display;
import com.dreamdisplays.api.DreamDisplays;
import com.dreamdisplays.api.display.model.property.DisplayState;
import dev.xcrafttm.ddbeam.DdBeam;

import java.util.UUID;

/**
 * A cast and the display's own video take turns. When a cast appears on a display, its video is paused (on this
 * client: Dream Displays plays videos on each player's side), so neither its picture nor its sound runs under the
 * cast. Afterwards a video that starts there (a new video set on the display, or play pressed) ends the cast.
 */
final class VideoGuard {
    final UUID display;
    /** Whether this guard paused a playing video. */
    final boolean pausedVideo;
    private String url;
    /** The video is not running (any more): from now on, one that starts ends the cast. */
    private boolean quiet;

    VideoGuard(UUID display) {
        this.display = display;
        Display d = find(display);
        url = d == null ? "" : urlOf(d);
        boolean paused = false;
        if (d != null && running(d)) {
            try {
                paused = d.pause().isOk();
            } catch (RuntimeException e) {
                DdBeam.LOGGER.warn("Could not pause the video of display {}: {}", display, e.toString());
            }
            if (!paused) DdBeam.LOGGER.info("Dream Displays did not pause the video of display {}: it plays under the cast", display);
        } else {
            quiet = true;
        }
        pausedVideo = paused;
    }

    /** Client tick: whether a video was started on the display since the cast appeared. */
    boolean videoStarted() {
        Display d = find(display);
        if (d == null) return false;
        String now = urlOf(d);
        if (!now.equals(url)) {
            url = now;
            // a new video on the display (Dream Displays syncs that to everyone); removing it does not count
            if (!now.isBlank()) return true;
        }
        boolean running = running(d);
        if (!quiet) {
            // still running: the pause is on its way, or Dream Displays refused it and the video goes on under the
            // cast (then only a new video ends it)
            if (!running) quiet = true;
            return false;
        }
        return running;
    }

    private static boolean running(Display d) {
        DisplayState state = d.getState();
        return state instanceof DisplayState.Playing || state instanceof DisplayState.Buffering;
    }

    private static String urlOf(Display d) {
        String url = d.getUrl();
        return url == null ? "" : url;
    }

    static Display find(UUID id) {
        try {
            if (!DreamDisplays.isAvailable()) return null;
            for (Display d : DreamDisplays.getDisplays()) {
                if (d.getId().getUuid().equals(id)) return d;
            }
        } catch (RuntimeException e) {
            DdBeam.LOGGER.debug("Dream Displays' displays are not available: {}", e.toString());
        }
        return null;
    }
}
