package mproxy;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;

/**
 * One side of a proxied connection.
 *
 * <p>Each pipe has two ends (client and upstream). Data read FROM this end is
 * destined for the PEER end, therefore it is enqueued into {@code peer.outbound}.
 * Every piece of mutable state lives here rather than in shared fields, which
 * is what makes the single-threaded reactor safe and makes per-connection
 * cleanup trivial.</p>
 */
final class End {

    /** Owning pipe; set when both ends are registered with the selector. */
    Pipe pipe;

    final SocketChannel channel;

    /** Selection key tying this end into the reactor's selector. */
    SelectionKey key;

    /**
     * Bytes waiting to be written TO this end (produced by the peer's reads).
     * FIFO order preserves the stream. Buffers come from the shared pool and
     * are returned to it once fully drained.
     */
    final ArrayDeque<ByteBuffer> outbound = new ArrayDeque<>();

    /** Total bytes currently queued in {@link #outbound}; drives backpressure. */
    long pendingBytes;

    /** True while a non-blocking connect is in flight (upstream end only). */
    boolean connecting;

    /** When the connect attempt started; drives the connect timeout sweep. */
    final long connectStartedAt = System.nanoTime();

    /** False once this end sent us EOF or was closed; stops OP_READ interest. */
    boolean inputOpen = true;

    /** False once we promised no more output to this end (shutdownOutput). */
    boolean outputOpen = true;

    /**
     * Set when the peer's queue exceeds the high watermark; clears reading
     * from this end until it falls back below the low watermark (hysteresis).
     */
    boolean readPaused;

    End(SocketChannel channel, boolean connecting) {
        this.channel = channel;
        this.connecting = connecting;
    }

    void enqueue(ByteBuffer data) {
        outbound.addLast(data);
        pendingBytes += data.remaining();
    }
}
