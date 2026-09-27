package dev.simplified.client.fetch;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import dev.simplified.client.exception.UrlFetchException;
import dev.simplified.client.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlFetcherTest {

    private HttpServer server;
    private URI baseUri;

    /**
     * The requests {@code /fresh} has answered.
     */
    private final AtomicInteger freshHits = new AtomicInteger();

    /**
     * The requests {@code /unavailable} has answered.
     */
    private final AtomicInteger unavailableHits = new AtomicInteger();

    /**
     * The requests {@code /revalidated} has answered.
     */
    private final AtomicInteger revalidatedHits = new AtomicInteger();

    /**
     * The {@code X-Variant} values each request {@code /negotiated} answered carried, in the
     * order they arrived.
     */
    private final List<List<String>> negotiatedVariants = new CopyOnWriteArrayList<>();

    /**
     * The requests {@code /negotiated-stale} has answered.
     */
    private final AtomicInteger staleVariantHits = new AtomicInteger();

    /**
     * The headers each request {@code /static-negotiated} answered carried, in the order they
     * arrived.
     */
    private final List<SentHeaders> staticNegotiated = new CopyOnWriteArrayList<>();

    /**
     * The {@code User-Agent}, {@code Accept} and {@code X-Static} values one request carried.
     *
     * @param userAgent the {@code User-Agent} values
     * @param accept the {@code Accept} values
     * @param custom the {@code X-Static} values
     */
    private record SentHeaders(List<String> userAgent, List<String> accept, List<String> custom) {

        /**
         * Reads the three headers from a request the server received.
         *
         * @param headers the request's headers
         * @return the values each header carried, empty when absent
         */
        static SentHeaders of(Headers headers) {
            return new SentHeaders(
                List.copyOf(headers.getOrDefault("User-Agent", List.of())),
                List.copyOf(headers.getOrDefault("Accept", List.of())),
                List.copyOf(headers.getOrDefault("X-Static", List.of()))
            );
        }

    }

    @BeforeEach
    void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/hello", exchange -> {
            byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/big", exchange -> {
            byte[] body = new byte[64 * 1024];
            java.util.Arrays.fill(body, (byte) 'a');
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/bad", exchange -> {
            byte[] body = "nope".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/fresh", exchange -> {
            this.freshHits.incrementAndGet();
            byte[] body = "fresh".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/unavailable", exchange -> {
            this.unavailableHits.incrementAndGet();
            byte[] body = "down".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/revalidated", exchange -> {
            this.revalidatedHits.incrementAndGet();
            exchange.getResponseHeaders().add("ETag", "\"v1\"");

            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                exchange.getResponseHeaders().add("Cache-Control", "max-age=100");
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }

            byte[] body = "revalidated".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.getResponseHeaders().add("Age", "120");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/negotiated", exchange -> {
            List<String> variant = exchange.getRequestHeaders().getOrDefault("X-Variant", List.of());
            this.negotiatedVariants.add(List.copyOf(variant));
            byte[] body = ("variant-" + String.join(",", variant)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Vary", "X-Variant");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/negotiated-stale", exchange -> {
            this.staleVariantHits.incrementAndGet();
            String variant = exchange.getRequestHeaders().getFirst("X-Variant");
            String etag = "\"" + variant + "\"";
            exchange.getResponseHeaders().add("Vary", "X-Variant");
            exchange.getResponseHeaders().add("ETag", etag);

            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.getResponseHeaders().add("Cache-Control", "max-age=100");
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }

            byte[] body = ("variant-" + variant).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.getResponseHeaders().add("Age", "120");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/static-negotiated", exchange -> {
            this.staticNegotiated.add(SentHeaders.of(exchange.getRequestHeaders()));
            byte[] body = ("agent-" + exchange.getRequestHeaders().getFirst("User-Agent")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Vary", "User-Agent, Accept, X-Static");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.start();
        this.baseUri = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (this.server != null) this.server.stop(0);
    }

    private UrlFetcher buildFetcher(long maxBytes) {
        return UrlFetcher.create(UrlFetcherConfig.builder(new Gson()).withMaxBodyBytes(maxBytes).build());
    }

    /**
     * Builds a fetcher whose every request carries a dynamic {@code X-Variant} header.
     *
     * @param variant the value the header carries, read on each request
     * @return the fetcher
     */
    private static UrlFetcher variantFetcher(AtomicReference<String> variant) {
        return UrlFetcher.create(
            UrlFetcherConfig.builder(new Gson())
                .withDynamicHeader("X-Variant", () -> Optional.of(variant.get()))
                .build()
        );
    }

    @Test
    @DisplayName("Fetches a small body and decodes by Content-Type charset")
    void fetchesSmallBody() {
        Response<String> result = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).get(this.baseUri.resolve("/hello"));
        assertThat(result.getStatus().getCode(), is(equalTo(200)));
        assertThat(result.getBody(), is(equalTo("hello world")));
        assertThat(result.getContentType().orElse(""), containsString("text/plain"));
    }

    @Test
    @DisplayName("Refuses bodies that exceed the configured cap")
    void refusesOversizedBody() {
        UrlFetcher fetcher = buildFetcher(1024);
        try {
            fetcher.bytes(this.baseUri.resolve("/big"));
        } catch (UrlFetchException.BodyCapExceeded expected) {
            assertThat(expected.getMessage(), containsString("exceeded cap"));
            return;
        }
        throw new AssertionError("Expected BodyCapExceeded for oversized body");
    }

    @Test
    @DisplayName("Throws on non-2xx responses")
    void throwsOnFailureStatus() {
        try {
            buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(this.baseUri.resolve("/bad"));
        } catch (UrlFetchException expected) {
            assertThat(expected.getMessage(), containsString("503"));
            return;
        }
        throw new AssertionError("Expected UrlFetchException for HTTP 503");
    }

    @Test
    @DisplayName("Serves a fresh response from the cache without reaching the origin again")
    void servesFreshResponseFromCache() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        Response<String> live = fetcher.get(this.baseUri.resolve("/fresh"));
        Response<String> replay = fetcher.get(this.baseUri.resolve("/fresh"));

        assertThat(this.freshHits.get(), is(1));
        assertThat(live.isFromCache(), is(false));
        assertThat(replay.isFromCache(), is(true));
        assertThat(replay.getBody(), is(equalTo("fresh")));
    }

    @Test
    @DisplayName("Raises an error status on every fetch, never replaying it from the cache")
    void neverReplaysAnErrorStatus() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        assertThrows(UrlFetchException.class, () -> fetcher.bytes(this.baseUri.resolve("/unavailable")));
        assertThrows(UrlFetchException.class, () -> fetcher.bytes(this.baseUri.resolve("/unavailable")));
        assertThat(this.unavailableHits.get(), is(2));
    }

    @Test
    @DisplayName("A 304 is answered with the refreshed entry, which is then aged from the revalidation")
    void notModifiedReplaysTheRefreshedEntry() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/revalidated");

        Response<String> live = fetcher.get(uri);
        Response<String> revalidated = fetcher.get(uri);
        Response<String> replay = fetcher.get(uri);

        assertThat(live.isFromCache(), is(false));
        assertThat(revalidated.isFromCache(), is(true));
        assertThat(revalidated.getHeaders().get("Cache-Control"), contains("max-age=100"));
        assertThat(replay.isFromCache(), is(true));
        assertThat(replay.getBody(), is(equalTo("revalidated")));
        assertThat(this.revalidatedHits.get(), is(2));
    }

    @Test
    @DisplayName("Each value of the header a response varies on keeps its own variant, and the header is sent once")
    void variantsFollowTheVariedHeader() {
        AtomicReference<String> variant = new AtomicReference<>("a");
        UrlFetcher fetcher = variantFetcher(variant);
        URI uri = this.baseUri.resolve("/negotiated");

        Response<String> a = fetcher.get(uri);
        Response<String> aReplay = fetcher.get(uri);
        variant.set("b");
        Response<String> b = fetcher.get(uri);
        Response<String> bReplay = fetcher.get(uri);
        variant.set("a");
        Response<String> aAgain = fetcher.get(uri);

        assertThat(a.isFromCache(), is(false));
        assertThat(aReplay.isFromCache(), is(true));
        assertThat(b.isFromCache(), is(false));
        assertThat(b.getBody(), is(equalTo("variant-b")));
        assertThat(bReplay.isFromCache(), is(true));
        assertThat(bReplay.getBody(), is(equalTo("variant-b")));
        assertThat(aAgain.isFromCache(), is(true));
        assertThat(aAgain.getBody(), is(equalTo("variant-a")));
        assertThat(this.negotiatedVariants, contains(List.of("a"), List.of("b")));
    }

    @Test
    @DisplayName("A 304 refreshes the variant of the request it revalidates, and each variant revalidates on its own")
    void notModifiedRefreshesTheVariantRevalidated() {
        AtomicReference<String> variant = new AtomicReference<>("a");
        UrlFetcher fetcher = variantFetcher(variant);
        URI uri = this.baseUri.resolve("/negotiated-stale");

        fetcher.get(uri);
        variant.set("b");
        fetcher.get(uri);
        variant.set("a");
        Response<String> revalidated = fetcher.get(uri);
        Response<String> replay = fetcher.get(uri);
        variant.set("b");
        Response<String> other = fetcher.get(uri);

        assertThat(revalidated.isFromCache(), is(true));
        assertThat(revalidated.getBody(), is(equalTo("variant-a")));
        assertThat(revalidated.getHeaders().get("Cache-Control"), contains("max-age=100"));
        assertThat(replay.isFromCache(), is(true));
        assertThat(replay.getBody(), is(equalTo("variant-a")));
        assertThat(other.isFromCache(), is(true));
        assertThat(other.getBody(), is(equalTo("variant-b")));
        assertThat(this.staleVariantHits.get(), is(4));
    }

    @Test
    @DisplayName("The static headers reach the origin once each, and a response varying on them is replayed only to their values")
    void staticHeadersReachTheOrigin() {
        URI uri = this.baseUri.resolve("/static-negotiated");
        UrlFetcher defaults = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        UrlFetcher configured = UrlFetcher.create(
            UrlFetcherConfig.builder(new Gson())
                .withHeader("User-Agent", "probe/1")
                .withHeader("Accept", "application/json")
                .withHeader("X-Static", "s1")
                .withSharedCache(defaults.getResponseCache())
                .build()
        );

        Response<String> live = defaults.get(uri);
        Response<String> replay = defaults.get(uri);
        Response<String> other = configured.get(uri);
        Response<String> otherReplay = configured.get(uri);

        assertThat(this.staticNegotiated, contains(
            new SentHeaders(List.of(UrlFetcherConfig.DEFAULT_USER_AGENT), List.of("*/*"), List.of()),
            new SentHeaders(List.of("probe/1"), List.of("application/json"), List.of("s1"))
        ));
        assertThat(live.isFromCache(), is(false));
        assertThat(replay.isFromCache(), is(true));
        assertThat(replay.getBody(), is(equalTo("agent-" + UrlFetcherConfig.DEFAULT_USER_AGENT)));
        assertThat(other.isFromCache(), is(false));
        assertThat(otherReplay.isFromCache(), is(true));
        assertThat(otherReplay.getBody(), is(equalTo("agent-probe/1")));
    }

    @Test
    @DisplayName("A request carrying a configured Authorization does not follow a redirect to another host")
    void credentialStopsACrossHostRedirect() throws IOException {
        List<List<String>> landed = new CopyOnWriteArrayList<>();
        HttpServer elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        elsewhere.createContext("/landing", exchange -> {
            landed.add(List.copyOf(exchange.getRequestHeaders().getOrDefault("Authorization", List.of())));
            byte[] body = "landed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        elsewhere.start();

        String landing = "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/landing";
        this.server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", landing);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        try {
            URI uri = this.baseUri.resolve("/moved");
            Response<String> anonymous = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).get(uri);
            Response<String> credentialed = UrlFetcher.create(
                UrlFetcherConfig.builder(new Gson())
                    .withHeader("Authorization", "Bearer one")
                    .build()
            ).get(uri);

            assertThat(anonymous.getStatus().getCode(), is(200));
            assertThat(anonymous.getBody(), is(equalTo("landed")));
            assertThat(credentialed.getStatus().getCode(), is(302));
            assertThat(landed, is(equalTo(List.<List<String>>of(List.of()))));
        } finally {
            elsewhere.stop(0);
        }
    }

}
