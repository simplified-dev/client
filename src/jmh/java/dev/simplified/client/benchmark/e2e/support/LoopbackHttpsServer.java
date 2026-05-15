package dev.simplified.client.benchmark.e2e.support;

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
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Self-contained HTTPS fixture for loopback JMH benchmarks. Loads a pre-generated PKCS12
 * keystore (committed as a JMH resource) on construction, brings up
 * {@link HttpsServer} on a fixed loopback port, and exposes the
 * matching trust-all {@link SSLContext} that the client side must use to accept the
 * self-signed certificate.
 *
 * <p>The fixed port is chosen high enough to avoid common conflicts; if it is in use,
 * {@link #start()} will throw and the benchmark will fail-fast.</p>
 */
public final class LoopbackHttpsServer {

    /**
     * Fixed loopback port - matches {@link LoopbackContract#ROUTE}.
     */
    public static final int PORT = 47652;

    private static final @NotNull String KEYSTORE_RESOURCE =
        "/dev/simplified/client/benchmark/e2e/support/loopback-benchmark.p12";
    private static final char @NotNull [] KEYSTORE_PASSWORD = "benchmark".toCharArray();

    private HttpsServer server;
    private SSLContext clientSslContext;

    public void start() throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = LoopbackHttpsServer.class.getResourceAsStream(KEYSTORE_RESOURCE)) {
            if (in == null)
                throw new IllegalStateException("Loopback benchmark keystore not on classpath: " + KEYSTORE_RESOURCE);
            keyStore.load(in, KEYSTORE_PASSWORD);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, new SecureRandom());

        this.server = HttpsServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        this.server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        registerHandlers(this.server);

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

    public void stop() {
        if (this.server != null) this.server.stop(0);
        this.server = null;
        this.clientSslContext = null;
    }

    public @NotNull SSLContext clientSslContext() {
        if (this.clientSslContext == null)
            throw new IllegalStateException("Server not started");
        return this.clientSslContext;
    }

    private static void registerHandlers(@NotNull HttpsServer server) {
        server.createContext("/small", exchange ->
            respond(exchange, 200, FixtureBodies.SMALL_JSON, jsonHeaders(FixtureBodies.SMALL_JSON.length)));

        server.createContext("/cacheable", exchange ->
            respond(exchange, 200, FixtureBodies.SMALL_JSON, Map.of(
                "Content-Type", List.of("application/json; charset=utf-8"),
                "Content-Length", List.of(Integer.toString(FixtureBodies.SMALL_JSON.length)),
                "Cache-Control", List.of("public, max-age=3600"),
                "ETag", List.of("\"loopback-v1\"")
            )));

        server.createContext("/error", exchange ->
            respond(exchange, 503, FixtureBodies.ERROR_JSON, jsonHeaders(FixtureBodies.ERROR_JSON.length)));
    }

    private static Map<String, Collection<String>> jsonHeaders(int contentLength) {
        return Map.of(
            "Content-Type", List.of("application/json; charset=utf-8"),
            "Content-Length", List.of(Integer.toString(contentLength))
        );
    }

    private static void respond(@NotNull HttpExchange exchange, int status, byte @NotNull [] body, @NotNull Map<String, Collection<String>> headers) throws IOException {
        headers.forEach((name, values) -> values.forEach(value -> exchange.getResponseHeaders().add(name, value)));
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

}
