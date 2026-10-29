package dev.xcrafttm.ddbeam.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Beam's own connection next to the game's, to a server that offers one (its streamPort): a cast's video goes over
 * it, so the game's packets never wait behind video. A frame is a VarInt length, a type byte and the bytes the
 * channel of that type carries. The client opens it and introduces itself: "DDBEAMS1", its player's UUID and the
 * token the server sent it over the game connection; the server answers one byte, 1 when it took it. Both ends fall
 * back to the game connection when it is not there (or breaks).
 */
public final class StreamLink implements AutoCloseable {
    private static final byte[] MAGIC = "DDBEAMS1".getBytes(StandardCharsets.US_ASCII);
    public static final int STREAM = 1, FRAME = 2;
    /** Longest frame taken (a piece is at most BeamPayloads.MAX_PART plus its header). */
    public static final int MAX_FRAME = 64 * 1024;
    public static final int TOKEN = 16;
    private static final int CONNECT_MS = 3000, HANDSHAKE_MS = 5000;

    public interface Receiver {
        void frame(int type, byte[] data);
    }

    /** Who a new connection said it is. */
    public record Hello(UUID player, byte[] token) {
    }

    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Receiver receiver;
    private final Runnable onClose;

    private StreamLink(Socket socket, String name, Receiver receiver, Runnable onClose) throws IOException {
        this.socket = socket;
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        this.receiver = receiver;
        this.onClose = onClose;
        Thread writer = new Thread(this::write, "ddbeam-link-out-" + name);
        writer.setDaemon(true);
        writer.start();
        Thread reader = new Thread(this::read, "ddbeam-link-in-" + name);
        reader.setDaemon(true);
        reader.start();
    }

    /** Client: connects and introduces itself; throws when the server does not take it. */
    public static StreamLink connect(String host, int port, UUID player, byte[] token, Receiver receiver, Runnable onClose)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(HANDSHAKE_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write(MAGIC);
            out.writeLong(player.getMostSignificantBits());
            out.writeLong(player.getLeastSignificantBits());
            out.write(token.length);
            out.write(token);
            out.flush();
            int answer = socket.getInputStream().read();
            if (answer != 1) throw new IOException("the server did not take the stream connection (" + answer + ")");
            socket.setSoTimeout(0);
            return new StreamLink(socket, player.toString().substring(0, 8), receiver, onClose);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** Server: reads who a new connection is (within a few seconds); throws for anything else. */
    public static Hello handshake(Socket socket) throws IOException {
        socket.setSoTimeout(HANDSHAKE_MS);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("not a Beam stream connection");
        UUID player = new UUID(in.readLong(), in.readLong());
        int length = in.read();
        if (length != TOKEN) throw new IOException("bad token");
        byte[] token = new byte[length];
        in.readFully(token);
        return new Hello(player, token);
    }

    /** Server: takes a connection that introduced itself properly. */
    public static StreamLink accept(Socket socket, Hello hello, Receiver receiver, Runnable onClose) throws IOException {
        socket.setTcpNoDelay(true);
        socket.getOutputStream().write(1);
        socket.getOutputStream().flush();
        socket.setSoTimeout(0);
        return new StreamLink(socket, hello.player().toString().substring(0, 8), receiver, onClose);
    }

    public static void refuse(Socket socket) {
        try (socket) {
            socket.getOutputStream().write(0);
        } catch (IOException ignored) {
        }
    }

    /** Queues a frame; false once the connection is gone. Any thread. */
    public boolean send(int type, byte[] data) {
        if (closed.get()) return false;
        byte[] frame = new byte[data.length + 1];
        frame[0] = (byte) type;
        System.arraycopy(data, 0, frame, 1, data.length);
        queued.addAndGet(frame.length);
        queue.add(frame);
        return true;
    }

    /** Bytes queued that are not written to the socket yet. */
    public long queued() {
        return queued.get();
    }

    public boolean isOpen() {
        return !closed.get();
    }

    private void write() {
        try {
            while (!closed.get()) {
                byte[] frame = queue.take();
                if (frame.length == 0) break;
                writeVarInt(out, frame.length);
                out.write(frame);
                queued.addAndGet(-frame.length);
                if (queue.isEmpty()) out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // gone
        } finally {
            close();
        }
    }

    private void read() {
        try {
            while (!closed.get()) {
                int length = readVarInt(in);
                if (length < 1 || length > MAX_FRAME) throw new IOException("bad frame length " + length);
                byte[] frame = new byte[length];
                in.readFully(frame);
                receiver.frame(frame[0], Arrays.copyOfRange(frame, 1, frame.length));
            }
        } catch (IOException | RuntimeException e) {
            // gone (or sent nonsense)
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        queue.add(new byte[0]);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        onClose.run();
    }

    private static void writeVarInt(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }
}
