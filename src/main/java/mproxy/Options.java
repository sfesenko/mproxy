package mproxy;

record Options(String remoteHost, int remotePort, int localPort, boolean mirrorPayloads) {

    static Options parse(String[] args) {
        String host = null;
        Integer port = null;
        int localPort = 9999;
        boolean mirror = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h" -> host = requireValue(args, ++i, "-h");
                case "-p" -> port = parsePort(requireValue(args, ++i, "-p"), "-p");
                case "-l" -> localPort = parsePort(requireValue(args, ++i, "-l"), "-l");
                case "-v" -> mirror = true;
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        if (host == null || port == null) {
            throw new IllegalArgumentException("both -h <host> and -p <port> are required");
        }
        return new Options(host, port, localPort, mirror);
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[index];
    }

    private static int parsePort(String raw, String flag) {
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects a numeric port, got: " + raw);
        }
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(flag + " must be in 1..65535, got: " + value);
        }
        return value;
    }
}
