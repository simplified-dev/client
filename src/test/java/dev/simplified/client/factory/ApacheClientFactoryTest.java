package dev.simplified.client.factory;

import com.sun.net.httpserver.HttpServer;
import dev.simplified.client.request.Timings;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.UnsupportedAddressTypeException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ApacheClientFactoryTest {

    /**
     * Default timings with a two-second connect timeout, so a connection that cannot complete
     * fails without holding the suite for the default five.
     */
    private static final Timings TIMINGS = new Timings(
        120_000L,
        45_000L,
        30_000L,
        2_000L,
        5_000L,
        10,
        10,
        1024L * 1024L,
        60_000L
    );

    /**
     * Parses an IPv6 literal, which never performs a lookup.
     *
     * @param literal the address literal
     * @return the address
     * @throws IOException if the literal does not parse
     */
    private static @NotNull Inet6Address address(@NotNull String literal) throws IOException {
        return (Inet6Address) InetAddress.getByName(literal);
    }

    /**
     * Starts a server on an address that answers every request with {@code 204} and records the
     * peer each request arrived from.
     *
     * @param address the address to listen on
     * @param peers receives the peer of every request, in the order they arrive
     * @return the started server, or {@code null} when the host cannot listen on the address
     */
    private static @Nullable HttpServer listen(@NotNull InetAddress address, @NotNull List<InetSocketAddress> peers) {
        HttpServer server;

        try {
            server = HttpServer.create(new InetSocketAddress(address, 0), 0);
        } catch (IOException | UnsupportedAddressTypeException ex) {
            return null;
        }

        server.createContext("/", exchange -> {
            peers.add(exchange.getRemoteAddress());

            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }

            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * Sends a {@code GET} that asks for its connection to be closed after the response, so every
     * request opens a connection of its own.
     *
     * @param http the client to send through
     * @param uri the request URI
     * @return the response status
     * @throws IOException if the request fails
     */
    private static int get(@NotNull CloseableHttpClient http, @NotNull URI uri) throws IOException {
        HttpGet request = new HttpGet(uri);
        request.setHeader(HttpHeaders.CONNECTION, "close");

        return http.execute(request, response -> {
            EntityUtils.consume(response.getEntity());
            return response.getCode();
        });
    }

    @Test
    @DisplayName("Every connection a client configured with an IPv6 address opens reaches the origin from that address")
    void configuredAddressIsThePeerOfEveryConnection() throws IOException {
        Inet6Address loopback = address("::1");
        List<InetSocketAddress> peers = new CopyOnWriteArrayList<>();
        HttpServer server = listen(loopback, peers);
        assumeTrue(server != null, "The host has no IPv6 loopback");
        URI uri = URI.create("http://[::1]:" + server.getAddress().getPort() + "/");

        try (CloseableHttpClient http = ApacheClientFactory.configure(TIMINGS, Map.of(), Optional.of(loopback)).build()) {
            for (int i = 0; i < 3; i++)
                assertThat("request " + i, get(http, uri), is(204));
        } finally {
            server.stop(0);
        }

        assertThat(peers, hasSize(3));

        for (InetSocketAddress peer : peers)
            assertThat(peer.getAddress(), is(loopback));
    }

    @Test
    @DisplayName("A client configured with an IPv6 address the host does not hold cannot connect, where an unbound client can")
    void configuredAddressIsBoundBeforeConnecting() throws IOException {
        Inet6Address loopback = address("::1");
        // 2001:db8::/32 is reserved for documentation, so no host holds this address: binding to it
        // fails, and where the host allows a non-local bind the handshake never completes.
        Inet6Address unheld = address("2001:db8::1");
        List<InetSocketAddress> peers = new CopyOnWriteArrayList<>();
        HttpServer server = listen(loopback, peers);
        assumeTrue(server != null, "The host has no IPv6 loopback");
        URI uri = URI.create("http://[::1]:" + server.getAddress().getPort() + "/");

        try (
            CloseableHttpClient unbound = ApacheClientFactory.configure(TIMINGS, Map.of(), Optional.empty()).build();
            CloseableHttpClient bound = ApacheClientFactory.configure(TIMINGS, Map.of(), Optional.of(unheld)).build()
        ) {
            assertThat(get(unbound, uri), is(204));
            assertThrows(IOException.class, () -> get(bound, uri));
        } finally {
            server.stop(0);
        }

        assertThat(peers, hasSize(1));
    }

    @Test
    @DisplayName("A gzip-encoded body is read decoded, through a stream that can abort its exchange")
    void encodedBodyIsReadDecodedThroughAnAbortableStream() throws IOException {
        byte[] body = "decoded body".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream out = new GZIPOutputStream(exchange.getResponseBody())) {
                out.write(body);
            }

            exchange.close();
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");

        try (CloseableHttpClient http = ApacheClientFactory.configure(TIMINGS, Map.of(), Optional.empty()).build()) {
            byte[] read = http.execute(new HttpGet(uri), response -> {
                InputStream content = response.getEntity().getContent();
                assertThat(content, is(instanceOf(EofSensorInputStream.class)));
                return content.readAllBytes();
            });

            assertThat(read, is(body));
        } finally {
            server.stop(0);
        }
    }

}
