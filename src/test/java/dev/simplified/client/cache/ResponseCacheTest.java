package dev.simplified.client.cache;

import dev.simplified.client.decoder.InternalResponseDecoder;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.ETag;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import feign.Feign;
import feign.Request;
import feign.RequestLine;
import feign.codec.Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWithIgnoringCase;

/**
 * Pipeline tests of {@link ResponseCache}: a Feign proxy whose transport is a
 * {@link CachingFeignClient} over a scripted origin, decoding through the
 * {@link InternalResponseDecoder} that stores each answer.
 */
class ResponseCacheTest {

    interface Resource {

        @RequestLine("GET /resource")
        Response<byte[]> get();

    }

    private static final String URL = "https://127.0.0.1:0/resource";

    private static final byte[] BODY = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);

    /**
     * The time Caffeine measures bucket lifetimes against, advanced by hand.
     */
    private final AtomicLong ticks = new AtomicLong();

    private final ResponseCache cache = new ResponseCache(1L << 20, 3_600_000L, this.ticks::get);

    /**
     * Every request that reached the origin, in the order it was sent.
     */
    private final List<Request> sent = new ArrayList<>();

    /**
     * How the origin answers the next request.
     */
    private Function<Request, feign.Response> origin = request -> answer(request, 200, "Cache-Control", "max-age=60");

    private final Resource resource = Feign.builder()
        .client(new CachingFeignClient(
            (request, options) -> {
                this.sent.add(request);
                return this.origin.apply(request);
            },
            this.cache
        ))
        .decoder(new InternalResponseDecoder(new Decoder.Default(), this.cache))
        .target(Resource.class, "https://127.0.0.1:0");

    private static Map<String, Collection<String>> headers(String... pairs) {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (int i = 0; i < pairs.length; i += 2)
            headers.put(pairs[i], List.of(pairs[i + 1]));

        return headers;
    }

    /**
     * Builds an answer carrying no round-trip markers, as the Apache transport returns one.
     *
     * @param request the request the answer responds to
     * @param status the answer's status code
     * @param headerPairs the answer's headers, as alternating names and values
     * @return the answer
     */
    private static feign.Response answer(Request request, int status, String... headerPairs) {
        return feign.Response.builder()
            .status(status)
            .reason(status == 304 ? "Not Modified" : "OK")
            .request(request)
            .headers(headers(headerPairs))
            .body(status == 304 ? new byte[0] : BODY)
            .build();
    }

    private Optional<CacheEntry<?>> lookup() {
        return this.cache.lookup(HttpMethod.GET, URL, Map.of());
    }

    /**
     * Offers an answer to a {@code GET} of the test resource straight to the cache, as the
     * decoder offers a live one.
     *
     * @param headerPairs the answer's headers, as alternating names and values
     */
    private void storeDirectly(String... headerPairs) {
        Request request = Request.create(Request.HttpMethod.GET, URL, headers(), null, StandardCharsets.UTF_8, null);
        this.cache.store(new Response.Impl<>(answer(request, 200, headerPairs), () -> BODY), BODY);
    }

    @Test
    @DisplayName("A stored response is looked up and replayed as a fresh hit without reaching the origin")
    void storedResponseIsReplayedFresh() {
        Response<byte[]> live = this.resource.get();

        assertThat(live.isFromCache(), is(false));
        assertThat(this.lookup().isPresent(), is(true));

        Response<byte[]> replay = this.resource.get();

        assertThat(this.sent, hasSize(1));
        assertThat(replay.isFromCache(), is(true));
        assertThat(replay.getBody(), is(BODY));
    }

    @Test
    @DisplayName("A 304 revalidation keeps the entry and refreshes it, restarting its bucket's lifetime")
    void notModifiedRefreshesTheEntry() {
        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=60", "Age", "120", "ETag", "\"v1\"");
        this.resource.get();

        this.origin = request -> answer(request, 304, "Cache-Control", "max-age=600", "ETag", "\"v1\"");
        Response<byte[]> revalidated = this.resource.get();

        assertThat(this.sent, hasSize(2));
        assertThat(this.sent.getLast().headers().get(ETag.IF_NONE_MATCH_HEADER), contains("\"v1\""));
        assertThat(revalidated.isFromCache(), is(true));
        assertThat(revalidated.getBody(), is(BODY));

        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=600");
        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(120));
        Response<byte[]> replay = this.resource.get();

        assertThat(this.sent, hasSize(2));
        assertThat(replay.isFromCache(), is(true));
        assertThat(this.lookup().orElseThrow().response().getHeaders().get("Cache-Control"), contains("max-age=600"));
    }

    @Test
    @DisplayName("A 304 refresh is aged from the revalidation, so the request after it is a fresh hit")
    void refreshedEntryIsAgedFromTheRevalidation() {
        String past = Instant.now().minusSeconds(100).toString();
        this.storeDirectly(
            "Cache-Control", "max-age=60",
            "Age", "90",
            "ETag", "\"v1\"",
            NetworkDetails.REQUEST_START, past,
            NetworkDetails.RESPONSE_RECEIVED, past
        );

        this.origin = request -> answer(request, 304, "Cache-Control", "max-age=60", "ETag", "\"v1\"");
        Response<byte[]> revalidated = this.resource.get();

        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=60");
        Response<byte[]> replay = this.resource.get();

        assertThat(this.sent, hasSize(1));
        assertThat(this.sent.getFirst().headers().get(ETag.IF_NONE_MATCH_HEADER), contains("\"v1\""));
        assertThat(revalidated.isFromCache(), is(true));
        assertThat(replay.isFromCache(), is(true));
        assertThat(this.lookup().orElseThrow().response().getHeaders().containsKey("Age"), is(false));
    }

    @Test
    @DisplayName("A 304 to a request sent before invalidateAll does not refresh the entry stored after it")
    void revalidationInFlightAcrossTheDropIsRefused() {
        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=60", "Age", "120", "ETag", "\"v1\"");
        this.resource.get();

        this.origin = request -> {
            this.cache.invalidateAll();
            Instant dropped = Instant.now();

            // a request stamped in the same clock tick as the drop counts as sent before it
            while (!Instant.now().isAfter(dropped))
                Thread.onSpinWait();

            String now = Instant.now().toString();
            this.storeDirectly(
                "Cache-Control", "max-age=60",
                "ETag", "\"v2\"",
                NetworkDetails.REQUEST_START, now,
                NetworkDetails.RESPONSE_RECEIVED, now
            );
            return answer(request, 304, "Cache-Control", "max-age=600", "ETag", "\"v1\"");
        };
        Response<byte[]> revalidated = this.resource.get();
        Response.CachedImpl<?> stored = this.lookup().orElseThrow().response();

        assertThat(revalidated.isFromCache(), is(true));
        assertThat(stored.getHeaders().get("ETag"), contains("\"v2\""));
        assertThat(stored.getHeaders().get("Cache-Control"), contains("max-age=60"));
    }

    @Test
    @DisplayName("An origin's X-Internal- headers neither fail the call nor stand in for its round trip")
    void originInternalHeadersAreIgnored() {
        Instant future = Instant.now().plusSeconds(3_600);
        this.origin = request -> {
            this.cache.invalidateAll();
            return answer(
                request,
                200,
                "Cache-Control", "max-age=60",
                NetworkDetails.REQUEST_START, future.toString(),
                "x-internal-response-received", "not-an-instant"
            );
        };
        Response<byte[]> live = this.resource.get();

        assertThat(live.getDetails().getRoundTrip().startedAt().isBefore(future), is(true));
        assertThat(this.lookup().isPresent(), is(false));
    }

    @Test
    @DisplayName("invalidateAll drops a stored entry")
    void invalidateAllDropsAStoredEntry() {
        this.resource.get();

        assertThat(this.resource.get().isFromCache(), is(true));

        this.cache.invalidateAll();

        assertThat(this.lookup().isPresent(), is(false));
        assertThat(this.resource.get().isFromCache(), is(false));
        assertThat(this.sent, hasSize(2));
    }

    @Test
    @DisplayName("An answer to a request sent before invalidateAll is not stored, and one sent after it is")
    void answerInFlightAcrossTheDropIsRefused() {
        AtomicReference<Instant> dropped = new AtomicReference<>();
        this.origin = request -> {
            this.cache.invalidateAll();
            dropped.set(Instant.now());
            return answer(request, 200, "Cache-Control", "max-age=60");
        };
        this.resource.get();

        assertThat(this.lookup().isPresent(), is(false));

        // a request stamped in the same clock tick as the drop counts as sent before it
        while (!Instant.now().isAfter(dropped.get()))
            Thread.onSpinWait();

        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=60");
        this.resource.get();
        Response<byte[]> replay = this.resource.get();

        assertThat(this.sent, hasSize(2));
        assertThat(replay.isFromCache(), is(true));
    }

    @Test
    @DisplayName("A response with no freshness and no validator is not retained, where one with freshness is")
    void responseWithoutFreshnessIsNotRetained() {
        this.origin = request -> answer(request, 200);
        this.resource.get();

        assertThat(this.lookup().isPresent(), is(false));
        assertThat(this.resource.get().isFromCache(), is(false));

        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=60");
        this.resource.get();

        assertThat(this.resource.get().isFromCache(), is(true));
        assertThat(this.sent, hasSize(3));
    }

    @Test
    @DisplayName("s-maxage does not lengthen a response's freshness in this private cache")
    void sharedMaxAgeIsIgnored() {
        this.origin = request -> answer(request, 200, "Cache-Control", "public, max-age=60, s-maxage=300", "Age", "90");
        this.resource.get();

        assertThat(this.resource.get().isFromCache(), is(false));
        assertThat(this.sent, hasSize(2));
    }

    @Test
    @DisplayName("A response carrying no request start is not stored, where one carrying it is")
    void responseWithoutRequestStartIsNotStored() {
        Request request = Request.create(Request.HttpMethod.GET, URL, headers(), null, StandardCharsets.UTF_8, null);
        String now = Instant.now().toString();

        feign.Response unstamped = answer(request, 200, "Cache-Control", "max-age=60", NetworkDetails.RESPONSE_RECEIVED, now);
        this.cache.store(new Response.Impl<>(unstamped, () -> BODY), BODY);

        assertThat(this.lookup().isPresent(), is(false));

        feign.Response stamped = answer(
            request,
            200,
            "Cache-Control", "max-age=60",
            NetworkDetails.REQUEST_START, now,
            NetworkDetails.RESPONSE_RECEIVED, now
        );
        this.cache.store(new Response.Impl<>(stamped, () -> BODY), BODY);

        assertThat(this.lookup().isPresent(), is(true));
    }

    @Test
    @DisplayName("A live answer and a replay both carry their round trip through Feign, and neither shows it publicly")
    void answersCarryTheirRoundTrip() {
        Response<byte[]> live = this.resource.get();
        Response<byte[]> replay = this.resource.get();

        assertThat(replay.isFromCache(), is(true));
        assertThat(live.getDetails().getRoundTrip().startedAt(), is(not(Instant.EPOCH)));
        assertThat(live.getDetails().getRoundTrip().completedAt(), is(not(Instant.EPOCH)));
        assertThat(replay.getDetails().getRoundTrip().startedAt(), is(not(Instant.EPOCH)));
        assertThat(replay.getDetails().getRoundTrip().durationNanos(), is(0L));
        assertThat(live.getHeaders().keySet(), everyItem(not(startsWithIgnoringCase(NetworkDetails.INTERNAL_HEADER_PREFIX))));
        assertThat(replay.getHeaders().keySet(), everyItem(not(startsWithIgnoringCase(NetworkDetails.INTERNAL_HEADER_PREFIX))));
    }

}
