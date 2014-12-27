package mproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.logging.Logger;

class Server {

    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());

    private final ByteBuffer buffer;
    private final WritableByteChannel stdoutChannel;


    private InetSocketAddress address;
    private final int localPort;

    private ServerSocketChannel serverChannel;
    Selector selector;

    Server(String host, int port, int localPort) {
        this.address = new InetSocketAddress(host, port);
        this.localPort = localPort;
        LOGGER.info("Hello from ctr");
        buffer = ByteBuffer.allocateDirect(32768);
        stdoutChannel = Channels.newChannel(System.out);
    }

    void configureServer() throws IOException {
        LOGGER.info("Listen local port " + localPort);
        LOGGER.info("Remote host is " + address);
        serverChannel = ServerSocketChannel.open();
        ServerSocket serverSocket = serverChannel.socket();
        selector = Selector.open();
        serverSocket.bind(new InetSocketAddress(localPort));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    void start() throws IOException {
        configureServer();
        while (true) {
            int m = selector.select();
            System.out.println("\n*** [new loop] =" + m + " ***");
            if (m == 0) {
                continue;
            }
            for (Iterator<SelectionKey> it = selector.selectedKeys().iterator(); it.hasNext(); ) {
                SelectionKey key = it.next();
                if (key.isAcceptable()) {
                    ServerSocketChannel server = (ServerSocketChannel) key.channel();
                    SocketChannel serverChannel = server.accept();
                    SelectionKey serverKey = registerChannel(selector, serverChannel, SelectionKey.OP_READ);
                    SocketChannel clientChannel = SocketChannel.open(address);
                    SelectionKey clientKey = registerChannel(selector, clientChannel, SelectionKey.OP_READ);
                    serverKey.attach(clientChannel);
                    clientKey.attach(serverChannel);
                }

                if (key.isReadable()) {
                    readDataFromSocket(key);
                }
                it.remove();
            }
        }
    }

    private void readDataFromSocket(SelectionKey key) throws IOException {
        SocketChannel sourceChannel = (SocketChannel) key.channel();
        SocketChannel targetChannel = (SocketChannel) key.attachment();
        int count;
        buffer.clear();
        // Empty buffer
        // Loop while data is available; channel is nonblocking
        count = sourceChannel.read(buffer);
        if (count >= 0) {
            buffer.flip();
            targetChannel.write(buffer);
//            buffer.flip();
//            stdoutChannel.write(buffer);
        } else {
            LOGGER.info("Close channel " + sourceChannel.getRemoteAddress());
            sourceChannel.close();
        }
    }

    private SelectionKey registerChannel(Selector selector, SocketChannel channel, int ops) throws IOException {
        // Set the new channel nonblocking
        channel.configureBlocking(false);
        // Register it with the selector
        return channel.register(selector, ops);
    }
}
