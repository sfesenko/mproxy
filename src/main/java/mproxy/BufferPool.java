package mproxy;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Recycles direct ByteBuffers on the single reactor thread.
 *
 * <p>Direct buffers are expensive to allocate and are the right choice here
 * because the JVM copies heap buffers into temporary direct buffers on every
 * socket IO anyway. The cap on retained buffers bounds off-heap usage; excess
 * releases fall back to garbage collection.</p>
 */
final class BufferPool {

    private final int bufferSize;
    private final int maxRetained;
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();

    BufferPool(int bufferSize, int maxRetained) {
        this.bufferSize = bufferSize;
        this.maxRetained = maxRetained;
    }

    ByteBuffer acquire() {
        ByteBuffer buffer = pool.pollFirst();
        return buffer != null ? buffer : ByteBuffer.allocateDirect(bufferSize);
    }

    void release(ByteBuffer buffer) {
        if (pool.size() < maxRetained) {
            buffer.clear();
            pool.addLast(buffer);
        }
    }
}
