package mproxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * State machine pumping bytes between the two ends of one proxied connection.
 *
 * <p>Data flow: a read on end A appends to B's outbound queue and enables
 * OP_WRITE on B; a write-readiness event on B drains that queue. Nothing ever
 * blocks - if the socket is full, {@code write()} simply writes nothing and
 * the data stays queued until the next OP_WRITE event.</p>
 *
 * <p>Two correctness details this class exists for:
 * <ul>
 *   <li><b>Backpressure</b> - when too much is queued toward a slow receiver,
 *       reading from its fast sender pauses (with hysteresis), so memory per
 *       connection stays bounded instead of growing without limit.</li>
 *   <li><b>Half-close</b> - TCP allows one direction to finish while the other
 *       still flows. EOF from A only shuts down output of B after everything A
 *       produced was flushed to B; the pipe dies only when both directions are
 *       exhausted. Closing both sockets on first EOF (a common shortcut) breaks
 *       protocols whose clients shutdown-write then await the response.</li>
 * </ul></p>
 */
final class Pipe {

    static final int BUFFER_SIZE = 32 * 1024;

    /**
     * Backpressure watermarks with hysteresis (HIGH > LOW prevents pausing or
     * resuming reads on every buffer). With HIGH = 128 KB, pending data toward
     * any peer is bounded by roughly HIGH + one read buffer.
     */
    private static final int HIGH_WATERMARK = 128 * 1024;
    private static final int LOW_WATERMARK = 64 * 1024;

    /**
     * Max queue entries flushed per writable event. Bounds the work done for a
     * single connection inside one dispatch, keeping the reactor fair when one
     * peer's socket accepts data endlessly.
     */
    private static final int MAX_FLUSH_BUFFERS = 16;

    private static final Logger LOGGER = Logger.getLogger(Pipe.class.getName());

    private final BufferPool pool;
    private final End client;
    private final End upstream;
    private final WritableByteChannel mirror;
    private boolean closed;

    Pipe(BufferPool pool, SocketChannel clientChannel, SocketChannel upstreamChannel, WritableByteChannel mirror) {
        this.pool = pool;
        this.client = new End(clientChannel, false);
        this.upstream = new End(upstreamChannel, true);
        this.mirror = mirror;
    }

    /**
     * Registers both ends. The client is readable immediately (its request can
     * be buffered during the upstream handshake); the upstream starts with
     * OP_CONNECT only and becomes readable/writable once connected.
     */
    void register(Selector selector) throws IOException {
        client.pipe = this;
        upstream.pipe = this;
        client.key = client.channel.register(selector, SelectionKey.OP_READ, client);
        upstream.key = upstream.channel.register(selector, SelectionKey.OP_CONNECT, upstream);
    }

    /** Completes the non-blocking connect started in ProxyServer.accept(). */
    void onConnectable(End end) throws IOException {
        // Throws if the handshake failed (refused, unreachable) - the caller
        // turns that into pipe teardown.
        end.channel.finishConnect();
        end.connecting = false;
        LOGGER.fine(() -> "Upstream connected " + label(end));
        updateInterestOps();
    }

    /**
     * Handles read-readiness on {@code end}: moves up to one buffer of data
     * into the peer's outbound queue.
     */
    void onReadable(End end) throws IOException {
        ByteBuffer data = pool.acquire();
        int n = end.channel.read(data);
        if (n > 0) {
            data.flip();
            if (mirror != null) {
                // duplicate() shares content but has its own position/limit,
                // so mirroring does not disturb the queued copy.
                mirrorPayload(data.duplicate());
            }
            peer(end).enqueue(data);
            applyBackpressure(end);
            updateInterestOps();
        } else {
            pool.release(data);
            if (n < 0) {
                // n == -1: orderly FIN. This end produces no more data; what
                // it already produced still needs flushing to the peer first.
                end.inputOpen = false;
                LOGGER.fine(() -> "EOF from " + label(end));
                propagateClosure();
                updateInterestOps();
            }
            // n == 0: spurious readiness, nothing to do.
        }
    }

    /**
     * Handles write-readiness on {@code end}: drains as much queued data as
     * the socket accepts. A partial write ({@code head.hasRemaining()}) just
     * leaves the rest queued for the next OP_WRITE - never spin, never block.
     */
    void onWritable(End end) throws IOException {
        int flushed = 0;
        while (flushed < MAX_FLUSH_BUFFERS && !end.outbound.isEmpty()) {
            ByteBuffer head = end.outbound.peekFirst();
            int n = end.channel.write(head);
            end.pendingBytes -= n;
            if (head.hasRemaining()) {
                break; // socket send buffer full; retry on next writable event
            }
            end.outbound.pollFirst();
            pool.release(head);
            flushed++;
        }
        relaxBackpressure(end);
        propagateClosure();
        updateInterestOps();
    }

