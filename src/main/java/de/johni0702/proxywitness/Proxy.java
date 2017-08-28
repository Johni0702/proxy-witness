package de.johni0702.proxywitness;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Proxy {
    private final ExecutorService clientHandlers = Executors.newCachedThreadPool();
    private final int port;
    private final ArtifactFetcher artifactFetcher;

    public Proxy(int port, ArtifactFetcher artifactFetcher) {
        this.port = port;
        this.artifactFetcher = artifactFetcher;
    }

    public void run() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        while (!Thread.interrupted()) {
            Socket socket = serverSocket.accept();
            clientHandlers.submit(new ClientHandler(socket, artifactFetcher));
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java -jar proxy-witness.jar <port> <checksums>");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);

        Map<String, String> checksums = Files.readAllLines(Paths.get(args[1])).stream().map(s -> s.split(" ", 2))
                .collect(Collectors.toMap(s -> s[1], s -> s[0]));

        ArtifactFetcher fetcher = new ArtifactFetcher(checksums);
        new Proxy(port, fetcher).run();
    }
}
