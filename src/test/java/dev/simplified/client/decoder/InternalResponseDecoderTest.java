package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.cache.ResponseCache;
import feign.Feign;
import feign.FeignException;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;
import feign.codec.Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests an {@link InternalResponseDecoder} built without an error decoder, as a Feign builder
 * outside {@link Client} builds one: a proxy over a scripted transport
 * that answers every request with the status a test sets.
 */
class InternalResponseDecoderTest {

    interface Resource {

        @RequestLine("GET /resource")
        String get();

    }

    private static final String BODY = "answered";

    /**
     * The status every request is answered with.
     */
    private final AtomicInteger status = new AtomicInteger(200);

    private final Resource resource = Feign.builder()
        .client(this::answer)
        .decoder(new InternalResponseDecoder(new Decoder.Default(), new ResponseCache(1L << 20, 3_600_000L)))
        .retryer(Retryer.NEVER_RETRY)
        .doNotCloseAfterDecode()
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

    @Test
    @DisplayName("A decoder built without an error decoder decodes a 2xx HttpStatus names")
    void decodesAKnownSuccess() {
        assertThat(this.resource.get(), is(equalTo(BODY)));
    }

    @Test
    @DisplayName("A decoder built without an error decoder raises a 2xx HttpStatus has no constant for as Feign's default error decoder does")
    void raisesAnUnknownSuccessAsFeignDoes() {
        this.status.set(218);

        FeignException raised = assertThrows(FeignException.class, this.resource::get);

        assertThat(raised.status(), is(218));
        assertThat(raised.contentUTF8(), is(equalTo(BODY)));
    }

}
