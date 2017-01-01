package mproxy;

import java.io.IOException;
import java.util.logging.Logger;

/**
 *
 */
public class Main {

    private static final Logger LOGGER = Logger.getLogger(Main.class.toString());

    public static void main(String[] args) throws IOException {
        int port = -1;
        int localPort = 9999;
        String host = null;
        for (int i = 0; i < args.length; i += 2) {
            String value = args[i + 1];
            switch (args[i]) {
                case "-h":
                    host = value;
                    break;
                case "-p":
                    port = Integer.parseInt(value);
                    break;
                case "-l":
                    localPort = Integer.parseInt(value);
                    break;
                default:
                    break;
            }
        }
        if (host == null || port == -1) {
            ussage();
            System.exit(1);
        }
        new Server(host, port, localPort).start();
    }

    private static void ussage() {
        LOGGER.info("TCP/IP proxy\n" +
                "Usage: mproxy -h <target host> -p <target port> -l <local port>");
    }
}

