# mproxy

Single-threaded NIO TCP proxy (reactor pattern demo, Java 21).

Forwards traffic from a local port to a remote host:port.

## Build & run

    ./mvnw package
    java -jar target/mproxy.jar -h <host> -p <port> [-l <local port>] [-v]

Defaults to local port 9999; `-v` mirrors payloads to stdout.

## Design

- One thread, one `Selector`, zero locks — readiness events drive everything
- Per-connection outbound queues + partial-write handling
- Backpressure via read-pausing between high/low watermarks (128/64 KB)
- Non-blocking upstream connects (`OP_CONNECT`)
- TCP half-close propagation; per-connection error isolation
- Pooled direct buffers (32 KB)
