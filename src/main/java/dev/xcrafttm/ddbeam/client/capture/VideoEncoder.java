package dev.xcrafttm.ddbeam.client.capture;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.client.BeamConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The H.264 encoder for streaming a cast to other players. JPEG frames stand alone, so a 1440p60 cast would need
 * about 17 MB/s; H.264 only sends what changes and needs about 1.5 MB/s for the same picture.
 * <p>
 * Hardware encoders come first, each tried once with a short test encode (an encoder that is built into FFmpeg but
 * has no matching GPU or driver fails there), then libx264 on the CPU:
 * <ul>
 *   <li>Windows: NVIDIA NVENC, AMD AMF, Intel Quick Sync, then Media Foundation's hardware encoder (whatever GPU
 *       driver provides one, e.g. Qualcomm on ARM laptops).</li>
 *   <li>Linux: NVIDIA NVENC, then VAAPI (AMD and Intel, through Mesa or Intel's media driver).</li>
 *   <li>macOS: VideoToolbox (Apple Silicon and Intel Macs).</li>
 * </ul>
 * H.264 every player can decode (hardware decoding with {@code -hwaccel auto} on all of the above); HEVC takes about a
 * third less for the same picture and is used when every Beam player on the server can decode it (see Casts), with
 * the same hardware encoders (no CPU one: libx265 is too slow for live casts, libx264 takes over then).
 */
public final class VideoEncoder {
    public static final String H264 = "h264", HEVC = "hevc";
    /** The encoder found per codec ("" for none). */
    private static final java.util.Map<String, String> CHOSEN = new java.util.concurrent.ConcurrentHashMap<>();

    private VideoEncoder() {
    }

    private static List<String> candidates(String codec) {
        boolean hevc = codec.equals(HEVC);
        return switch (Sources.os()) {
            case "windows" -> hevc ? List.of("hevc_nvenc", "hevc_amf", "hevc_qsv", "hevc_mf")
                    : List.of("h264_nvenc", "h264_amf", "h264_qsv", "h264_mf", "libx264");
            case "mac" -> hevc ? List.of("hevc_videotoolbox") : List.of("h264_videotoolbox", "libx264");
            default -> hevc ? List.of("hevc_nvenc", "hevc_vaapi") : List.of("h264_nvenc", "h264_vaapi", "libx264");
        };
    }

    /** The codec an encoder writes. */
    public static String codecOf(String encoder) {
        return encoder.startsWith("hevc") ? HEVC : H264;
    }

    /** VAAPI's render node (the first GPU), for Linux. */
    private static String vaapiDevice() {
        String configured = System.getProperty("ddbeam.vaapiDevice");
        if (configured != null) return configured;
        for (int i = 128; i < 136; i++) {
            File node = new File("/dev/dri/renderD" + i);
            if (node.exists()) return node.getPath();
        }
        return "/dev/dri/renderD128";
    }

    /** The H.264 encoder to use, null when this FFmpeg can encode no H.264 at all. Blocks: not on the render thread. */
    public static String get() {
        return get(H264);
    }

    /** The encoder for [codec], null when there is none. Blocks: not on the render thread. */
    public static synchronized String get(String codec) {
        String configured = BeamConfig.get().streamEncoder;
        if (configured != null && !configured.isBlank() && !configured.equals("auto")) {
            return codecOf(configured).equals(codec) ? configured : null;
        }
        String found = CHOSEN.get(codec);
        if (found != null) return found.isEmpty() ? null : found;
        String chosen = null;
        for (String encoder : candidates(codec)) {
            try {
                List<String> args = new ArrayList<>(List.of("-loglevel", "error"));
                args.addAll(deviceArgs(encoder));
                args.addAll(List.of("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-frames:v", "5",
                        "-vf", "format=" + inputFormat(encoder)));
                args.addAll(options(encoder, 2000, 30));
                args.addAll(List.of("-f", "null", "-"));
                String out = Ffmpeg.run(args, 10_000);
                if (out.isBlank()) {
                    chosen = encoder;
                    break;
                }
                DdBeam.LOGGER.info("Encoder {} not usable: {}", encoder, out.lines().findFirst().orElse(""));
            } catch (Exception e) {
                DdBeam.LOGGER.info("Encoder {} not usable: {}", encoder, e.toString());
            }
        }
        CHOSEN.put(codec, chosen == null ? "" : chosen);
        DdBeam.LOGGER.info("Stream encoder for {}: {}", codec, chosen == null ? "none" : chosen);
        return chosen;
    }

    /** FFmpeg arguments that go before the inputs (a hardware device the encoder needs). */
    public static List<String> deviceArgs(String encoder) {
        return encoder.endsWith("_vaapi") ? List.of("-vaapi_device", vaapiDevice()) : List.of();
    }

    /** The end of the filter chain that feeds the encoder: its pixel format (and the upload to the GPU for VAAPI). */
    public static String inputFormat(String encoder) {
        // hardware encoders behind Media Foundation take NV12 only
        if (encoder.endsWith("_vaapi")) return "nv12,hwupload";
        return encoder.endsWith("_mf") ? "nv12" : "yuv420p";
    }

    /** Bits per second for a picture: about 12 Mbit/s for 1440p60, at least 2.5 Mbit/s. */
    public static int bitrateKbps(int width, int height, int fps) {
        int configured = BeamConfig.get().streamBitrateKbps;
        if (configured > 0) return configured;
        return (int) Math.max(2500, Math.min(25_000, (long) width * height * fps * 0.055 / 1000));
    }

    /**
     * Encoder settings for live streaming: a variable bit rate up to a cap ([kbps]: a still or calm picture takes a
     * fraction of it, motion up to it), no B-frames (no delay for reordering) and a key frame every two seconds, so a
     * player who comes into range has a picture within two seconds. Works for the H.264 and HEVC encoders alike.
     */
    public static List<String> options(String encoder, int kbps, int fps) {
        String cap = kbps + "k", average = Math.max(300, kbps * 3 / 5) + "k", buffer = Math.max(500, kbps / 2) + "k";
        String gop = Integer.toString(Math.max(2, fps * 2));
        boolean hevc = encoder.startsWith("hevc");
        List<String> args = new ArrayList<>(List.of("-c:v", encoder));
        switch (encoder.substring(encoder.indexOf('_') + 1)) {
            // with a quality target: what looks right takes no more bits than it needs
            case "nvenc" -> args.addAll(List.of("-preset", "p4", "-tune", "ll", "-rc", "vbr", "-cq", hevc ? "26" : "23", "-forced-idr", "1"));
            case "amf" -> args.addAll(List.of("-usage", "lowlatency", "-rc", "vbr_peak"));
            case "qsv" -> args.addAll(List.of("-preset", "veryfast", "-look_ahead", "0"));
            // hardware only: the software Media Foundation encoder is slower than libx264
            case "mf" -> args.addAll(List.of("-hw_encoding", "1", "-rate_control", "pc_vbr", "-scenario", "display_remoting"));
            case "vaapi" -> args.addAll(List.of("-rc_mode", "VBR"));
            case "videotoolbox" -> args.addAll(List.of("-realtime", "1"));
            case "libx264" -> args.addAll(List.of("-preset", "veryfast", "-tune", "zerolatency", "-crf", "23"));
            default -> {
            }
        }
        // libx264's quality factor sets its rate itself, under the cap
        if (!encoder.equals("libx264")) args.addAll(List.of("-b:v", average));
        args.addAll(List.of("-maxrate", cap, "-bufsize", buffer, "-g", gop, "-bf", "0"));
        return args;
    }
}
