package de.johni0702.proxywitness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ArtifactFetcher {
    private final Set<String> httpUris;
    private final Map<String, Set<String>> checksums;
    private final Map<String, Optional<Artifact>> cache = new ConcurrentHashMap<>();
    private boolean useCache = true;

    public ArtifactFetcher(Set<String> httpUris,
                           Map<String, Set<String>> checksums) {
        this.httpUris = httpUris;
        this.checksums = checksums;
    }

    public void setUseCache(boolean useCache) {
        this.useCache = useCache;
    }

    public Artifact getArtifact(String uriString, boolean head) throws IOException {
        if (uriString.contains("SNAPSHOT")) {
            System.err.println("Tried to fetch SNAPSHOT artifact: " + uriString);
            return null;
        }

        Optional<Artifact> cached = useCache ? cache.get(uriString) : null;
        if (cached != null) {
            if (cached.isPresent()) {
                Artifact artifact = cached.get();
                // Only return from cache if this is either a HEAD or the cached artifact was from a GET (has content)
                if (head || artifact.data != null) {
                    return artifact;
                }
            } else {
                return null;
            }
        }

        Set<String> expectedHash = Collections.emptySet();
        for (Map.Entry<String, Set<String>> entry : checksums.entrySet()) {
            if (uriString.endsWith(entry.getKey())) {
                expectedHash = entry.getValue();
            }
        }
        if (expectedHash.isEmpty() && !(uriString.endsWith(".sha1") || checksums.isEmpty())) {
            System.err.println("Tried to fetch unknown artifact: " + uriString);
            if (useCache) cache.put(uriString, Optional.empty());
            return null;
        }

        URL url = new URL(uriString);
        if (!httpUris.contains(uriString)) {
            // Force https
            url = new URL("https", url.getHost(), url.getPort(), url.getFile());
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(head ? "HEAD" : "GET");
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        try {
            connection.connect();
            if (connection.getResponseCode() != 200) {
                if (connection.getResponseCode() != 404) {
                    // 404 is somewhat to be expected with multiple repos, so we don't print out the error for it
                    System.err.println("Got non 200 response for " + uriString + ", namely: "
                            + connection.getResponseCode() + " " + connection.getResponseMessage());
                }
                if (useCache) cache.put(uriString, Optional.empty());
                return null;
            } else {
                Artifact artifact;
                if (!head) {
                    InputStream in = connection.getInputStream();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        buffer.write(buf, 0, read);
                    }
                    byte[] bytes = buffer.toByteArray();
                    String actualHash = Hash.sha256(bytes);
                    if (!expectedHash.contains("*") && !expectedHash.contains(actualHash)) {
                        if (!expectedHash.isEmpty()) {
                            System.err.println("Received unexpected hash for: " + uriString);
                            System.err.println("Expected: " + expectedHash);
                            System.err.println("But was:  " + actualHash);
                            if (useCache) cache.put(uriString, Optional.empty());
                            return null;
                        } else if (!uriString.endsWith(".sha1")) {
                            String path = url.getPath();
                            // Remove common prefixes
                            for (String prefix : Arrays.asList("/m2", "/maven2", "/maven")) {
                                if (path.startsWith(prefix)) {
                                    path = path.substring(prefix.length());
                                    break;
                                }
                            }
                            System.out.println(actualHash + " " + path);
                        }
                    }
                    artifact = new Artifact(connection.getLastModified(), bytes);
                } else {
                    artifact = new Artifact(connection.getLastModified(), connection.getContentLength());
                }
                if (useCache) cache.put(uriString, Optional.of(artifact));
                return artifact;
            }
        } finally {
            connection.disconnect();
        }
    }

    public static class Artifact {
        private final long lastModified;
        private final long dataLength;
        private final byte[] data;

        private Artifact(long lastModified, long dataLength) {
            this.lastModified = lastModified;
            this.dataLength = dataLength;
            this.data = null;
        }

        private Artifact(long lastModified, byte[] data) {
            this.lastModified = lastModified;
            this.dataLength = data.length;
            this.data = data;
        }

        public long getLastModified() {
            return lastModified;
        }

        public byte[] getData() {
            return data;
        }

        public long getDataLength() {
            return dataLength;
        }
    }
}
