package mproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single-threaded NIO reactor ("reactor" pattern).
 *
 * <p>One thread owns everything: the {@link Selector} (the demultiplexer), all
 * registered channels, and every byte of connection state. The thread never
 * performs a blocking operation on a peer's behalf - reads and writes happen
 * only when the selector reports readiness, so a slow or stalled client cannot
 * stall any other client.</p>
 *
 * <p>State is confined to this single thread, which means no locks anywhere:
 * the price of that simplicity is that handlers must be cheap. Anything
 * CPU-heavy or blocking (console IO, TLS crypto) would have to be offloaded,
 * or the whole event loop would stutter.</p>
 */
final class ProxyServer {

    private static final Logger LOGGER = Logger.getLogger(ProxyServer.class.getName());
    private static final int BACKLOG = 1024;

    /**
     * Non-blocking connects have no built-in timeout: a blackholed remote
     * would otherwise linger for minutes (kernel SYN retries) holding the
     * client hostage. The event loop wakes up periodically to sweep them.
     */
    private static final long CONNECT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int SELECT_TIMEOUT_MILLIS = 500;

    private final InetSocketAddress remoteAddress;
    private final int localPort;
    private final BufferPool bufferPool = new BufferPool(Pipe.BUFFER_SIZE, 64);
    private final WritableByteChannel mirror;

    private Selector selector;
    private ServerSocketChannel serverChannel;

    /**
     * Written by the shutdown hook (another thread), read by the event loop -
     * hence volatile. The hook also calls {@code selector.wakeup()} so the
     * blocked {@code select()} returns immediately instead of waiting for the
     * next event.
     */
    private volatile boolean running = true;

    ProxyServer(InetSocketAddress remoteAddress, int localPort, WritableByteChannel mirror) {
        this.remoteAddress = remoteAddress;
        this.localPort = localPort;
        this.mirror = mirror;
    }

    void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(localPort), BACKLOG);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "proxy-shutdown"));
        LOGGER.info("Listening on local port " + localPort + ", forwarding to " + remoteAddress);
        try {
            eventLoop();
        } finally {
            closeQuietly(serverChannel);
            closeQuietly(selector);
        }
    }

    /**
     * The reactor loop: wait for readiness events, then dispatch each ready
     * channel to its handler. All multiplexed connections are served by this
     * single iteration point.
     */
    private void eventLoop() throws IOException {
        while (running) {
            try {
                // Timed select: also serves as the tick for the connect
                // timeout sweep below. wakeup() still interrupts it.
                selector.select(SELECT_TIMEOUT_MILLIS);
            } catch (ClosedSelectorException e) {
                break;
            }
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                // Remove BEFORE processing: handlers may cancel/close channels,
                // and the selected-keys set must not accumulate stale entries.
                it.remove();
                dispatch(key);
            }
            enforceConnectTimeout();
        }
    }

    /** Closes pipes whose upstream handshake exceeded {@link #CONNECT_TIMEOUT_NANOS}. */
    private void enforceConnectTimeout() {
        long now = System.nanoTime();
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof End end
                    && end.connecting && now - end.connectStartedAt > CONNECT_TIMEOUT_NANOS) {
                LOGGER.warning(() -> "Upstream connect timeout, closing " + label(end.channel));
                end.pipe.close();
            }
        }
    }

    /**
     * Dispatches one readiness event. Failures are caught PER CONNECTION:
     * an IOException on some client's socket closes just that pipe instead of
     * propagating out of the loop and killing the proxy.
     */
    private void dispatch(SelectionKey key) {
        if (!key.isValid()) {
            return;
        }
        try {
            if (key.isAcceptable()) {
                accept();
                return;
            }
            End end = (End) key.attachment();
            Pipe pipe = end.pipe;
            if (key.isConnectable()) {
                pipe.onConnectable(end);
            }
            // Re-check validity: the handler above may have closed this channel.
            if (key.isValid() && key.isReadable()) {
                pipe.onReadable(end);
            }
            if (key.isValid() && key.isWritable()) {
                pipe.onWritable(end);
            }
        } catch (CancelledKeyException | IOException e) {
            LOGGER.log(Level.FINE, "Connection failed", e);
            closePipe(key);
        }
    }

    /**
     * Accepts a new client and pairs it with an upstream connection.
     *
     * <p>The upstream connect is NON-BLOCKING ({@code connect()} + later
     * {@code finishConnect()} on OP_CONNECT): DNS lookup and TCP handshake run
     * asynchronously, so new clients keep being accepted and already-connected
     * clients keep transferring data while we wait. A blocking
     * {@code SocketChannel.open(remote)} here would freeze the entire event
     * loop for the duration of every handshake.</p>
     */
    private void accept() throws IOException {
        // Non-blocking accept: null simply means the readiness was consumed by
        // another wakeup or the connection was dropped meanwhile.
        SocketChannel client = serverChannel.accept();
        if (client == null) {
            return;
        }
        SocketChannel upstream = SocketChannel.open();
        client.configureBlocking(false);
        upstream.configureBlocking(false);
        try {
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);
            upstream.setOption(StandardSocketOptions.TCP_NODELAY, true);
            upstream.connect(remoteAddress);
        } catch (IOException e) {
            closeQuietly(client);
            closeQuietly(upstream);
            return;
        }
        new Pipe(bufferPool, client, upstream, mirror).register(selector);
        LOGGER.fine(() -> "Accepted " + label(client));
    }

    private void closePipe(SelectionKey key) {
        Object attachment = key.attachment();
        if (attachment instanceof End end) {
            end.pipe.close();
        }
    }

    private void shutdown() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
    }

    private static void closeQuietly(AutoCloseable channel) {
        try {
            channel.close();
        } catch (Exception e) {
            LOGGER.log(Level.FINEST, "Failed to close resource", e);
        }
    }

    private static String label(SocketChannel channel) {
        try {
            return String.valueOf(channel.getRemoteAddress());
        } catch (IOException e) {
            return "?";
        }
    }
}
