package dev.simplified.client.cache;

import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import feign.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class CachingFeignClientTest {

    private static final String URL = "https://127.0.0.1:0/resource";

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    private static Map<String, Collection<String>> headers(String... pairs) {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (int i = 0; i < pairs.length; i += 2)
            headers.put(pairs[i], List.of(pairs[i + 1]));

        return headers;
    }

    private static Request request(String... headerPairs) {
        return Request.create(Request.HttpMethod.GET, URL, headers(headerPairs), null, StandardCharsets.UTF_8, null);
    }

    /**
     * A cached {@code 200} for the test resource, built directly rather than through
     * {@link ResponseCache#store} so the client can be handed the entry it would have looked up.
     */
    private static CacheEntry<byte[]> entry(Request request, String... headerPairs) {
        feign.Response live = feign.Response.builder()
            .status(200)
            .reason("OK")
            .request(request)
            .headers(headers(headerPairs))
            .body(BODY)
            .build();

        return new CacheEntry<>(Response.CachedImpl.from(new Response.Impl<>(live, () -> BODY)), BODY);
    }

    /**
     * Serves the entry through a client whose origin answers every conditional request
     * {@code 304 Not Modified} with the given headers.
     */
    private static feign.Response serve(CacheEntry<?> entry, String... notModifiedPairs) throws IOException {
        CachingFeignClient client = new CachingFeignClient(
            (request, options) -> feign.Response.builder()
                .status(304)
                .reason("Not Modified")
                .request(request)
                .headers(headers(notModifiedPairs))
                .build(),
            new ResponseCache(1L << 20, 3_600_000L)
        );

        return client.serveFromCache(request(), new Request.Options(), HttpMethod.GET, entry);
    }

    @Test
    @DisplayName("A 304 replay carries the 304's headers over the stored ones and names them")
    void notModifiedReplayCarriesTheLiveHeaders() throws IOException {
        feign.Response replay = serve(
            entry(request(), "ETag", "\"v1\"", "x-ratelimit-limit", "60", "x-ratelimit-remaining", "40"),
            "ETag", "\"v1\"",
            "x-ratelimit-remaining", "59"
        );

        assertThat(replay.status(), is(200));
        assertThat(replay.headers().get(ResponseCache.CACHE_HIT_HEADER), contains("true"));
        assertThat(replay.headers().get("x-ratelimit-remaining"), contains("59"));
        assertThat(replay.headers().get("x-ratelimit-limit"), contains("60"));
        assertThat(replay.headers().get(ResponseCache.REVALIDATED_HEADER), containsInAnyOrder(
            equalToIgnoringCase("ETag"),
            equalToIgnoringCase("x-ratelimit-remaining")
        ));
    }

    @Test
    @DisplayName("A 304 replay names none of the 304's hop-by-hop or framing headers")
    void notModifiedReplayNamesOnlyRefreshedHeaders() throws IOException {
        feign.Response replay = serve(
            entry(request(), "ETag", "\"v1\""),
            "Connection", "keep-alive",
            "Content-Length", "0",
            "x-ratelimit-remaining", "59"
        );

        assertThat(replay.headers().get(ResponseCache.REVALIDATED_HEADER), contains(equalToIgnoringCase("x-ratelimit-remaining")));
    }

    @Test
    @DisplayName("A fresh hit carries no revalidation marker")
    void freshHitIsNotMarkedRevalidated() throws IOException {
        String now = Instant.now().toString();
        Request sent = request(NetworkDetails.REQUEST_START, now);

        feign.Response replay = serve(entry(
            sent,
            "Cache-Control", "max-age=3600",
            NetworkDetails.RESPONSE_RECEIVED, now,
            "x-ratelimit-remaining", "40"
        ));

        assertThat(replay.headers().get(ResponseCache.CACHE_HIT_HEADER), contains("true"));
        assertThat(replay.headers().get(ResponseCache.REVALIDATED_HEADER), is(nullValue()));
    }

}
