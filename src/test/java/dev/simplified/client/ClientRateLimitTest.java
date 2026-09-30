package dev.simplified.client;

import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.response.ETag;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import dev.simplified.gson.GsonSettings;
import feign.Request;
import feign.RequestLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a {@link Client} holds to its client-side rate limit only the requests that leave
 * it: a {@link Client} built over a scripted transport, on a route allowing two requests a
 * minute.
 */
class ClientRateLimitTest {

    @Route(value = "127.0.0.1:0", rateLimit = @RateLimitConfig(limit = 2, window = 60))
    interface Resource extends Contract {

        @RequestLine("GET /fresh")
        Response<byte[]> fresh();

        @RequestLine("GET /uncached")
        Response<byte[]> uncached();

        @RequestLine("GET /revalidated")
        Response<byte[]> revalidated();

    }

    private static final String ETAG = "\"v1\"";

    /**
     * The {@code GET} requests that reached the transport.
     */
    private final AtomicInteger answered = new AtomicInteger();

    private final Client<Resource> client = new Client<>(
        ClientConfig.builder(Resource.class, GsonSettings.builder().build()).build(),
        this::answer
    );

    /**
     * Answers {@code /fresh} fresh for a minute, {@code /uncached} with {@code no-store}, and
     * {@code /revalidated} stale on arrival under a validator, with a {@code 304} to a request
     * carrying it; answers anything else, the client's connection warm-up, with an empty
     * {@code 200}.
     *
     * @param request the request
     * @param options the request options
     * @return the answer
     */
    private feign.Response answer(Request request, Request.Options options) {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int status = 200;
        byte[] body = new byte[0];

        if (request.httpMethod() == Request.HttpMethod.GET) {
            this.answered.incrementAndGet();
            body = "answered".getBytes(StandardCharsets.UTF_8);

            if (request.url().endsWith("/fresh"))
                headers.put("Cache-Control", List.of("max-age=60"));
            else if (request.url().endsWith("/uncached"))
                headers.put("Cache-Control", List.of("no-store"));
            else {
                headers.put("Cache-Control", List.of("max-age=0"));
                headers.put("ETag", List.of(ETAG));

                if (request.headers().containsKey(ETag.IF_NONE_MATCH_HEADER)) {
                    status = 304;
                    body = new byte[0];
                }
            }
        }

        return feign.Response.builder()
            .status(status)
            .reason(status == 304 ? "Not Modified" : "OK")
            .request(request)
            .headers(headers)
            .body(body)
            .build();
    }

    @Test
    @DisplayName("A fresh cache hit is neither refused by the client-side rate limit nor counted against it")
    void freshHitIsNotRateLimited() {
        Resource resource = this.client.getContract();

        resource.fresh();
        long remaining = this.client.getRemainingRequests();
        Response<byte[]> first = resource.fresh();
        Response<byte[]> second = resource.fresh();
        Response<byte[]> third = resource.fresh();

        assertThat(remaining, is(1L));
        assertThat(first.isFromCache(), is(true));
        assertThat(second.isFromCache(), is(true));
        assertThat(third.isFromCache(), is(true));
        assertThat(this.client.getRemainingRequests(), is(1L));
        assertThat(this.answered.get(), is(1));
    }

    @Test
    @DisplayName("A request the cache cannot answer is still counted, and refused once the bucket is spent")
    void uncachedRequestIsRateLimited() {
        Resource resource = this.client.getContract();

        resource.fresh();
        resource.fresh();
        resource.uncached();
        RateLimitException refused = assertThrows(RateLimitException.class, resource::uncached);

        assertThat(refused.isServerEnforced(), is(false));
        assertThat(this.client.getRemainingRequests(), is(0L));
        assertThat(this.answered.get(), is(2));
    }

    @Test
    @DisplayName("A conditional request revalidating a stale entry leaves the client, so it is counted and refused as any other")
    void revalidationIsRateLimited() {
        Resource resource = this.client.getContract();

        resource.revalidated();
        Response<byte[]> revalidated = resource.revalidated();

        assertThat(revalidated.isFromCache(), is(true));
        assertThat(this.client.getRemainingRequests(), is(0L));
        assertThrows(RateLimitException.class, resource::revalidated);
        assertThat(this.answered.get(), is(2));
    }

    @Test
    @DisplayName("Requests sent together are admitted no further than the client-side rate limit allows")
    void concurrentRequestsAreAdmittedNoFurtherThanTheLimit() throws Exception {
        Resource resource = this.client.getContract();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int round = 0; round < 50; round++) {
                this.client.getRateLimitManager().clear();
                this.answered.set(0);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> sent = new ArrayList<>();

                for (int i = 0; i < threads; i++) {
                    sent.add(pool.submit(() -> {
                        start.await();

                        try {
                            resource.uncached();
                            return true;
                        } catch (RateLimitException refused) {
                            return false;
                        }
                    }));
                }

                start.countDown();
                long admitted = 0;

                for (Future<Boolean> future : sent) {
                    if (future.get())
                        admitted++;
                }

                assertThat("round " + round, admitted, is(2L));
                assertThat("round " + round, this.answered.get(), is(2));
            }
        } finally {
            pool.shutdownNow();
        }
    }

}
