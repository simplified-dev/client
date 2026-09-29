package dev.simplified.client;

import dev.simplified.client.exception.ApiDecodeException;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import dev.simplified.gson.GsonSettings;
import feign.Request;
import feign.RequestLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a {@link Client} never replays a cached {@code 2xx} whose body did not decode: a
 * {@link Client} built over a scripted transport that answers every {@code GET} with the body a
 * test sets, fresh for a minute.
 */
class ClientUndecodableBodyTest {

    @Route(value = "127.0.0.1:0", rateLimit = @RateLimitConfig(unlimited = true))
    interface Resource extends Contract {

        @RequestLine("GET /resource")
        Thing typed();

        @RequestLine("GET /resource")
        Response<Thing> wrapped();

    }

    /**
     * The body the resource is read into.
     */
    static final class Thing {

        private String name = "";

    }

    /**
     * A body that does not decode into a {@link Thing}, which is an object.
     */
    private static final String UNDECODABLE = "[]";

    private static final String DECODABLE = "{\"name\":\"decoded\"}";

    /**
     * The body every {@code GET} is answered with.
     */
    private final AtomicReference<String> body = new AtomicReference<>(UNDECODABLE);

    /**
     * The {@code GET} requests answered.
     */
    private final AtomicInteger answered = new AtomicInteger();

    private final Client<Resource> client = new Client<>(
        ClientConfig.builder(Resource.class, GsonSettings.builder().build()).build(),
        this::answer
    );

    /**
     * Answers a {@code GET} with the scripted body, fresh for a minute; answers anything else, the
     * client's connection warm-up, with an empty {@code 200}.
     *
     * @param request the request
     * @param options the request options
     * @return the answer
     */
    private feign.Response answer(Request request, Request.Options options) {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byte[] answer = new byte[0];

        if (request.httpMethod() == Request.HttpMethod.GET) {
            this.answered.incrementAndGet();
            headers.put("Cache-Control", List.of("max-age=60"));
            answer = this.body.get().getBytes(StandardCharsets.UTF_8);
        }

        return feign.Response.builder()
            .status(200)
            .reason("OK")
            .request(request)
            .headers(headers)
            .body(answer)
            .build();
    }

    @Test
    @DisplayName("A body a contract method decodes eagerly that fails to decode is not replayed, so the next call reaches the origin")
    void eagerDecodeFailureIsNotReplayed() {
        Resource resource = this.client.getContract();

        assertThrows(ApiDecodeException.class, resource::typed);
        this.body.set(DECODABLE);
        Thing next = resource.typed();

        assertThat(next.name, is(equalTo("decoded")));
        assertThat(this.answered.get(), is(2));
    }

    @Test
    @DisplayName("A deferred body that fails to decode is discarded from the cache, so the next call reaches the origin")
    void deferredDecodeFailureIsDiscarded() {
        Resource resource = this.client.getContract();

        Response<Thing> live = resource.wrapped();
        assertThrows(ApiDecodeException.class, live::getBody);
        this.body.set(DECODABLE);
        Response<Thing> next = resource.wrapped();

        assertThat(next.isFromCache(), is(false));
        assertThat(next.getBody().name, is(equalTo("decoded")));
        assertThat(this.answered.get(), is(2));
    }

    @Test
    @DisplayName("A replay whose body fails to decode is discarded on that failure, and replays before it are still served to callers that read no body")
    void replayDecodeFailureIsDiscarded() {
        Resource resource = this.client.getContract();

        resource.wrapped();
        Response<Thing> replay = resource.wrapped();
        assertThrows(ApiDecodeException.class, replay::getBody);
        this.body.set(DECODABLE);
        Response<Thing> next = resource.wrapped();

        assertThat(replay.isFromCache(), is(true));
        assertThat(next.isFromCache(), is(false));
        assertThat(next.getBody().name, is(equalTo("decoded")));
        assertThat(this.answered.get(), is(2));
    }

    @Test
    @DisplayName("A body that decodes is stored and replayed, whether decoded eagerly or deferred")
    void decodableBodyIsReplayed() {
        this.body.set(DECODABLE);
        Resource resource = this.client.getContract();

        Thing live = resource.typed();
        Thing replayed = resource.typed();
        Response<Thing> wrapped = resource.wrapped();

        assertThat(live.name, is(equalTo("decoded")));
        assertThat(replayed.name, is(equalTo("decoded")));
        assertThat(wrapped.isFromCache(), is(true));
        assertThat(wrapped.getBody().name, is(equalTo("decoded")));
        assertThat(this.answered.get(), is(1));
    }

}
