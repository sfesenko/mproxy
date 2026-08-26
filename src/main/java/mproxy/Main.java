package mproxy;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        InetSocketAddress remote = new InetSocketAddress(options.remoteHost(), options.remotePort());
        if (remote.getAddress() == null) {
            System.err.println("Error: cannot resolve host " + options.remoteHost());
            System.exit(2);
            return;
        }
        WritableByteChannel mirror = options.mirrorPayloads()
                ? Channels.newChannel(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)))
                : null;
        new ProxyServer(remote, options.localPort(), mirror).start();
    }

    private static void usage() {
        System.err.println("""
                TCP/IP proxy (single-threaded NIO reactor)
                Usage: mproxy -h <host> -p <port> [-l <local port>] [-v]
                  -h  remote host to forward traffic to
                  -p  remote port
                  -l  local listening port (default 9999)
                  -v  mirror transferred payloads to stdout""");
    }
}
