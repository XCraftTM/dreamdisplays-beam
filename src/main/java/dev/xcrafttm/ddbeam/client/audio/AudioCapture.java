package dev.xcrafttm.ddbeam.client.audio;

/** A running audio capture that delivers 48 kHz mono 16-bit samples to its sink, from its own thread. */
public interface AudioCapture extends AutoCloseable {
    int RATE = 48000;

    @Override
    void close();
}
