package dev.simplified.client;

import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.exception.NotModifiedException;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import dev.simplified.gson.GsonSettings;
import feign.Request;
import feign.RequestLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a {@link Client} raises a status code {@link HttpStatus} has no constant for through
 * its error decoder, carrying the code as {@link ApiException#getStatusCode()}: a {@link Client}
 * built over a scripted transport that answers every {@code GET} with the status a test sets.
 */
class ClientUnknownStatusTest {

    @Route(value = "127.0.0.1:0", rateLimit = @RateLimitConfig(unlimited = true))
    interface Resource extends Contract {

        @RequestLine("GET /resource")
        Response<byte[]> raw();

        @RequestLine("GET /resource")
        Thing typed();

        @RequestLine("GET /resource")
        InputStream stream();

    }

    /**
     * The body the resource is read into by {@link Resource#typed()}.
     */
    static final class Thing {

        private String name = "";

    }

    /**
     * The exception the client's error decoder raises.
     */
    static final class OriginException extends ApiException {

        OriginException(ErrorContext context) {
            super(null, "Origin", context);
        }

    }

    private static final String BODY = "{\"name\":\"refused\"}";

    /**
     * The status every {@code GET} is answered with.
     */
    private final AtomicInteger status = new AtomicInteger(200);

    private final Client<Resource> client = new Client<>(
        ClientConfig.builder(Resource.class, GsonSettings.builder().build())
            .withErrorDecoder(OriginException::new)
            .build(),
        this::answer
    );

    /**
     * Answers a {@code GET} with the scripted status and a JSON body; answers anything else, the
     * client's connection warm-up, with an empty {@code 200}.
     *
     * @param request the request
     * @param options the request options
     * @return the answer
     */
    private feign.Response answer(Request request, Request.Options options) {
        boolean scripted = request.httpMethod() == Request.HttpMethod.GET;

        return feign.Response.builder()
            .status(scripted ? this.status.get() : 200)
            .reason("")
            .request(request)
            .headers(Map.of())
            .body(scripted ? BODY.getBytes(StandardCharsets.UTF_8) : new byte[0])
            .build();
    }

    private OriginException raise(int code, Executable call) {
        this.status.set(code);
        return assertThrows(OriginException.class, call);
    }

    @Test
    @DisplayName("A 4xx HttpStatus has no constant for reaches the error decoder carrying the code, UNKNOWN_ERROR and the body")
    void unknownClientErrorReachesTheErrorDecoder() {
        OriginException raised = this.raise(460, () -> this.client.getContract().raw());

        assertThat(raised.getStatusCode(), is(460));
        assertThat(raised.getStatus(), is(HttpStatus.UNKNOWN_ERROR));
        assertThat(new String(raised.getBody().orElseThrow(), StandardCharsets.UTF_8), is(equalTo(BODY)));
        assertThat(raised.getMessage(), endsWith("/resource failed with unknown status 460"));
    }

    @Test
    @DisplayName("A code in the Nginx range HttpStatus has no constant for reaches the error decoder carrying the code")
    void unknownNginxCodeReachesTheErrorDecoder() {
        OriginException raised = this.raise(498, () -> this.client.getContract().raw());

        assertThat(raised.getStatusCode(), is(498));
        assertThat(raised.getStatus(), is(HttpStatus.UNKNOWN_ERROR));
    }

    @Test
    @DisplayName("A 5xx HttpStatus has no constant for reaches the error decoder carrying the code")
    void unknownServerErrorReachesTheErrorDecoder() {
        OriginException raised = this.raise(561, () -> this.client.getContract().raw());

        assertThat(raised.getStatusCode(), is(561));
        assertThat(raised.getStatus(), is(HttpStatus.UNKNOWN_ERROR));
    }

    @Test
    @DisplayName("A 3xx HttpStatus has no constant for raises NotModifiedException as the 3xx constants do")
    void unknownRedirectionRaisesNotModified() {
        this.status.set(399);
        NotModifiedException raised = assertThrows(NotModifiedException.class, () -> this.client.getContract().raw());

        assertThat(raised.getStatusCode(), is(399));
        assertThat(raised.getStatus(), is(HttpStatus.UNKNOWN_ERROR));
    }

    @Test
    @DisplayName("The exception raised for a status HttpStatus has no constant for is the client's last response")
    void unknownStatusIsTheLastResponse() {
        OriginException raised = this.raise(460, () -> this.client.getContract().raw());
        Optional<Response<?>> last = this.client.getLastResponse();

        assertThat(last.orElseThrow(), is(sameInstance(raised)));
    }

    @Test
    @DisplayName("A status HttpStatus names reaches the error decoder as its constant and code")
    void knownStatusReachesTheErrorDecoder() {
        OriginException raised = this.raise(404, () -> this.client.getContract().raw());

        assertThat(raised.getStatusCode(), is(404));
        assertThat(raised.getStatus(), is(HttpStatus.NOT_FOUND));
        assertThat(raised.getMessage(), endsWith("/resource failed with status 404 Not Found"));
    }

    @Test
    @DisplayName("A status HttpStatus names in the 2xx class is decoded")
    void knownSuccessIsDecoded() {
        this.status.set(200);

        assertThat(this.client.getContract().typed().name, is(equalTo("refused")));
    }

}
