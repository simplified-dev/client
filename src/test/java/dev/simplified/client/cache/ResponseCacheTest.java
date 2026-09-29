package dev.simplified.client.cache;

import dev.simplified.client.decoder.InternalResponseDecoder;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.ETag;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import feign.Feign;
import feign.Headers;
import feign.Param;
import feign.Request;
import feign.RequestLine;
import feign.codec.Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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

        @RequestLine("PUT /resource")
        Response<byte[]> put();

    }

    /**
     * The test resource, read with a request header the caller chooses.
     */
    interface Negotiated {

        @RequestLine("GET /resource")
        @Headers("Accept: {accept}")
        Response<byte[]> accept(@Param("accept") String accept);

        @RequestLine("GET /resource")
        @Headers("Cookie: {cookie}")
        Response<byte[]> cookie(@Param("cookie") String cookie);

    }

    private static final String URL = "https://127.0.0.1:0/resource";

    private static final byte[] BODY = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);

    /**
     * Formats an instant as the IMF-fixdate an origin sends in {@code Date}.
     */
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC);

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

    private final Resource resource = this.proxy(Resource.class);

    private final Negotiated negotiated = this.proxy(Negotiated.class);

    /**
     * Builds a Feign proxy whose transport is a {@link CachingFeignClient} over the scripted
     * origin, decoding through the {@link InternalResponseDecoder} that stores each answer.
     *
     * @param contract the contract the proxy implements
     * @param <T> the contract type
     * @return the proxy
     */
    private <T> T proxy(Class<T> contract) {
        return Feign.builder()
            .client(new CachingFeignClient(
                (request, options) -> {
                    this.sent.add(request);
                    return this.origin.apply(request);
                },
                this.cache
            ))
            .decoder(new InternalResponseDecoder(new Decoder.Default(), this.cache))
            .target(contract, "https://127.0.0.1:0");
    }

    /**
     * Answers a conditional request {@code 304 Not Modified} and any other {@code 200}, both
     * carrying the given headers.
     *
     * @param headerPairs the answer's headers, as alternating names and values
     * @return the origin
     */
    private static Function<Request, feign.Response> revalidating(String... headerPairs) {
        return request -> answer(request, header(request, ETag.IF_NONE_MATCH_HEADER).isEmpty() ? 200 : 304, headerPairs);
    }

    /**
     * Returns the given headers followed by a request start and a response received both set to
     * now, as a live answer carries them.
     *
     * @param headerPairs the headers, as alternating names and values
     * @return the headers with the round trip added
     */
    private static String[] receivedNow(String... headerPairs) {
        return receivedAt(Instant.now(), headerPairs);
    }

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
        return answer(request, status, status == 304 ? new byte[0] : BODY, headerPairs);
    }

    /**
     * Builds an answer with the given body, carrying no round-trip markers.
     *
     * @param request the request the answer responds to
     * @param status the answer's status code
     * @param body the answer's body
     * @param headerPairs the answer's headers, as alternating names and values
     * @return the answer
     */
    private static feign.Response answer(Request request, int status, byte[] body, String... headerPairs) {
        return feign.Response.builder()
            .status(status)
            .reason(status == 304 ? "Not Modified" : "OK")
            .request(request)
            .headers(headers(headerPairs))
            .body(body)
            .build();
    }

    /**
     * Reads the first value of a request header, ignoring the name's case.
     *
     * @param request the request
     * @param name the header name
     * @return the header's first value, or an empty string when the request carries none
     */
    private static String header(Request request, String name) {
        return request.headers().entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .flatMap(entry -> entry.getValue().stream())
            .findFirst()
            .orElse("");
    }

    /**
     * Answers each request with its own {@code Accept} value as the body, stale on arrival under
     * a validator naming that value, and a conditional request carrying that validator with a
     * {@code 304} that makes it fresh for ten minutes.
     *
     * @param request the request
     * @return the answer
     */
    private static feign.Response negotiatedAnswer(Request request) {
        String accept = header(request, "Accept");
        String etag = "\"" + accept + "\"";

        if (etag.equals(header(request, ETag.IF_NONE_MATCH_HEADER)))
            return answer(request, 304, "Vary", "Accept", "Cache-Control", "max-age=600", "ETag", etag);

        return answer(
            request,
            200,
            accept.getBytes(StandardCharsets.UTF_8),
            "Vary", "Accept",
            "Cache-Control", "max-age=60",
            "Age", "120",
            "ETag", etag
        );
    }

    private Optional<CacheEntry<?>> lookup() {
        return lookup(this.cache);
    }

    private static Optional<CacheEntry<?>> lookup(ResponseCache cache) {
        return cache.lookup(HttpMethod.GET, URL, Map.of());
    }

    /**
     * Offers an answer to a {@code GET} of the test resource straight to the cache, as the
     * decoder offers a live one.
     *
     * @param headerPairs the answer's headers, as alternating names and values
     */
    private void storeDirectly(String... headerPairs) {
        storeDirectly(this.cache, headerPairs);
    }

    /**
     * Offers an answer to a {@code GET} of the test resource straight to the given cache, as the
     * decoder offers a live one.
     *
     * @param cache the cache to offer the answer to
     * @param headerPairs the answer's headers, as alternating names and values
     */
    private static void storeDirectly(ResponseCache cache, String... headerPairs) {
        storeDirectly(cache, headers(), headerPairs);
    }

    /**
     * Offers an answer to a {@code GET} of the test resource carrying the given request headers
     * straight to the given cache, as the decoder offers a live one.
     *
     * @param cache the cache to offer the answer to
     * @param requestHeaders the headers of the request the answer responds to
     * @param headerPairs the answer's headers, as alternating names and values
     */
    private static void storeDirectly(ResponseCache cache, Map<String, Collection<String>> requestHeaders, String... headerPairs) {
        Request request = Request.create(Request.HttpMethod.GET, URL, requestHeaders, null, StandardCharsets.UTF_8, null);
        cache.store(new Response.Impl<>(answer(request, 200, headerPairs), () -> BODY), BODY, request.headers());
    }

    /**
     * Returns the given headers followed by a request start and a response received both set to
     * the given instant.
     *
     * @param received the instant the answer was sent for and received
     * @param headerPairs the headers, as alternating names and values
     * @return the headers with the round trip added
     */
    private static String[] receivedAt(Instant received, String... headerPairs) {
        String[] stamped = Arrays.copyOf(headerPairs, headerPairs.length + 4);
        stamped[headerPairs.length] = NetworkDetails.REQUEST_START;
        stamped[headerPairs.length + 1] = received.toString();
        stamped[headerPairs.length + 2] = NetworkDetails.RESPONSE_RECEIVED;
        stamped[headerPairs.length + 3] = received.toString();
        return stamped;
    }

    /**
     * Returns the headers of an answer varying on {@code Accept}, tagged {@code "accept"}, carrying
     * the given further headers and received at the given instant.
     *
     * @param received the instant the answer was received
     * @param headerPairs the answer's further headers, as alternating names and values
     * @return the answer's headers
     */
    private static String[] varyingOnAccept(Instant received, String... headerPairs) {
        return varying("Accept", "\"accept\"", received, headerPairs);
    }

    /**
     * Returns the headers of an answer varying on {@code Accept-Language}, tagged
     * {@code "language"}, carrying the given further headers and received at the given instant.
     *
     * @param received the instant the answer was received
     * @param headerPairs the answer's further headers, as alternating names and values
     * @return the answer's headers
     */
    private static String[] varyingOnLanguage(Instant received, String... headerPairs) {
        return varying("Accept-Language", "\"language\"", received, headerPairs);
    }

    private static String[] varying(String vary, String etag, Instant received, String... headerPairs) {
        String[] fixed = { "Vary", vary, "ETag", etag, "Cache-Control", "max-age=60" };
        String[] joined = Arrays.copyOf(fixed, fixed.length + headerPairs.length);
        System.arraycopy(headerPairs, 0, joined, fixed.length, headerPairs.length);
        return receivedAt(received, joined);
    }

    /**
     * Stores the given answers, in order, in a new cache, each answering one request carrying
     * both {@code Accept} and {@code Accept-Language}, and looks that request up.
     *
     * @param variants the headers of each answer, as alternating names and values
     * @return the {@code ETag} of the variant the lookup answers with
     */
    private Collection<String> answering(String[]... variants) {
        ResponseCache cache = new ResponseCache(1L << 20, 3_600_000L, this.ticks::get);
        Map<String, Collection<String>> request = headers("Accept", "a", "Accept-Language", "en");

        for (String[] variant : variants)
            storeDirectly(cache, request, variant);

        return cache.lookup(HttpMethod.GET, URL, request).orElseThrow().response().getHeaders().get("ETag");
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
    @DisplayName("A GET in flight across a PUT to its URL does not store what it read, and a GET sent after the PUT does")
    void getInFlightAcrossAMutationIsNotStored() {
        AtomicReference<Instant> mutated = new AtomicReference<>();
        this.origin = request -> {
            if (request.httpMethod() == Request.HttpMethod.PUT)
                return answer(request, 200);

            if (mutated.get() == null) {
                this.resource.put();
                mutated.set(Instant.now());
            }

            return answer(request, 200, "Cache-Control", "max-age=60");
        };
        this.resource.get();

        assertThat(this.lookup().isPresent(), is(false));

        // a request stamped in the same clock tick as the invalidation counts as sent before it
        while (!Instant.now().isAfter(mutated.get()))
            Thread.onSpinWait();

        this.resource.get();
        Response<byte[]> replay = this.resource.get();

        assertThat(replay.isFromCache(), is(true));
        assertThat(this.sent, hasSize(3));
        assertThat(this.sent.get(1).httpMethod(), is(Request.HttpMethod.PUT));
    }

    @Test
    @DisplayName("An invalidation of another URL does not refuse an answer in flight across it")
    void invalidationOfAnotherUrlDoesNotRefuse() {
        this.origin = request -> {
            this.cache.invalidate("https://127.0.0.1:0/other");
            return answer(request, 200, "Cache-Control", "max-age=60");
        };
        this.resource.get();

        assertThat(this.lookup().isPresent(), is(true));
    }

    @Test
    @DisplayName("A 304 to a request sent before invalidate(url) does not refresh the entry stored after it")
    void revalidationInFlightAcrossAnInvalidationIsRefused() {
        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=60", "Age", "120", "ETag", "\"v1\"");
        this.resource.get();

        this.origin = request -> {
            this.cache.invalidate(URL);
            Instant invalidated = Instant.now();

            // a request stamped in the same clock tick as the invalidation counts as sent before it
            while (!Instant.now().isAfter(invalidated))
                Thread.onSpinWait();

            this.storeDirectly(receivedNow("Cache-Control", "max-age=60", "ETag", "\"v2\""));
            return answer(request, 304, "Cache-Control", "max-age=600", "ETag", "\"v1\"");
        };
        Response<byte[]> revalidated = this.resource.get();
        Response.CachedImpl<?> stored = this.lookup().orElseThrow().response();

        assertThat(revalidated.isFromCache(), is(true));
        assertThat(stored.getHeaders().get("ETag"), contains("\"v2\""));
        assertThat(stored.getHeaders().get("Cache-Control"), contains("max-age=60"));
    }

    @Test
    @DisplayName("Invalidations past the record limit fold into one instant that still refuses the answers in flight")
    void foldedInvalidationsStillRefuseAnswersInFlight() {
        AtomicReference<Instant> folded = new AtomicReference<>();
        this.origin = request -> {
            this.cache.invalidate(URL);

            for (int i = 0; i < ResponseCache.INVALIDATION_RECORD_LIMIT; i++)
                this.cache.invalidate("https://127.0.0.1:0/other/" + i);

            folded.set(Instant.now());
            return answer(request, 200, "Cache-Control", "max-age=60");
        };
        this.resource.get();

        assertThat(this.lookup().isPresent(), is(false));

        // a request stamped in the same clock tick as the fold counts as sent before it
        while (!Instant.now().isAfter(folded.get()))
            Thread.onSpinWait();

        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=60");
        this.resource.get();

        assertThat(this.resource.get().isFromCache(), is(true));
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
    @DisplayName("A stale entry carrying a validator is still held after its freshness, and a 304 revalidates it")
    void staleEntryWithValidatorIsHeldForRevalidation() {
        this.origin = revalidating("Cache-Control", "max-age=60", "Age", "120", "ETag", "\"v1\"");
        this.resource.get();
        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(120));

        Response<byte[]> revalidated = this.resource.get();

        assertThat(this.sent, hasSize(2));
        assertThat(this.sent.getLast().headers().get(ETag.IF_NONE_MATCH_HEADER), contains("\"v1\""));
        assertThat(revalidated.isFromCache(), is(true));
        assertThat(revalidated.getBody(), is(BODY));
    }

    @Test
    @DisplayName("A response carrying a validator and no freshness is held and revalidated, rather than expired as it is written")
    void validatorWithoutFreshnessIsHeldForRevalidation() {
        this.origin = revalidating("ETag", "\"v1\"");
        this.resource.get();

        assertThat(this.lookup().isPresent(), is(true));

        Response<byte[]> revalidated = this.resource.get();

        assertThat(this.sent, hasSize(2));
        assertThat(this.sent.getLast().headers().get(ETag.IF_NONE_MATCH_HEADER), contains("\"v1\""));
        assertThat(revalidated.isFromCache(), is(true));
    }

    @Test
    @DisplayName("An entry held for revalidation expires once the stale retention has passed after its freshness, and a zero retention holds none")
    void staleRetentionIsBounded() {
        ResponseCache retaining = new ResponseCache(1L << 20, 3_600_000L, TimeUnit.MINUTES.toMillis(10), this.ticks::get);
        ResponseCache retainingNothing = new ResponseCache(1L << 20, 3_600_000L, 0L, this.ticks::get);
        storeDirectly(retaining, receivedNow("Cache-Control", "max-age=60", "ETag", "\"v1\""));
        storeDirectly(retainingNothing, receivedNow("Cache-Control", "max-age=60", "ETag", "\"v1\""));

        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(61));

        assertThat(lookup(retaining).isPresent(), is(true));
        assertThat(lookup(retainingNothing).isPresent(), is(false));

        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(598));

        assertThat(lookup(retaining).isPresent(), is(true));

        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(2));

        assertThat(lookup(retaining).isPresent(), is(false));
    }

    @Test
    @DisplayName("The safety fallback caps the stale retention")
    void safetyFallbackCapsTheStaleRetention() {
        ResponseCache capped = new ResponseCache(1L << 20, TimeUnit.MINUTES.toMillis(5), TimeUnit.HOURS.toMillis(1), this.ticks::get);
        storeDirectly(capped, receivedNow("Cache-Control", "max-age=60", "ETag", "\"v1\""));

        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(299));

        assertThat(lookup(capped).isPresent(), is(true));

        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(2));

        assertThat(lookup(capped).isPresent(), is(false));
    }

    @Test
    @DisplayName("An entry with no validator is held for its freshness and the stale-if-error window it may be served under, and no longer")
    void entryWithoutValidatorIsHeldForItsFreshnessAndStaleIfError() {
        this.storeDirectly(receivedNow("Cache-Control", "max-age=60, stale-if-error=30"));
        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(89));

        assertThat(this.lookup().isPresent(), is(true));

        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(2));

        assertThat(this.lookup().isPresent(), is(false));
    }

    @Test
    @DisplayName("A stale-if-error window a directive forbids holds no entry, and no-cache without a validator holds none at all")
    void forbiddenStaleWindowsHoldNothing() {
        this.storeDirectly(receivedNow("Cache-Control", "max-age=60, must-revalidate, stale-if-error=600"));
        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(61));

        assertThat(this.lookup().isPresent(), is(false));

        this.storeDirectly(receivedNow("Cache-Control", "max-age=60, no-cache, stale-if-error=600"));

        assertThat(this.lookup().isPresent(), is(false));
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
    @DisplayName("A response carrying no-cache beside max-age is revalidated before each reuse rather than replayed as fresh")
    void noCacheIsRevalidatedWhileFresh() {
        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=60, no-cache", "ETag", "\"v1\"");
        this.resource.get();

        this.origin = request -> answer(request, 304, "Cache-Control", "max-age=60, no-cache", "ETag", "\"v1\"");
        Response<byte[]> first = this.resource.get();
        Response<byte[]> second = this.resource.get();

        assertThat(this.sent, hasSize(3));
        assertThat(this.sent.get(1).headers().get(ETag.IF_NONE_MATCH_HEADER), contains("\"v1\""));
        assertThat(this.sent.getLast().headers().get(ETag.IF_NONE_MATCH_HEADER), contains("\"v1\""));
        assertThat(first.isFromCache(), is(true));
        assertThat(second.isFromCache(), is(true));
    }

    @Test
    @DisplayName("A response carrying no-cache and no validator is fetched in full on the next request")
    void noCacheWithoutValidatorIsFetchedAgain() {
        this.origin = request -> answer(request, 200, "Cache-Control", "max-age=60, no-cache");
        this.resource.get();
        Response<byte[]> again = this.resource.get();

        assertThat(again.isFromCache(), is(false));
        assertThat(this.sent, hasSize(2));
        assertThat(this.sent.getLast().headers().containsKey(ETag.IF_NONE_MATCH_HEADER), is(false));
    }

    @Test
    @DisplayName("A response carrying no request start is not stored, where one carrying it is")
    void responseWithoutRequestStartIsNotStored() {
        Request request = Request.create(Request.HttpMethod.GET, URL, headers(), null, StandardCharsets.UTF_8, null);
        String now = Instant.now().toString();

        feign.Response unstamped = answer(request, 200, "Cache-Control", "max-age=60", NetworkDetails.RESPONSE_RECEIVED, now);
        this.cache.store(new Response.Impl<>(unstamped, () -> BODY), BODY, request.headers());

        assertThat(this.lookup().isPresent(), is(false));

        feign.Response stamped = answer(
            request,
            200,
            "Cache-Control", "max-age=60",
            NetworkDetails.REQUEST_START, now,
            NetworkDetails.RESPONSE_RECEIVED, now
        );
        this.cache.store(new Response.Impl<>(stamped, () -> BODY), BODY, request.headers());

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

    @Test
    @DisplayName("A response varying on a request header is replayed to a request with the same value, not a different one")
    void variantIsReplayedOnlyToTheSameValue() {
        this.origin = request -> answer(request, 200, "Vary", "Accept", "Cache-Control", "max-age=60");

        Response<byte[]> live = this.negotiated.accept("application/json");
        Response<byte[]> replay = this.negotiated.accept("application/json");
        Response<byte[]> other = this.negotiated.accept("text/plain");

        assertThat(live.isFromCache(), is(false));
        assertThat(replay.isFromCache(), is(true));
        assertThat(other.isFromCache(), is(false));
        assertThat(this.sent, hasSize(2));
    }

    @Test
    @DisplayName("Two variants of one URL are held together and each is replayed to its own request")
    void variantsOfOneUrlCoexist() {
        this.origin = request -> answer(
            request,
            200,
            header(request, "Accept").getBytes(StandardCharsets.UTF_8),
            "Vary", "Accept",
            "Cache-Control", "max-age=60"
        );

        this.negotiated.accept("a");
        this.negotiated.accept("b");
        Response<byte[]> a = this.negotiated.accept("a");
        Response<byte[]> b = this.negotiated.accept("b");

        assertThat(this.sent, hasSize(2));
        assertThat(a.isFromCache(), is(true));
        assertThat(new String(a.getBody(), StandardCharsets.UTF_8), is("a"));
        assertThat(b.isFromCache(), is(true));
        assertThat(new String(b.getBody(), StandardCharsets.UTF_8), is("b"));
    }

    @Test
    @DisplayName("A 304 refreshes the variant it revalidates and leaves the URL's other variant as stored")
    void notModifiedRefreshesTheVariantRevalidated() {
        this.origin = ResponseCacheTest::negotiatedAnswer;
        this.negotiated.accept("a");
        this.negotiated.accept("b");

        Response<byte[]> revalidated = this.negotiated.accept("a");

        assertThat(revalidated.isFromCache(), is(true));
        assertThat(new String(revalidated.getBody(), StandardCharsets.UTF_8), is("a"));
        assertThat(this.sent, hasSize(3));
        assertThat(this.sent.getLast().headers().get(ETag.IF_NONE_MATCH_HEADER), contains("\"a\""));

        this.ticks.addAndGet(TimeUnit.SECONDS.toNanos(120));
        Response<byte[]> replay = this.negotiated.accept("a");

        assertThat(replay.isFromCache(), is(true));
        assertThat(this.sent, hasSize(3));
        assertThat(
            this.cache.lookup(HttpMethod.GET, URL, headers("Accept", "a")).orElseThrow().response().getHeaders().get("Cache-Control"),
            contains("max-age=600")
        );
        assertThat(
            this.cache.lookup(HttpMethod.GET, URL, headers("Accept", "b")).orElseThrow().response().getHeaders().get("Cache-Control"),
            contains("max-age=60")
        );
    }

    @Test
    @DisplayName("Of variants stored under different Vary sets that both match a request, the one with the latest Date answers")
    void latestDateAnswersAmongMatchingVariants() {
        Instant now = Instant.now();
        String latest = HTTP_DATE.format(now);
        String earlier = HTTP_DATE.format(now.minusSeconds(60));

        assertThat(this.answering(varyingOnAccept(now, "Date", latest), varyingOnLanguage(now, "Date", earlier)), contains("\"accept\""));
        assertThat(this.answering(varyingOnAccept(now, "Date", earlier), varyingOnLanguage(now, "Date", latest)), contains("\"language\""));
    }

    @Test
    @DisplayName("A matching variant with no Date is dated by when it was received")
    void variantWithoutDateIsDatedByItsReceipt() {
        Instant now = Instant.now();
        Instant earlier = now.minusSeconds(60);

        assertThat(this.answering(varyingOnAccept(earlier, "Date", HTTP_DATE.format(earlier)), varyingOnLanguage(now)), contains("\"language\""));
        assertThat(this.answering(varyingOnAccept(now, "Date", HTTP_DATE.format(now)), varyingOnLanguage(earlier)), contains("\"accept\""));
    }

    @Test
    @DisplayName("Matching variants with the same Date are told apart by when each was received, and then by their fingerprints")
    void matchingVariantsWithOneDateAreOrderedDeterministically() {
        Instant now = Instant.now();
        Instant earlier = now.minusMillis(500);
        String date = HTTP_DATE.format(now);

        assertThat(this.answering(varyingOnAccept(now, "Date", date), varyingOnLanguage(earlier, "Date", date)), contains("\"accept\""));
        assertThat(this.answering(varyingOnAccept(earlier, "Date", date), varyingOnLanguage(now, "Date", date)), contains("\"language\""));
        assertThat(this.answering(varyingOnAccept(now, "Date", date), varyingOnLanguage(now, "Date", date)), contains("\"language\""));
        assertThat(this.answering(varyingOnLanguage(now, "Date", date), varyingOnAccept(now, "Date", date)), contains("\"language\""));
    }

    @Test
    @DisplayName("A response with no Vary is replayed to every request for its URL")
    void responseWithoutVaryMatchesEveryRequest() {
        this.negotiated.accept("a");
        Response<byte[]> replay = this.negotiated.accept("b");

        assertThat(replay.isFromCache(), is(true));
        assertThat(this.sent, hasSize(1));
    }

    @Test
    @DisplayName("A response whose Vary names * among other headers is not stored")
    void wildcardAmongVaryNamesIsNotStored() {
        this.origin = request -> answer(request, 200, "Vary", "Accept, *", "Cache-Control", "max-age=60");

        this.resource.get();

        assertThat(this.resource.get().isFromCache(), is(false));
        assertThat(this.sent, hasSize(2));
    }

    @Test
    @DisplayName("A response varying on an internal header is not stored, as each request carries its own value")
    void internalHeaderVariantIsNotStored() {
        this.origin = request -> answer(request, 200, "Vary", NetworkDetails.DNS_START, "Cache-Control", "max-age=60");

        this.resource.get();

        assertThat(this.lookup().isPresent(), is(false));
        assertThat(this.resource.get().isFromCache(), is(false));
        assertThat(this.sent, hasSize(2));
    }

    @Test
    @DisplayName("A response varying on Cookie is matched only on a Cookie its request carried, never on one the transport may add")
    void cookieVariantNeedsACookieTheRequestCarried() {
        this.origin = request -> answer(request, 200, "Vary", "Cookie", "Cache-Control", "max-age=60");

        this.resource.get();
        Response<byte[]> uncookied = this.resource.get();

        this.negotiated.cookie("session=1");
        Response<byte[]> sameCookie = this.negotiated.cookie("session=1");
        Response<byte[]> otherCookie = this.negotiated.cookie("session=2");

        assertThat(uncookied.isFromCache(), is(false));
        assertThat(sameCookie.isFromCache(), is(true));
        assertThat(otherCookie.isFromCache(), is(false));
        assertThat(this.sent, hasSize(4));
    }

}
