package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.Response;
import feign.Feign;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;
import feign.codec.Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests an {@link InternalResponseDecoder} as a Feign builder outside {@link Client} builds one:
 * a proxy over a scripted transport that answers every request with the status a test sets.
 */
class InternalResponseDecoderTest {

    interface Resource {

        @RequestLine("GET /resource")
        String get();

        @RequestLine("PUT /resource")
        void put();

        @RequestLine("GET /resource")
        InputStream stream();

    }

    private static final String BODY = "answered";

    /**
     * The status every request is answered with.
     */
    private final AtomicInteger status = new AtomicInteger(200);

    private final ResponseCache cache = new ResponseCache(1L << 20, 3_600_000L);

    private final Resource resource = Feign.builder()
        .client(this::answer)
        .decoder(new InternalResponseDecoder(new Decoder.Default(), this.cache))
        .retryer(Retryer.NEVER_RETRY)
        .doNotCloseAfterDecode()
        .decodeVoid()
        .target(Resource.class, "https://127.0.0.1:0");

    private feign.Response answer(Request request, Request.Options options) {
        return feign.Response.builder()
            .status(this.status.get())
            .reason("")
            .request(request)
            .headers(Map.of())
            .body(BODY.getBytes(StandardCharsets.UTF_8))
            .build();
    }

    private Response<?> lastResponse() {
        return this.cache.getLastResponse().orElseThrow();
    }

    @Test
    @DisplayName("A 2xx HttpStatus names is decoded")
    void decodesAKnownSuccess() {
        assertThat(this.resource.get(), is(equalTo(BODY)));
    }

    @Test
    @DisplayName("A 2xx HttpStatus has no constant for is decoded as a 200")
    void decodesAnUnknownSuccessAsOk() {
        this.status.set(218);

        assertThat(this.resource.get(), is(equalTo(BODY)));
        assertThat(this.lastResponse().getStatus(), is(HttpStatus.OK));
    }

    @Test
    @DisplayName("A void method records its exchange as the last response")
    void voidMethodRecordsItsExchange() {
        this.resource.get();
        this.status.set(204);
        this.resource.put();

        assertThat(this.lastResponse().getStatus(), is(HttpStatus.NO_CONTENT));
        assertThat(this.lastResponse().getRequest().getMethod(), is(HttpMethod.PUT));
    }

    @Test
    @DisplayName("A void method answered with a 2xx HttpStatus has no constant for returns and records a 200")
    void voidMethodReturnsForAnUnknownSuccess() {
        this.status.set(218);
        this.resource.put();

        assertThat(this.lastResponse().getStatus(), is(HttpStatus.OK));
    }

    @Test
    @DisplayName("A bare InputStream method records its exchange as the last response, around the stream it returns")
    void streamMethodRecordsItsExchange() throws IOException {
        this.resource.get();
        this.status.set(203);

        try (InputStream stream = this.resource.stream()) {
            Response<?> last = this.lastResponse();

            assertThat(last, is(instanceOf(Response.StreamingImpl.class)));
            assertThat(last.getStatus(), is(HttpStatus.NON_AUTHORITATIVE_INFORMATION));
            assertThat(last.getBody(), is(sameInstance(stream)));
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8), is(equalTo(BODY)));
        }
    }

}
