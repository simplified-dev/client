package dev.simplified.client;

import dev.simplified.client.cache.CacheKey;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import dev.simplified.gson.GsonSettings;
import feign.RequestLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Tests that a {@link Client}'s static query parameters reach the origin and key its response
 * cache: a {@link Client} configured with a static {@code key} query, sending through the
 * production Apache transport to a {@link LoopbackOrigin} that answers {@code /resource} fresh for
 * a minute with the query it received as the body, and a {@code POST} to {@code /create} with a
 * {@code 201} whose {@code Location} names {@code /resource}.
 */
class ClientStaticQueryTest {

    @Route(value = "127.0.0.1", rateLimit = @RateLimitConfig(unlimited = true))
    interface Origin extends Contract {

        @RequestLine("GET /resource")
        Response<byte[]> resource();

        @RequestLine("POST /create")
        Response<byte[]> create();

    }

    private static final String SECRET = "query-secret-7d3b";

    /**
     * The URL the contract's {@code /resource} is requested at, before any query.
     */
    private static final String RESOURCE = "https://127.0.0.1/resource";

    private final LoopbackOrigin origin = new LoopbackOrigin((request, exchange) -> {
        if (request.method().equals("POST"))
            LoopbackOrigin.respond(exchange, 201, "", "Location", RESOURCE);
        else
            LoopbackOrigin.respond(exchange, 200, Objects.toString(request.uri().getRawQuery(), ""), "Cache-Control", "max-age=60");
    });

    private final Client<Origin> client = this.origin.client(
        ClientConfig.builder(Origin.class, GsonSettings.builder().build())
            .withQuery("key", SECRET)
            .build()
    );

    ClientStaticQueryTest() throws IOException, GeneralSecurityException { }

    @AfterEach
    void stopOrigin() {
        this.origin.close();
    }

    @Test
    @DisplayName("The origin receives the static query, and the cache keys the answer by it, so a request without it is not answered")
    void staticQueryKeysTheCache() {
        Response<byte[]> live = this.client.getContract().resource();
        Response<byte[]> replay = this.client.getContract().resource();
        String keyed = CacheKey.withQuery(RESOURCE, CacheKey.queryFingerprints(Map.of("key", SECRET)));

        assertThat(this.origin.received(), hasSize(1));
        assertThat(this.origin.received().getFirst().uri().getRawQuery(), is("key=" + SECRET));
        assertThat(new String(live.getBody(), StandardCharsets.UTF_8), is("key=" + SECRET));
        assertThat(replay.isFromCache(), is(true));
        assertThat(this.client.getResponseCache().lookup(HttpMethod.GET, RESOURCE, Map.of()).isPresent(), is(false));
        assertThat(this.client.getResponseCache().lookup(HttpMethod.GET, keyed, Map.of()).isPresent(), is(true));
        assertThat(live.getRequest().getUrl(), not(containsString(SECRET)));
    }

    @Test
    @DisplayName("A mutation whose Location names a cached URL invalidates it as the client keys it")
    void locationInvalidatesTheKeyedUrl() {
        Response<byte[]> live = this.client.getContract().resource();
        this.client.getContract().create();
        Response<byte[]> next = this.client.getContract().resource();

        assertThat(live.isFromCache(), is(false));
        assertThat(next.isFromCache(), is(false));
        assertThat(this.origin.received(), hasSize(3));
    }

}
