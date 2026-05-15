package dev.simplified.client.springbench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Mock Mojang HTTPS server backing the Spring bench. Serves the
 * {@code /minecraft/profile/lookup/name/{username}} route with a deterministic,
 * Mojang-shaped JSON body so the bench measures the client library end-to-end
 * (Spring controller, Feign proxy, Apache HC5 pool, TLS, decode) without
 * involving the real Mojang API or its 200-req-per-2-minute IP rate limit.
 *
 * <p>The TLS material is the same PKCS12 keystore committed alongside the JMH
 * benchmarks at {@code /dev/simplified/client/benchmark/e2e/support/loopback-benchmark.p12},
 * picked up from the shared resources directory. The Spring bench's client
 * trusts it via a permissive {@link X509TrustManager}.</p>
 *
 * <p>Two response modes are exposed:</p>
 * <ul>
 *   <li>{@code /minecraft/profile/lookup/name/{username}} - no {@code Cache-Control}
 *       header. Every call is a fresh decode through the library; the
 *       {@link dev.simplified.client.cache.CachingFeignClient} sees no
 *       fresh-hit opportunity. <b>This is the cache-miss path.</b></li>
 *   <li>{@code /minecraft/profile/lookup/name-cached/{username}} - emits
 *       {@code Cache-Control: public, max-age=60}. Repeat calls for the same
 *       username short-circuit at the cache layer. <b>This is the cache-hit
 *       path.</b></li>
 * </ul>
 */
public final class MockMojangServer {

    /** Fixed loopback port chosen to match the JMH benchmark fixture. */
    public static final int PORT = 47652;

    private static final @NotNull String KEYSTORE_RESOURCE =
        "/dev/simplified/client/benchmark/e2e/support/loopback-benchmark.p12";
    private static final char @NotNull [] KEYSTORE_PASSWORD = "benchmark".toCharArray();

    private HttpsServer server;
    private SSLContext clientSslContext;

    /**
     * Starts the HTTPS server on the fixed loopback port.
     *
     * @throws IOException if the keystore cannot be read or the socket cannot be bound
     * @throws GeneralSecurityException if the keystore is malformed or the TLS context cannot be built
     */
    public void start() throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = MockMojangServer.class.getResourceAsStream(KEYSTORE_RESOURCE)) {
            if (in == null)
                throw new IllegalStateException("Bench keystore not on classpath: " + KEYSTORE_RESOURCE);
            keyStore.load(in, KEYSTORE_PASSWORD);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, new SecureRandom());

        this.server = HttpsServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        this.server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        this.server.createContext("/minecraft/profile/lookup/name/", exchange ->
            handleLookup(exchange, "/minecraft/profile/lookup/name/", false));
        this.server.createContext("/minecraft/profile/lookup/name-cached/", exchange ->
            handleLookup(exchange, "/minecraft/profile/lookup/name-cached/", true));

        this.server.start();

        TrustManager[] trustAll = {
            new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
        };
        this.clientSslContext = SSLContext.getInstance("TLS");
        this.clientSslContext.init(null, trustAll, new SecureRandom());
    }

    /**
     * Stops the server, releasing the port immediately.
     */
    public void stop() {
        if (this.server != null) this.server.stop(0);
        this.server = null;
        this.clientSslContext = null;
    }

    /**
     * The trust-all {@link SSLContext} the client side must use to accept this
     * server's self-signed certificate.
     *
     * @return the client-side SSL context
     */
    public @NotNull SSLContext clientSslContext() {
        if (this.clientSslContext == null)
            throw new IllegalStateException("Server not started");
        return this.clientSslContext;
    }

    private static void handleLookup(@NotNull HttpExchange exchange, @NotNull String prefix, boolean cacheable) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String username = path.length() > prefix.length() ? path.substring(prefix.length()) : "";
        if (username.isBlank() || username.contains("/")) {
            respond(exchange, 404, "{\"path\":\"" + path + "\",\"error\":\"NOT_FOUND\"}", cacheable);
            return;
        }
        String body = "{\"id\":\"" + deterministicId(username) + "\",\"name\":\"" + username + "\"}";
        respond(exchange, 200, body, cacheable);
    }

    private static void respond(@NotNull HttpExchange exchange, int status, @NotNull String json, boolean cacheable) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        if (cacheable) {
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=60");
            exchange.getResponseHeaders().add("ETag", "\"mock-v1\"");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Builds a 32-character (no-dash) UUID hex string from a SHA-256 of the username,
     * mirroring the shape Mojang returns for {@code /minecraft/profile/lookup/name/{username}}.
     * Deterministic so cache keys behave consistently across runs.
     *
     * @param username the username to hash
     * @return a 32-character UUID hex string, no dashes
     */
    private static @NotNull String deterministicId(@NotNull String username) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(username.getBytes(StandardCharsets.UTF_8));
            byte[] sixteen = new byte[16];
            System.arraycopy(hash, 0, sixteen, 0, 16);
            UUID uuid = UUID.nameUUIDFromBytes(sixteen);
            return uuid.toString().replace("-", "");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
