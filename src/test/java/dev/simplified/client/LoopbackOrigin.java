package dev.simplified.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import dev.simplified.client.factory.ApacheClientFactory;
import dev.simplified.client.request.Contract;
import feign.Request;
import feign.hc5.ApacheHttp5Client;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * An HTTPS origin on a loopback port that records every request reaching it, and the
 * {@link Client} that talks to it through the production Apache transport.
 * <p>
 * The origin's certificate is a self-signed key pair {@code keytool} generates once per JVM. A
 * client {@link #client(ClientConfig) built} for the origin sends each request through
 * {@link ApacheClientFactory}'s transport, trusting the certificate, with the contract's
 * {@code 127.0.0.1} route pointed at the origin's port; it answers the connection warm-up probe
 * itself, so every connection the origin sees is one a contract request opened.
 */
final class LoopbackOrigin implements AutoCloseable {

    /**
     * The password of the generated key store and its key.
     */
    private static final char[] PASSWORD = "loopback".toCharArray();

    /**
     * The generated key store, created on first use.
     */
    private static KeyStore keyStore;

    /**
     * Answers one request the origin received.
     */
    @FunctionalInterface
    interface Answer {

        /**
         * Answers a request.
         *
         * @param request the request as the origin received it
         * @param exchange the exchange to answer on
         * @throws IOException if writing the answer fails
         */
        void answer(@NotNull Received request, @NotNull HttpExchange exchange) throws IOException;

    }

    /**
     * One request as the origin received it.
     *
     * @param method the request method
     * @param uri the request URI, its query as sent
     * @param headers the request headers, keyed case-insensitively
     * @param tlsProtocol the TLS protocol the connection negotiated
     * @param tlsCipher the cipher suite the connection negotiated
     */
    record Received(
        @NotNull String method,
        @NotNull URI uri,
        @NotNull Map<String, List<String>> headers,
        @NotNull String tlsProtocol,
        @NotNull String tlsCipher
    ) {

        /**
         * The values of a header, empty when the request carried none.
         *
         * @param name the header name
         * @return the header's values
         */
        @NotNull List<String> header(@NotNull String name) {
            return this.headers.getOrDefault(name, List.of());
        }

    }

    /**
     * The server the origin runs on.
     */
    private final @NotNull HttpsServer server;

    /**
     * Every request the origin received, in the order it arrived.
     */
    private final @NotNull List<Received> received = new CopyOnWriteArrayList<>();

    /**
     * Starts an origin answering every request with the given answer.
     *
     * @param answer the answer to each request
     * @throws IOException if the server cannot start or the key store cannot be generated
     * @throws GeneralSecurityException if the server's TLS context cannot be built
     */
    LoopbackOrigin(@NotNull Answer answer) throws IOException, GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore(), PASSWORD);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, new SecureRandom());

        this.server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setHttpsConfigurator(new HttpsConfigurator(tls));
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", exchange -> {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
            SSLSession session = ((HttpsExchange) exchange).getSSLSession();
            Received request = new Received(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                headers,
                session.getProtocol(),
                session.getCipherSuite()
            );

            this.received.add(request);

            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }

            answer.answer(request, exchange);
        });
        this.server.start();
    }

    /**
     * Answers an exchange with a status, headers given as alternating names and values, and a
     * text body.
     *
     * @param exchange the exchange to answer
     * @param status the status code
     * @param body the body text
     * @param headerPairs the headers, as alternating names and values
     * @throws IOException if writing the answer fails
     */
    static void respond(@NotNull HttpExchange exchange, int status, @NotNull String body, @NotNull String... headerPairs) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < headerPairs.length; i += 2)
            exchange.getResponseHeaders().add(headerPairs[i], headerPairs[i + 1]);

        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Every request the origin received, in the order it arrived.
     *
     * @return the received requests
     */
    @NotNull List<Received> received() {
        return List.copyOf(this.received);
    }

    /**
     * Builds a client for a contract routed to {@code 127.0.0.1}, sending each request to this
     * origin through the transport {@link ApacheClientFactory} configures from {@code config}.
     *
     * @param config the client's configuration
     * @param <C> the contract type
     * @return the client
     */
    <C extends Contract> @NotNull Client<C> client(@NotNull ClientConfig<C> config) {
        feign.Client apache = new ApacheHttp5Client(ApacheClientFactory.configure(
            config.getTimings(),
            config.getQueries(),
            config.getInet6Address(),
            new DefaultClientTlsStrategy(trustAll(), NoopHostnameVerifier.INSTANCE)
        ).build());
        String origin = "https://127.0.0.1:" + this.server.getAddress().getPort();

        return new Client<>(config, (request, options) -> {
            if (request.httpMethod() == Request.HttpMethod.HEAD) {
                return feign.Response.builder()
                    .status(200)
                    .reason("OK")
                    .request(request)
                    .headers(Map.<String, Collection<String>>of())
                    .body(new byte[0])
                    .build();
            }

            String url = request.url().replaceFirst("^https://127\\.0\\.0\\.1(:\\d+)?", origin);
            return apache.execute(Request.create(
                request.httpMethod(),
                url,
                request.headers(),
                request.body(),
                request.charset(),
                request.requestTemplate()
            ), options);
        });
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        this.server.stop(0);
    }

    /**
     * Builds a TLS context that trusts every certificate, the origin's self-signed one included.
     *
     * @return the TLS context
     */
    private static @NotNull SSLContext trustAll() {
        TrustManager[] trustAll = {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Generates, on first call, a key store holding one self-signed EC key pair for
     * {@code 127.0.0.1}, with the running JDK's {@code keytool}.
     *
     * @return the key store
     * @throws IOException if {@code keytool} cannot run or fails
     * @throws GeneralSecurityException if the generated key store cannot be read
     */
    private static synchronized @NotNull KeyStore keyStore() throws IOException, GeneralSecurityException {
        if (keyStore != null)
            return keyStore;

        Path directory = Files.createTempDirectory("loopback-origin");
        Path file = directory.resolve("origin.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process keytool = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-genkeypair",
            "-alias", "origin",
            "-keyalg", "EC",
            "-groupname", "secp256r1",
            "-dname", "CN=127.0.0.1",
            "-ext", "SAN=ip:127.0.0.1",
            "-validity", "1",
            "-storetype", "PKCS12",
            "-keystore", file.toString(),
            "-storepass", new String(PASSWORD),
            "-keypass", new String(PASSWORD)
        ).redirectErrorStream(true).start();

        try {
            String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (keytool.waitFor() != 0)
                throw new IOException(String.format("keytool failed: '%s'", output));

            KeyStore generated = KeyStore.getInstance("PKCS12");

            try (InputStream in = Files.newInputStream(file)) {
                generated.load(in, PASSWORD);
            }

            keyStore = generated;
            return generated;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

}
