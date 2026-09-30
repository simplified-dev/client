package dev.simplified.client.interceptor;

import dev.simplified.client.ClientConfig;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.ratelimit.RateLimitingFeignClient;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.Route;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.gson.GsonSettings;
import feign.MethodMetadata;
import feign.Request;
import feign.RequestLine;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class InternalResponseInterceptorTest {

    @Route("127.0.0.1:0")
    interface ShapeContract extends Contract {

        @RequestLine("GET /resource")
        String resource();

    }

    /**
     * The endpoint every request in a test invokes, as Feign parses it.
     */
    private static final MethodMetadata RESOURCE = new feign.Contract.Default().parseAndValidateMetadata(ShapeContract.class).getFirst();

    /**
     * The clock reading every request and response in a test is stamped with. It is the real
     * time because {@link RateLimitManager#getRequestCount(String)} reads the window against the
     * system clock; every later instant a test reaches is an offset from it.
     */
    private final long now = System.currentTimeMillis();

    private final long nowSecond = this.now / 1000L;

    private final RateLimitManager manager = new RateLimitManager();

    private final RouteDiscovery discovery = new RouteDiscovery(
        ClientConfig.builder(ShapeContract.class, GsonSettings.builder().build()).build()
    );

    private final InternalResponseInterceptor interceptor = new InternalResponseInterceptor(this.manager, this.discovery);

    private final String key = this.discovery.getDefaultRoute().getBucketKey();

    private final RateLimit policy = this.discovery.getDefaultRoute().getRateLimit();

    /**
     * The bucket the endpoint's next request is gated and counted against.
     */
    private String bucket() {
        return this.manager.getBucketKey(this.key, RESOURCE.configKey());
    }

    private long count() {
        return this.manager.getRequestCount(this.bucket());
    }

    /**
     * Mirrors {@link RateLimitingFeignClient#execute}: refuses the request if its bucket is
     * exhausted, otherwise counts it, in one atomic step.
     */
    private boolean send(long at) {
        return this.manager.tryAcquire(this.bucket(), this.policy, at);
    }

    private static Map<String, Collection<String>> headers(String... headerPairs) {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (int i = 0; i < headerPairs.length; i += 2)
            headers.put(headerPairs[i], List.of(headerPairs[i + 1]));

        return headers;
    }

    private Request request(String... headerPairs) {
        RequestTemplate template = new RequestTemplate().methodMetadata(RESOURCE);
        return Request.create(Request.HttpMethod.GET, "https://127.0.0.1:0/resource", headers(headerPairs), null, StandardCharsets.UTF_8, template);
    }

    /**
     * A request numbered as {@link InternalRequestInterceptor} numbers the requests it sends.
     */
    private Request numbered(long sequence) {
        return this.request(InternalRequestInterceptor.SEQUENCE_HEADER, Long.toString(sequence));
    }

    private Response response(String... headerPairs) {
        return this.response(this.request(), headerPairs);
    }

    private Response response(Request request, String... headerPairs) {
        return Response.builder()
            .status(200)
            .reason("OK")
            .request(request)
            .headers(headers(headerPairs))
            .build();
    }

    /**
     * Builds the replay {@link CachingFeignClient} answers a {@code 304} with: the stored headers
     * overlaid by the 304's, marked as a cache hit that names the headers the 304 carried.
     */
    private Response revalidationReplay(String[] storedPairs, String... notModifiedPairs) {
        Map<String, Collection<String>> notModified = headers(notModifiedPairs);
        Map<String, Collection<String>> replayed = headers(storedPairs);
        replayed.putAll(notModified);
        replayed.put(ResponseCache.CACHE_HIT_HEADER, List.of("true"));
        replayed.put(ResponseCache.REVALIDATED_HEADER, List.copyOf(notModified.keySet()));

        return Response.builder()
            .status(200)
            .reason("OK")
            .request(this.request())
            .headers(replayed)
            .build();
    }

    private Response gitHubResponse(long limit, long remaining, long resetSecond) {
        return this.gitHubResponse(this.request(), limit, remaining, resetSecond);
    }

    private Response gitHubResponse(Request request, long limit, long remaining, long resetSecond) {
        return this.response(
            request,
            "x-ratelimit-limit", Long.toString(limit),
            "x-ratelimit-remaining", Long.toString(remaining),
            "x-ratelimit-used", Long.toString(limit - remaining),
            "x-ratelimit-reset", Long.toString(resetSecond),
            "x-ratelimit-resource", "core"
        );
    }

    @Test
    @DisplayName("GitHub-shaped headers leave an exhausted bucket refused only until the epoch reset")
    void gitHubHeadersDoNotRefuseForever() {
        long resetSecond = this.nowSecond + 3600L;
        long resetMillis = resetSecond * 1000L;

        for (long remaining = 59; remaining >= 0; remaining--) {
            assertThat("request with " + remaining + " remaining is sent", this.send(this.now), is(true));
            this.interceptor.recordServerLimit(this.gitHubResponse(60, remaining, resetSecond), this.now);
        }

        assertThat(this.send(this.now), is(false));
        assertThat(this.send(resetMillis - 1L), is(false));
        assertThat(this.send(resetMillis), is(true));
        assertThat(this.count(), is(1L));
    }

    @Test
    @DisplayName("Hypixel-shaped delta headers refuse until that many seconds after receipt")
    void deltaHeadersRefuseUntilDeltaElapses() {
        assertThat(this.send(this.now), is(true));
        this.interceptor.recordServerLimit(this.response(
            "RateLimit-Limit", "120",
            "RateLimit-Remaining", "0",
            "RateLimit-Reset", "30"
        ), this.now);

        assertThat(this.send(this.now + 29_999L), is(false));
        assertThat(this.send(this.now + 30_000L), is(true));
    }

    @Test
    @DisplayName("The server's remaining count replaces the local tally, refunding requests it never counted")
    void remainingReplacesLocalTally() {
        long resetSecond = this.nowSecond + 3600L;

        this.send(this.now);
        this.interceptor.recordServerLimit(this.gitHubResponse(60, 59, resetSecond), this.now);

        for (int i = 0; i < 10; i++)
            this.send(this.now);

        assertThat(this.count(), is(11L));

        this.interceptor.recordServerLimit(this.gitHubResponse(60, 57, resetSecond), this.now);

        assertThat(this.count(), is(3L));
    }

    @Test
    @DisplayName("A response that lands after the response to a later request cannot roll the count back")
    void lateResponseCannotOverwriteLaterOne() {
        long resetSecond = this.nowSecond + 3600L;

        this.send(this.now);
        Request first = this.numbered(this.manager.nextSequence());
        this.send(this.now);
        Request second = this.numbered(this.manager.nextSequence());

        this.interceptor.recordServerLimit(this.gitHubResponse(second, 60, 58, resetSecond), this.now);
        this.interceptor.recordServerLimit(this.gitHubResponse(first, 60, 59, resetSecond), this.now);

        assertThat(this.count(), is(2L));
    }

    @Test
    @DisplayName("A response to a later request applies after an earlier one")
    void laterResponseApplies() {
        long resetSecond = this.nowSecond + 3600L;

        this.send(this.now);
        Request first = this.numbered(this.manager.nextSequence());
        this.send(this.now);
        Request second = this.numbered(this.manager.nextSequence());

        this.interceptor.recordServerLimit(this.gitHubResponse(first, 60, 55, resetSecond), this.now);
        this.interceptor.recordServerLimit(this.gitHubResponse(second, 60, 50, resetSecond), this.now);

        assertThat(this.count(), is(10L));
    }

    @Test
    @DisplayName("A cache replay's stored rate-limit headers leave the bucket untouched")
    void cacheReplayIsSkipped() {
        long resetSecond = this.nowSecond + 3600L;
        long staleResetSecond = this.nowSecond - 60L;

        this.send(this.now);
        this.interceptor.recordServerLimit(this.gitHubResponse(60, 50, resetSecond), this.now);

        this.interceptor.recordServerLimit(this.response(
            "x-ratelimit-limit", "60",
            "x-ratelimit-remaining", "59",
            "x-ratelimit-reset", Long.toString(staleResetSecond),
            ResponseCache.CACHE_HIT_HEADER, "true"
        ), this.now);

        assertThat(this.count(), is(10L));
        assertThat(this.send(this.now), is(true));
        assertThat(this.count(), is(11L));
    }

    @Test
    @DisplayName("A 304 revalidation applies the rate-limit headers it carried, refunding revalidations the server never charged")
    void revalidationAppliesTheHeadersThe304Carried() {
        long resetSecond = this.nowSecond + 3600L;
        String reset = Long.toString(resetSecond);
        String[] stored = { "ETag", "\"v1\"", "x-ratelimit-limit", "60", "x-ratelimit-remaining", "40", "x-ratelimit-reset", reset };

        this.send(this.now);
        this.interceptor.recordServerLimit(this.gitHubResponse(60, 59, resetSecond), this.now);

        for (int i = 0; i < 10; i++) {
            this.send(this.now);
            this.interceptor.recordServerLimit(this.revalidationReplay(
                stored,
                "ETag", "\"v1\"",
                "x-ratelimit-limit", "60",
                "x-ratelimit-remaining", "59",
                "x-ratelimit-reset", reset
            ), this.now);
        }

        assertThat(this.count(), is(1L));
    }

    @Test
    @DisplayName("A 304 revalidation leaves the bucket untouched by stored rate-limit headers the 304 did not carry")
    void revalidationIgnoresStoredHeaders() {
        long resetSecond = this.nowSecond + 3600L;
        String[] stored = { "ETag", "\"v1\"", "x-ratelimit-limit", "60", "x-ratelimit-remaining", "5", "x-ratelimit-reset", Long.toString(resetSecond) };

        this.send(this.now);
        this.interceptor.recordServerLimit(this.gitHubResponse(60, 59, resetSecond), this.now);

        this.send(this.now);
        this.interceptor.recordServerLimit(this.revalidationReplay(stored, "ETag", "\"v1\""), this.now);

        assertThat(this.count(), is(2L));
    }

    @Test
    @DisplayName("A response without rate-limit headers creates no bucket")
    void noHeadersCreatesNoBucket() {
        this.interceptor.recordServerLimit(this.response("Content-Type", "application/json"), this.now);

        assertThat(this.manager.hasBucket(this.key), is(false));
    }

}
