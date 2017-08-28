package de.johni0702.proxywitness;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;

public class ClientHandler implements Runnable {
    private static final String HTTP_VERSION = "HTTP/1.1";

    private final Socket socket;
    private final ArtifactFetcher artifactFetcher;

    public ClientHandler(Socket socket, ArtifactFetcher artifactFetcher) {
        this.socket = socket;
        this.artifactFetcher = artifactFetcher;
    }

    @Override
    public void run() {
        try (Socket socket = this.socket;
             InputStream sin = socket.getInputStream();
             InputStreamReader rin = new InputStreamReader(sin);
             BufferedReader in = new BufferedReader(rin);
             OutputStream sout = socket.getOutputStream();
             OutputStreamWriter wout = new OutputStreamWriter(sout);
             BufferedWriter out = new BufferedWriter(wout)) {
            boolean cleanExit = false;
            try {
                String line;
                while (true) {
                    // Read request line
                    line = in.readLine();
                    if (line == null) {
                        // Client disconnected unexpectedly
                        cleanExit = true;
                        return;
                    }
                    String[] request = line.split(" ");
                    String method = request[0];
                    String uri = request[1];
                    String version = request[2];

                    // Read headers
                    Map<String, String> headers = new HashMap<>();
                    while (!"".equals(line = in.readLine().trim())) {
                        String[] split = line.split(": *", 2);
                        headers.put(split[0], split[1]);
                    }

                    // Handle request
                    if (!("HTTP/1.0".equals(version) || "HTTP/1.1".equals(version))) {
                        responseHeader(out, "505 HTTP Version not supported");
                        closeConnection(out);
                        cleanExit = true;
                        return;
                    }
                    boolean head = "HEAD".equals(method);
                    if (head || "GET".equals(method)) {
                        ArtifactFetcher.Artifact artifact = artifactFetcher.getArtifact(uri, head);
                        if (artifact == null) {
                            responseHeader(out, "404 Not Found");
                            closeConnection(out);
                            cleanExit = true;
                            return;
                        } else {
                            responseHeader(out, "200 OK");
                            if (artifact.getLastModified() != 0) {
                                out.write("Last-Modified: ");
                                RFC_1123_DATE_TIME.withZone(ZoneId.systemDefault())
                                        .formatTo(Instant.ofEpochMilli(artifact.getLastModified()), out);
                                out.write("\r\n");
                            }
                            out.write("Content-Length: " + String.valueOf(artifact.getDataLength()) + "\r\n");
                            out.write("\r\n");
                            if (!head) {
                                out.flush();
                                sout.write(artifact.getData());
                            }
                        }
                    } else {
                        responseHeader(out, "501 Not Implemented");
                        closeConnection(out);
                        cleanExit = true;
                        return;
                    }
                    if ("HTTP/1.0".equals(version) || headers.getOrDefault("Proxy-Connection", "Keep-Alive").equals("close")) {
                        cleanExit = true;
                        return;
                    }
                    out.flush();
                }
            } finally {
                if (!cleanExit) {
                    responseHeader(out, "500 Internal Server Error");
                    closeConnection(out);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void responseHeader(Writer out, String status) throws IOException {
        out.write(HTTP_VERSION);
        out.write(' ');
        out.write(status);
        out.write("\r\n");
    }

    private static void closeConnection(Writer out) throws IOException {
        out.write("Content-Length: 0\r\n");
        out.write("Connection: close\r\n\r\n");
    }
}
