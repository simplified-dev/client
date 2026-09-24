package dev.simplified.client.route;

import dev.simplified.client.ClientConfig;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.gson.GsonSettings;
import feign.RequestLine;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

class RouteDiscoveryMethodRouteTest {

    enum Host implements DynamicRouteProvider {

        MEDIA;

        @Override
        public @NotNull String getRoute() {
            return "media.example.com";
        }

    }

    @DynamicRoute
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Hosted {

        Host value();

    }

    @Route("api.example.com")
    interface SplitContract extends Contract {

        @RequestLine("GET /status")
        String status();

        @Route(value = "uploads.example.com/v2", rateLimit = @RateLimitConfig(limit = 5, window = 1))
        @RequestLine("POST /files")
        String upload();

        @Hosted(Host.MEDIA)
        @RequestLine("GET /avatar")
        String avatar();

    }

    private final RouteDiscovery discovery = new RouteDiscovery(
        ClientConfig.builder(SplitContract.class, GsonSettings.builder().build()).build()
    );

    private RouteDiscovery.Metadata metadata(String method) throws NoSuchMethodException {
        return this.discovery.getMetadata(SplitContract.class.getMethod(method));
    }

    @Test
    @DisplayName("A method-level @Route overrides the type-level route for that method")
    void methodRouteOverridesTypeRoute() throws NoSuchMethodException {
        RouteDiscovery.Metadata upload = this.metadata("upload");

        assertThat(upload.getRoute(), is("uploads.example.com/v2"));
        assertThat(upload.getFullUrl(), is("https://uploads.example.com/v2"));
        assertThat(upload.getBucketKey(), is("uploads.example.com/v2"));
        assertThat(upload.getRateLimit().getLimit(), is(5L));
    }

    @Test
    @DisplayName("A method-level @DynamicRoute overrides the type-level @Route for that method")
    void methodDynamicRouteOverridesTypeRoute() throws NoSuchMethodException {
        assertThat(this.metadata("avatar").getRoute(), is("media.example.com"));
    }

    @Test
    @DisplayName("A method without a route of its own takes the type-level route")
    void unannotatedMethodTakesTypeRoute() throws NoSuchMethodException {
        assertThat(this.metadata("status").getRoute(), is("api.example.com"));
        assertThat(this.metadata("status").getBucketKey(), is(this.discovery.getDefaultRoute().getBucketKey()));
    }

    @Test
    @DisplayName("A URL under a method-level route matches that route")
    void urlMatchFindsMethodRoute() {
        assertThat(this.discovery.findMatchingMetadata("https://uploads.example.com/v2/files").getRoute(), is("uploads.example.com/v2"));
        assertThat(this.discovery.findMatchingMetadata("https://api.example.com/status").getRoute(), is("api.example.com"));
    }

    @Test
    @DisplayName("The advertised hosts include each method-level route's")
    void advertisedHostsIncludeMethodRoutes() {
        assertThat(this.discovery.collectAdvertisedHosts(), containsInAnyOrder("api.example.com", "uploads.example.com", "media.example.com"));
    }

}