    /** Tears down both ends; idempotent so multiple paths may call it. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeEnd(client);
        closeEnd(upstream);
        LOGGER.fine("Pipe closed");
    }

    private End peer(End end) {
        return end == client ? upstream : client;
    }

    private void mirrorPayload(ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            mirror.write(data);
        }
    }

    /**
     * Pauses reading from {@code end} when the data queued toward its peer
     * crosses the high watermark - the fast sender must not outpace the slow
     * receiver into unbounded memory growth.
     */
    private void applyBackpressure(End end) {
        if (!end.readPaused && peer(end).pendingBytes >= HIGH_WATERMARK) {
            end.readPaused = true;
            LOGGER.fine(() -> "Paused reading " + label(end));
        }
    }

    private void relaxBackpressure(End end) {
        if (end.readPaused && peer(end).pendingBytes <= LOW_WATERMARK) {
            end.readPaused = false;
            LOGGER.fine(() -> "Resumed reading " + label(end));
        }
    }

    /**
     * Re-evaluates closure state after every flush or EOF. Half-closes an end
     * once its producer finished AND all produced data reached it; closes the
     * whole pipe once both directions are exhausted.
     */
    private void propagateClosure() throws IOException {
        halfCloseIfNeeded(client);
        halfCloseIfNeeded(upstream);
        if (!client.inputOpen && !upstream.inputOpen
                && client.outbound.isEmpty() && upstream.outbound.isEmpty()) {
            close();
        }
    }

    /**
     * If the source feeding {@code end} hit EOF and we have delivered
     * everything it sent, forward the FIN via shutdownOutput: {@code end}
     * observes a normal end-of-stream while the reverse direction keeps
     * flowing.
     */
    private void halfCloseIfNeeded(End end) throws IOException {
        End source = peer(end);
        if (end.outputOpen && !source.inputOpen && end.outbound.isEmpty()) {
            end.channel.shutdownOutput();
            end.outputOpen = false;
            LOGGER.fine(() -> "Half-closed output of " + label(end));
        }
    }

    /**
     * Recomputes interest sets from current state. Interest ops are always
     * DERIVED, never patched ad hoc - this is what keeps OP_READ/OP_WRITE
     * consistent across backpressure, EOF and connect transitions:
     * <ul>
     *   <li>OP_CONNECT - while the non-blocking handshake is in flight</li>
     *   <li>OP_READ   - connected, input open, not paused by backpressure</li>
     *   <li>OP_WRITE  - connected, output open, has queued data</li>
     * </ul>
     */
    private void updateInterestOps() {
        updateOps(client);
        updateOps(upstream);
    }

    private void updateOps(End end) {
        if (end.key == null || !end.key.isValid()) {
            return;
        }
        int ops = 0;
        if (end.connecting) {
            // Handshake still in flight: OP_CONNECT must survive every
            // recomputation. Early client data queues up meanwhile and is
            // flushed right after connect completes. Recomputing only
            // READ/WRITE here would silently DROP OP_CONNECT and hang the
            // pipe forever - exactly the bug this branch fixes.
            ops = SelectionKey.OP_CONNECT;
        } else {
            if (end.inputOpen && !end.readPaused) {
                ops |= SelectionKey.OP_READ;
            }
            if (end.outputOpen && !end.outbound.isEmpty()) {
                ops |= SelectionKey.OP_WRITE;
            }
        }
        // Only touch the key when needed: interestOps() is a syscall.
        if (end.key.interestOps() != ops) {
            end.key.interestOps(ops);
        }
    }

    private void closeEnd(End end) {
        if (end.key != null) {
            end.key.cancel();
        }
        // Return queued buffers to the pool instead of leaking direct memory.
        ByteBuffer buffered;
        while ((buffered = end.outbound.pollFirst()) != null) {
            pool.release(buffered);
        }
        try {
            end.channel.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINEST, "Failed to close " + label(end), e);
        }
    }

    private String label(End end) {
        try {
            return String.valueOf(end.channel.getRemoteAddress());
        } catch (IOException e) {
            return end == client ? "client" : "upstream";
        }
    }
}
