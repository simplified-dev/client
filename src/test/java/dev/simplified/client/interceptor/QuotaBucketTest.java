package dev.simplified.client.interceptor;

import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class QuotaBucketTest {

    @Route("api.github.com")
    interface GitHubContract extends Contract {

        @RequestLine("GET /repos/simplified-dev/client")
        String repository();

        @RequestLine("GET /search/repositories")
        String searchRepositories();

    }

    @Route("api.github.com/users")
    interface GitHubUsersContract extends Contract {

        @RequestLine("GET /octocat")
        String user();

    }

    @Route("api.hypixel.net/v2")
    interface HypixelContract extends Contract {

        @RequestLine("GET /skyblock/profiles")
        String profiles();

    }

    private final long resetSecond = System.currentTimeMillis() / 1000L + 3600L;

    /**
     * The manager every client in a test shares, as the clients of a proxy share theirs.
     */
    private final RateLimitManager manager = new RateLimitManager();

    private static Map<String, Collection<String>> headers(String... pairs) {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (int i = 0; i < pairs.length; i += 2)
            headers.put(pairs[i], List.of(pairs[i + 1]));

        return headers;
    }

    private String[] gitHub(String quota, long limit, long remaining) {
        return new String[] {
            "x-ratelimit-limit", Long.toString(limit),
            "x-ratelimit-remaining", Long.toString(remaining),
            "x-ratelimit-used", Long.toString(limit - remaining),
            "x-ratelimit-reset", Long.toString(this.resetSecond),
            "x-ratelimit-resource", quota
        };
    }

    /**
     * One endpoint of a client on the shared manager, driven through that client's own request
     * interceptor, rate-limiting transport and response interceptor.
     */
    private final class Endpoint {

        private final RouteDiscovery discovery;

        private final MethodMetadata metadata;

        private final InternalRequestInterceptor requests;

        private final InternalResponseInterceptor responses;

        private Endpoint(Class<? extends Contract> contract, String method) {
            this.discovery = new RouteDiscovery(ClientConfig.builder(contract, GsonSettings.builder().build()).build());
            this.metadata = new feign.Contract.Default().parseAndValidateMetadata(contract).stream()
                .filter(candidate -> candidate.method().getName().equals(method))
                .findFirst()
                .orElseThrow();
            this.requests = new InternalRequestInterceptor(QuotaBucketTest.this.manager, this.discovery);
            this.responses = new InternalResponseInterceptor(QuotaBucketTest.this.manager, this.discovery);
        }

        /**
         * Sends a request through the request interceptor and the rate-limiting transport, and
         * answers it with the given headers.
         *
         * @return {@code false} if the rate-limiting transport refused the request
         */
        private boolean send(String... answer) {
            RequestTemplate template = this.metadata.template().resolve(Map.of()).methodMetadata(this.metadata);
            this.requests.apply(template);

            RateLimitingFeignClient transport = new RateLimitingFeignClient(
                (request, options) -> Response.builder()
                    .status(200)
                    .reason("OK")
                    .request(request)
                    .headers(headers(answer))
                    .build(),
                QuotaBucketTest.this.manager,
                this.discovery
            );
            Response response;

            try {
                response = transport.execute(template.request(), new Request.Options());
            } catch (RateLimitException ex) {
                return false;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }

            this.responses.recordServerLimit(response, System.currentTimeMillis());
            return true;
        }

        private String routeKey() {
            return this.discovery.getMetadata(this.metadata.method()).getBucketKey();
        }

        /**
         * The bucket this endpoint's next request is gated and counted against.
         */
        private String bucketKey() {
            return QuotaBucketTest.this.manager.getBucketKey(this.routeKey(), this.metadata.configKey());
        }

    }

    @Test
    @DisplayName("Two routes answering one GitHub quota share one bucket")
    void routesSharingAQuotaShareABucket() {
        Endpoint repository = new Endpoint(GitHubContract.class, "repository");
        Endpoint user = new Endpoint(GitHubUsersContract.class, "user");

        assertThat(repository.send(this.gitHub("core", 5000, 4990)), is(true));
        assertThat(user.send(this.gitHub("core", 5000, 4989)), is(true));

        assertThat(user.routeKey(), is(not(repository.routeKey())));
        assertThat(user.bucketKey(), is(repository.bucketKey()));
        assertThat(this.manager.getRequestCount(repository.bucketKey()), is(11L));

        assertThat(repository.send(), is(true));
        assertThat(this.manager.getRequestCount(user.bucketKey()), is(12L));
    }

    @Test
    @DisplayName("Two GitHub quotas on one route keep two buckets")
    void quotasOnOneRouteKeepTheirOwnBuckets() {
        Endpoint repository = new Endpoint(GitHubContract.class, "repository");
        Endpoint search = new Endpoint(GitHubContract.class, "searchRepositories");

        assertThat(search.routeKey(), is(repository.routeKey()));

        assertThat(repository.send(this.gitHub("core", 5000, 4999)), is(true));
        assertThat(search.send(this.gitHub("search", 30, 0)), is(true));

        assertThat(search.bucketKey(), is(not(repository.bucketKey())));
        assertThat(search.send(), is(false));
        assertThat(repository.send(), is(true));
    }

    @Test
    @DisplayName("A response that names no quota keeps the route's bucket")
    void unnamedQuotaKeepsTheRouteBucket() {
        Endpoint profiles = new Endpoint(HypixelContract.class, "profiles");

        assertThat(profiles.send("RateLimit-Limit", "120", "RateLimit-Remaining", "100", "RateLimit-Reset", "30"), is(true));

        assertThat(profiles.bucketKey(), is(profiles.routeKey()));
        assertThat(this.manager.getRequestCount(profiles.routeKey()), is(20L));
    }

    @Test
    @DisplayName("A route reads the bucket of the quota its latest response named")
    void routeReadsItsLatestQuota() {
        Endpoint repository = new Endpoint(GitHubContract.class, "repository");

        assertThat(repository.send(this.gitHub("core", 5000, 4990)), is(true));

        assertThat(this.manager.getBucketKey(repository.routeKey()), is(repository.bucketKey()));
        assertThat(this.manager.getRemaining(this.manager.getBucketKey(repository.routeKey())), is(4990L));
    }

}
