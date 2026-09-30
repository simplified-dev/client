package dev.simplified.client;

import dev.simplified.client.exception.ApiException;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import dev.simplified.gson.GsonSettings;
import feign.Headers;
import feign.RequestLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a {@link Client}'s internal headers stay inside it: a {@link Client} sending through
 * the production Apache transport to a {@link LoopbackOrigin} over TLS, which answers
 * {@code /resource} with a {@code 200} and {@code /missing} with a {@code 404}, each carrying
 * internal headers of the origin's own.
 */
class ClientInternalHeadersTest {

    @Route(value = "127.0.0.1", rateLimit = @RateLimitConfig(unlimited = true))
    interface Origin extends Contract {

        @RequestLine("GET /resource")
        Response<byte[]> resource();

        @RequestLine("GET /missing")
        Response<byte[]> missing();

        @RequestLine("GET /resource")
        @Headers("X-Internal-Trace: c1")
        Response<byte[]> traced();

    }

    /**
     * The TCP connection start the origin sends as an internal header of its own.
     */
    private static final Instant FORGED_TCP_START = Instant.parse("2001-02-03T04:05:06Z");

    private final LoopbackOrigin origin = new LoopbackOrigin((request, exchange) -> LoopbackOrigin.respond(
        exchange,
        request.uri().getPath().equals("/resource") ? 200 : 404,
        "body",
        "Cache-Control", "no-store",
        NetworkDetails.TLS_PROTOCOL, "forged",
        NetworkDetails.TCP_CONNECT_START, FORGED_TCP_START.toString()
    ));

    private final Client<Origin> client = this.origin.client(
        ClientConfig.builder(Origin.class, GsonSettings.builder().build()).build()
    );

    ClientInternalHeadersTest() throws IOException, GeneralSecurityException { }

    @AfterEach
    void stopOrigin() {
        this.origin.close();
    }

    /**
     * Asserts that a request reached the origin without a header named with the internal
     * prefix.
     *
     * @param request the request the origin received
     */
    private static void assertNoInternalHeader(LoopbackOrigin.Received request) {
        List<String> internal = request.headers().keySet().stream().filter(NetworkDetails::isInternalHeader).toList();
        assertThat(request.uri().getPath(), internal, is(empty()));
    }

    /**
     * Asserts that the network details of a response describe the TLS connection the origin
     * received its request over - its TLS protocol and cipher, its TLS handshake, and the
     * connection window before that handshake - and none of the markers the origin sent.
     *
     * @param details the response's network details
     * @param request the request the origin received
     */
    private static void assertConnectionOf(NetworkDetails details, LoopbackOrigin.Received request) {
        assertThat(details.getTlsProtocol().orElseThrow(), is(request.tlsProtocol()));
        assertThat(details.getTlsCipher().orElseThrow(), is(request.tlsCipher()));
        assertThat(details.getTlsHandshake().startedAt(), is(not(Instant.EPOCH)));
        assertThat(details.getTlsHandshake().completedAt().isBefore(details.getTlsHandshake().startedAt()), is(false));
        assertThat(details.getTcpConnection().startedAt(), is(not(FORGED_TCP_START)));
        assertThat(details.getTcpConnection().startedAt(), is(not(Instant.EPOCH)));
        assertThat(details.getTcpConnection().completedAt().isBefore(details.getTcpConnection().startedAt()), is(false));
        assertThat(details.getTcpConnection().completedAt().isAfter(details.getTlsHandshake().startedAt()), is(false));
    }

    @Test
    @DisplayName("No internal header reaches the origin, and a response reports the TLS connection it came over rather than the origin's markers")
    void responseReportsItsConnection() {
        Response<byte[]> response = this.client.getContract().resource();

        assertThat(this.origin.received(), hasSize(1));
        LoopbackOrigin.Received request = this.origin.received().getFirst();
        assertNoInternalHeader(request);
        assertConnectionOf(response.getDetails(), request);
    }

    @Test
    @DisplayName("An error status raises an exception reporting the TLS connection it came over rather than the origin's markers")
    void exceptionReportsItsConnection() {
        ApiException raised = assertThrows(ApiException.class, () -> this.client.getContract().missing());

        assertThat(raised.getStatusCode(), is(404));
        assertThat(this.origin.received(), hasSize(1));
        LoopbackOrigin.Received request = this.origin.received().getFirst();
        assertNoInternalHeader(request);
        assertConnectionOf(raised.getDetails(), request);
    }

    @Test
    @DisplayName("A header a caller names with the internal prefix reaches the origin, while none the client writes itself does")
    void callersInternalHeadersReachTheOrigin() {
        Client<Origin> configured = this.origin.client(
            ClientConfig.builder(Origin.class, GsonSettings.builder().build())
                .withHeader("X-Internal-Tenant", "t1")
                .build()
        );

        configured.getContract().traced();

        assertThat(this.origin.received(), hasSize(1));
        LoopbackOrigin.Received request = this.origin.received().getFirst();
        assertThat(request.header("X-Internal-Tenant"), contains("t1"));
        assertThat(request.header("X-Internal-Trace"), contains("c1"));
        assertThat(request.headers().keySet().stream().filter(NetworkDetails::isClientHeader).toList(), is(empty()));
    }

}
