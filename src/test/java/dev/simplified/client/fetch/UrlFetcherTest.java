package dev.simplified.client.fetch;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.exception.UrlFetchException;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.request.Request;
import dev.simplified.client.request.Timings;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
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
     * The requests {@code /big-fresh} has answered.
     */
    private final AtomicInteger bigFreshHits = new AtomicInteger();

    /**
     * The requests {@code /missing} has answered.
     */
    private final AtomicInteger missingHits = new AtomicInteger();

    /**
     * The requests {@code /retired} has answered.
     */
    private final AtomicInteger retiredHits = new AtomicInteger();

    /**
     * The requests {@code /unknown-client} has answered.
     */
    private final AtomicInteger unknownClientHits = new AtomicInteger();

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
     * The {@code If-None-Match} values each request {@code /no-cache} answered carried, in the
     * order they arrived.
     */
    private final List<List<String>> noCacheValidators = new CopyOnWriteArrayList<>();

    /**
     * The {@code If-None-Match} values each request {@code /validated} answered carried, in the
     * order they arrived.
     */
    private final List<List<String>> validatedValidators = new CopyOnWriteArrayList<>();

    /**
     * The headers each request {@code /static-negotiated} answered carried, in the order they
     * arrived.
     */
    private final List<SentHeaders> staticNegotiated = new CopyOnWriteArrayList<>();

    /**
     * The requests {@code /revaried} has answered.
     */
    private final AtomicInteger revariedHits = new AtomicInteger();

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
        this.server.createContext("/big-fresh", exchange -> {
            this.bigFreshHits.incrementAndGet();
            byte[] body = new byte[64 * 1024];
            java.util.Arrays.fill(body, (byte) 'b');
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
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
        this.server.createContext("/missing", exchange -> {
            this.missingHits.incrementAndGet();
            byte[] body = "gone".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/missing-big", exchange -> {
            byte[] body = new byte[64 * 1024];
            java.util.Arrays.fill(body, (byte) 'm');
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/bad-big", exchange -> {
            byte[] body = new byte[64 * 1024];
            java.util.Arrays.fill(body, (byte) 'b');
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/unknown-client", exchange -> {
            this.unknownClientHits.incrementAndGet();
            byte[] body = "refused".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(460, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/unknown-client-big", exchange -> {
            byte[] body = new byte[64 * 1024];
            java.util.Arrays.fill(body, (byte) 'u');
            exchange.sendResponseHeaders(460, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/unknown-server", exchange -> {
            byte[] body = "odd".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(540, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/unknown-success", exchange -> {
            byte[] body = "odd".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(299, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/unknown-nginx", exchange -> {
            byte[] body = "invalid token".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(498, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/unknown-empty", exchange -> {
            exchange.sendResponseHeaders(460, -1);
            exchange.close();
        });
        this.server.createContext("/wobbly", exchange -> revalidatedWith(exchange, 540));
        this.server.createContext("/unavailable-must-revalidate", exchange -> revalidatedWith(exchange, 503, "must-revalidate"));
        this.server.createContext("/unavailable-proxy-revalidate", exchange -> revalidatedWith(exchange, 503, "proxy-revalidate"));
        this.server.createContext("/unavailable-no-cache", exchange -> revalidatedWith(exchange, 503, "no-cache"));
        this.server.createContext("/validated", exchange -> {
            List<String> validator = exchange.getRequestHeaders().getOrDefault("If-None-Match", List.of());
            this.validatedValidators.add(List.copyOf(validator));
            exchange.getResponseHeaders().add("ETag", "\"v1\"");

            if (!validator.isEmpty()) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }

            byte[] body = "validated".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/no-cache", exchange -> {
            List<String> validator = exchange.getRequestHeaders().getOrDefault("If-None-Match", List.of());
            this.noCacheValidators.add(List.copyOf(validator));
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60, no-cache");

            if (!validator.isEmpty()) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }

            byte[] body = "checked".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/withdrawn", exchange -> revalidatedWith(exchange, 460));
        this.server.createContext("/retired", exchange -> {
            this.retiredHits.incrementAndGet();
            exchange.getResponseHeaders().add("ETag", "\"v1\"");

            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                exchange.sendResponseHeaders(410, -1);
                exchange.close();
                return;
            }

            byte[] body = "retiring".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "max-age=0, stale-if-error=60");
            exchange.sendResponseHeaders(200, body.length);
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
        this.server.createContext("/revaried", exchange -> {
            this.revariedHits.incrementAndGet();
            exchange.getResponseHeaders().add("ETag", "\"v1\"");

            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                exchange.getResponseHeaders().add("Vary", "X-Static");
                exchange.getResponseHeaders().add("Cache-Control", "max-age=100");
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }

            byte[] body = "revaried".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Vary", "X-Variant");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.getResponseHeaders().add("Age", "120");
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
     * Answers a request with {@code 200 steady}, stale at once and servable stale on error for a
     * minute under an {@code ETag}, or a conditional request with {@code revalidationStatus}.
     *
     * @param exchange the exchange to answer
     * @param revalidationStatus the status a conditional request is answered with
     * @throws IOException if writing the response fails
     */
    private static void revalidatedWith(HttpExchange exchange, int revalidationStatus) throws IOException {
        revalidatedWith(exchange, revalidationStatus, "");
    }

    /**
     * Answers as {@link #revalidatedWith(HttpExchange, int)} does, with {@code directive} added to
     * the {@code 200}'s {@code Cache-Control} when it is not empty.
     *
     * @param exchange the exchange to answer
     * @param revalidationStatus the status a conditional request is answered with
     * @param directive the directive added to the {@code 200}'s {@code Cache-Control}, or an
     *                  empty string for none
     * @throws IOException if writing the response fails
     */
    private static void revalidatedWith(HttpExchange exchange, int revalidationStatus, String directive) throws IOException {
        boolean conditional = exchange.getRequestHeaders().containsKey("If-None-Match");
        byte[] body = (conditional ? "odd" : "steady").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("ETag", "\"v1\"");

        if (!conditional)
            exchange.getResponseHeaders().add("Cache-Control", "max-age=0, stale-if-error=60" + (directive.isEmpty() ? "" : ", " + directive));

        exchange.sendResponseHeaders(conditional ? revalidationStatus : 200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
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

    /**
     * Stores a fresh {@code GET} response for {@code uri} in {@code cache} the way a writer
     * other than the fetcher would, with {@code max-age=60} and no {@code Vary}.
     *
     * @param cache the cache to store into
     * @param uri the URL the response answers
     * @param status the response's status
     * @param body the response's body
     */
    private static void storeDirectly(ResponseCache cache, URI uri, HttpStatus status, byte[] body) {
        HttpClientContext context = HttpClientContext.create();
        context.setAttribute(NetworkDetails.REQUEST_START, Instant.now());
        context.setAttribute(NetworkDetails.RESPONSE_RECEIVED, Instant.now());
        NetworkDetails details = new NetworkDetails(context);

        Response.DirectImpl<byte[]> response = new Response.DirectImpl<>(
            status,
            new Request.Impl(HttpMethod.GET, uri.toString()),
            () -> details,
            Map.of("Cache-Control", List.of("max-age=60")),
            () -> body
        );
        cache.store(response, body, Map.of());
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
    @DisplayName("A per-request cap below the configured one refuses a body the configured cap admits")
    void perRequestCapBelowTheConfiguredRefuses() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        UrlFetchException.BodyCapExceeded raised = assertThrows(
            UrlFetchException.BodyCapExceeded.class,
            () -> fetcher.bytes(this.baseUri.resolve("/big"), 1024)
        );

        assertThat(raised.getMaxBytes(), is(1024L));
    }

    @Test
    @DisplayName("A per-request cap above the configured one admits a body the configured cap refuses")
    void perRequestCapAboveTheConfiguredAdmits() {
        UrlFetcher fetcher = buildFetcher(1024);

        Response<String> result = fetcher.get(this.baseUri.resolve("/big"), 128 * 1024);

        assertThat(result.getBody().length(), is(64 * 1024));
    }

    @Test
    @DisplayName("A negative per-request cap is refused")
    void negativePerRequestCapIsRefused() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        assertThrows(IllegalArgumentException.class, () -> fetcher.get(this.baseUri.resolve("/hello"), -1));
    }

    @Test
    @DisplayName("A fresh cached body larger than the request's cap raises without a request, and stays cached")
    void freshCachedBodyOverTheCapRaises() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/big-fresh");

        fetcher.bytes(uri);
        UrlFetchException.BodyCapExceeded raised = assertThrows(
            UrlFetchException.BodyCapExceeded.class,
            () -> fetcher.bytes(uri, 1024)
        );
        Response<byte[]> replay = fetcher.bytes(uri);

        assertThat(raised.isFromCache(), is(true));
        assertThat(raised.getMaxBytes(), is(1024L));
        assertThat(replay.isFromCache(), is(true));
        assertThat(replay.getBody().length, is(64 * 1024));
        assertThat(this.bigFreshHits.get(), is(1));
    }

    @Test
    @DisplayName("A body replayed on a 304 is held to the request's cap")
    void notModifiedReplayOverTheCapRaises() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/revalidated");

        fetcher.get(uri);
        UrlFetchException.BodyCapExceeded raised = assertThrows(
            UrlFetchException.BodyCapExceeded.class,
            () -> fetcher.get(uri, 4)
        );

        assertThat(raised.isFromCache(), is(true));
        assertThat(this.revalidatedHits.get(), is(2));
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
    @DisplayName("A 4xx raises ClientError carrying the origin's status and body")
    void clientErrorStatusRaisesClientError() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        UrlFetchException.ClientError raised = assertThrows(
            UrlFetchException.ClientError.class,
            () -> fetcher.get(this.baseUri.resolve("/missing"))
        );

        assertThat(raised.getStatus(), is(HttpStatus.NOT_FOUND));
        assertThat(raised.getStatusCode(), is(404));
        assertThat(new String(raised.getBody().orElseThrow(), StandardCharsets.UTF_8), is(equalTo("gone")));
        assertThat(raised.getMessage(), containsString("404"));
    }

    @Test
    @DisplayName("A 4xx HttpStatus has no constant for raises ClientError carrying the origin's code and body")
    void unknownClientErrorStatusRaisesClientError() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        UrlFetchException.ClientError raised = assertThrows(
            UrlFetchException.ClientError.class,
            () -> fetcher.get(this.baseUri.resolve("/unknown-client"))
        );

        assertThat(raised.getStatusCode(), is(460));
        assertThat(raised.getStatus(), is(HttpStatus.UNKNOWN_ERROR));
        assertThat(new String(raised.getBody().orElseThrow(), StandardCharsets.UTF_8), is(equalTo("refused")));
        assertThat(raised.getMessage(), containsString("460"));
        assertThat(raised.getUrl(), is(this.baseUri.resolve("/unknown-client")));
    }

    @Test
    @DisplayName("A 4xx HttpStatus has no constant for is never stored, so each fetch of it reaches the origin")
    void unknownClientErrorStatusIsNeverStored() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        assertThrows(UrlFetchException.ClientError.class, () -> fetcher.bytes(this.baseUri.resolve("/unknown-client")));
        assertThrows(UrlFetchException.ClientError.class, () -> fetcher.bytes(this.baseUri.resolve("/unknown-client")));
        assertThat(this.unknownClientHits.get(), is(2));
    }

    @Test
    @DisplayName("A 4xx HttpStatus has no constant for, with a body larger than the cap, raises ClientError with the body cut at the cap")
    void unknownClientErrorOverTheCapRaisesClientError() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        UrlFetchException.ClientError raised = assertThrows(
            UrlFetchException.ClientError.class,
            () -> fetcher.bytes(this.baseUri.resolve("/unknown-client-big"), 1024)
        );

        assertThat(raised.getStatusCode(), is(460));
        assertThat(raised.getBody().orElseThrow().length, is(1024));
    }

    @Test
    @DisplayName("A 5xx HttpStatus has no constant for raises a UrlFetchException that is not a ClientError")
    void unknownServerErrorStatusIsNotAClientError() {
        UrlFetchException raised = assertThrows(
            UrlFetchException.class,
            () -> buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(this.baseUri.resolve("/unknown-server"))
        );

        assertThat(raised, is(not(instanceOf(UrlFetchException.ClientError.class))));
        assertThat(raised.getStatusCode(), is(540));
        assertThat(raised.getStatus(), is(HttpStatus.UNKNOWN_ERROR));
        assertThat(new String(raised.getBody().orElseThrow(), StandardCharsets.UTF_8), is(equalTo("odd")));
    }

    @Test
    @DisplayName("A 2xx HttpStatus has no constant for raises a UrlFetchException that is not a ClientError")
    void unknownSuccessStatusRaises() {
        UrlFetchException raised = assertThrows(
            UrlFetchException.class,
            () -> buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).get(this.baseUri.resolve("/unknown-success"))
        );

        assertThat(raised, is(not(instanceOf(UrlFetchException.ClientError.class))));
        assertThat(raised.getStatusCode(), is(299));
    }

    @Test
    @DisplayName("A code in the Nginx range HttpStatus has no constant for raises as the Nginx codes it names do, not as a ClientError")
    void unknownNginxStatusIsNotAClientError() {
        UrlFetchException raised = assertThrows(
            UrlFetchException.class,
            () -> buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(this.baseUri.resolve("/unknown-nginx"))
        );

        assertThat(raised, is(not(instanceOf(UrlFetchException.ClientError.class))));
        assertThat(raised.getStatusCode(), is(498));
    }

    @Test
    @DisplayName("An unknown status raises with a message naming the code and the URL")
    void unknownStatusMessageNamesCodeAndUrl() {
        URI uri = this.baseUri.resolve("/unknown-client");
        UrlFetchException raised = assertThrows(
            UrlFetchException.class,
            () -> buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(uri)
        );

        assertThat(raised.getMessage(), is(equalTo("Origin returned unknown status 460 for URL '" + uri + "'")));
    }

    @Test
    @DisplayName("A 4xx HttpStatus has no constant for, sent without a body, raises ClientError carrying no body")
    void unknownClientErrorWithoutBody() {
        UrlFetchException.ClientError raised = assertThrows(
            UrlFetchException.ClientError.class,
            () -> buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(this.baseUri.resolve("/unknown-empty"))
        );

        assertThat(raised.getStatusCode(), is(460));
        assertThat(raised.getBody(), is(Optional.empty()));
    }

    @Test
    @DisplayName("A 5xx HttpStatus has no constant for, answering a revalidation, is replaced by the stale entry within stale-if-error")
    void unknownServerErrorOnRevalidationServesStale() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/wobbly");

        fetcher.get(uri);
        Response<String> replay = fetcher.get(uri);

        assertThat(replay.getBody(), is(equalTo("steady")));
        assertThat(replay.isStaleFromCache(), is(true));
    }

    @Test
    @DisplayName("A 5xx answering a revalidation raises, rather than replaying the stale entry, when the entry must be revalidated")
    void serverErrorOnRevalidationOfAnEntryThatMustBeRevalidatedRaises() {
        for (String directive : List.of("must-revalidate", "proxy-revalidate", "no-cache")) {
            UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
            URI uri = this.baseUri.resolve("/unavailable-" + directive);

            fetcher.get(uri);
            UrlFetchException raised = assertThrows(UrlFetchException.class, () -> fetcher.get(uri), directive);

            assertThat(directive, raised.getStatus(), is(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(directive, raised.isStaleFromCache(), is(false));
        }
    }

    @Test
    @DisplayName("A response carrying no-cache beside max-age is revalidated before each reuse rather than replayed as fresh")
    void noCacheIsRevalidatedWhileFresh() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/no-cache");

        Response<String> live = fetcher.get(uri);
        Response<String> first = fetcher.get(uri);
        Response<String> second = fetcher.get(uri);

        assertThat(live.isFromCache(), is(false));
        assertThat(first.isFromCache(), is(true));
        assertThat(first.getBody(), is(equalTo("checked")));
        assertThat(second.isFromCache(), is(true));
        assertThat(this.noCacheValidators, contains(List.of(), List.of("\"v1\""), List.of("\"v1\"")));
    }

    @Test
    @DisplayName("A 304 whose Vary names another of the request's headers keeps its variant reachable under that header")
    void notModifiedWithAnotherVaryKeepsTheVariantReachable() {
        AtomicReference<String> variant = new AtomicReference<>("a");
        UrlFetcher fetcher = UrlFetcher.create(
            UrlFetcherConfig.builder(new Gson())
                .withHeader("X-Static", "s")
                .withDynamicHeader("X-Variant", () -> Optional.of(variant.get()))
                .build()
        );
        URI uri = this.baseUri.resolve("/revaried");

        Response<String> live = fetcher.get(uri);
        Response<String> revalidated = fetcher.get(uri);
        Response<String> replay = fetcher.get(uri);
        variant.set("b");
        Response<String> otherVariant = fetcher.get(uri);

        assertThat(live.isFromCache(), is(false));
        assertThat(revalidated.isFromCache(), is(true));
        assertThat(replay.isFromCache(), is(true));
        assertThat(otherVariant.isFromCache(), is(true));
        assertThat(otherVariant.getBody(), is(equalTo("revaried")));
        assertThat(this.revariedHits.get(), is(2));
    }

    @Test
    @DisplayName("A response carrying a validator and no freshness is revalidated on the next fetch, and a 304 answers it from the cache")
    void validatorWithoutFreshnessIsRevalidated() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/validated");

        Response<String> live = fetcher.get(uri);
        Response<String> revalidated = fetcher.get(uri);

        assertThat(live.isFromCache(), is(false));
        assertThat(revalidated.isFromCache(), is(true));
        assertThat(revalidated.getBody(), is(equalTo("validated")));
        assertThat(this.validatedValidators, contains(List.of(), List.of("\"v1\"")));
    }

    @Test
    @DisplayName("A fetcher whose timings retain nothing stale fetches a response with a validator and no freshness in full again")
    void zeroStaleRetentionHoldsNothingForRevalidation() {
        Timings defaults = Timings.createDefault();
        UrlFetcher fetcher = UrlFetcher.create(
            UrlFetcherConfig.builder(new Gson())
                .withTimings(new Timings(
                    defaults.connectionTimeToLive(),
                    defaults.connectionIdleTimeout(),
                    defaults.connectionKeepAlive(),
                    defaults.connectTimeout(),
                    defaults.socketTimeout(),
                    defaults.maxConnections(),
                    defaults.maxConnectionsPerRoute(),
                    defaults.maxCacheBytes(),
                    defaults.cacheSafetyFallback(),
                    0L
                ))
                .build()
        );
        URI uri = this.baseUri.resolve("/validated");

        fetcher.get(uri);
        Response<String> again = fetcher.get(uri);

        assertThat(again.isFromCache(), is(false));
        assertThat(this.validatedValidators, contains(List.of(), List.of()));
    }

    @Test
    @DisplayName("A 4xx HttpStatus has no constant for, answering a revalidation, raises ClientError rather than replaying the stale entry")
    void unknownClientErrorOnRevalidationRaises() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/withdrawn");

        fetcher.get(uri);
        UrlFetchException.ClientError raised = assertThrows(UrlFetchException.ClientError.class, () -> fetcher.get(uri));

        assertThat(raised.getStatusCode(), is(460));
        assertThat(new String(raised.getBody().orElseThrow(), StandardCharsets.UTF_8), is(equalTo("odd")));
    }

    @Test
    @DisplayName("ofUnknownStatus refuses a code HttpStatus has a constant for, naming the code")
    void ofUnknownStatusRefusesKnownCode() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> UrlFetchException.ofUnknownStatus(404, this.baseUri, Map.of(), new byte[0], NetworkDetails.EMPTY)
        );

        assertThat(thrown.getMessage(), containsString("'404'"));
    }

    @Test
    @DisplayName("ofStatus raises a context carrying a code HttpStatus has no constant for as ofUnknownStatus does")
    void ofStatusRaisesAnUnknownCodeAsOfUnknownStatusDoes() {
        for (int code : new int[] { 460, 498, 561, 218 }) {
            ErrorContext context = new ErrorContext(
                HttpStatus.UNKNOWN_ERROR,
                code,
                HttpMethod.GET,
                this.baseUri.toString(),
                Map.of(),
                Map.of(),
                new byte[0]
            );
            UrlFetchException viaStatus = UrlFetchException.ofStatus(context, NetworkDetails.EMPTY);
            UrlFetchException viaCode = UrlFetchException.ofUnknownStatus(code, this.baseUri, Map.of(), new byte[0], NetworkDetails.EMPTY);

            assertThat("code " + code, viaStatus.getClass(), is(equalTo(viaCode.getClass())));
            assertThat("code " + code, viaStatus.getMessage(), is(equalTo(viaCode.getMessage())));
        }
    }

    @Test
    @DisplayName("The exception raised for a code HttpStatus has no constant for is the fetcher's last response")
    void unknownStatusIsTheLastResponse() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        fetcher.get(this.baseUri.resolve("/fresh"));

        UrlFetchException.ClientError raised = assertThrows(
            UrlFetchException.ClientError.class,
            () -> fetcher.get(this.baseUri.resolve("/unknown-client"))
        );

        assertThat(fetcher.getLastResponse().orElseThrow(), is(sameInstance(raised)));
    }

    @Test
    @DisplayName("A synthetic status carries its own code as the status code")
    void syntheticStatusCarriesItsCode() {
        UrlFetchException.BodyCapExceeded raised = assertThrows(
            UrlFetchException.BodyCapExceeded.class,
            () -> buildFetcher(1024).bytes(this.baseUri.resolve("/big"))
        );

        assertThat(raised.getStatusCode(), is(HttpStatus.IO_ERROR.getCode()));
    }

    @Test
    @DisplayName("A 4xx is never stored, so each fetch of it reaches the origin")
    void clientErrorStatusIsNeverStored() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        assertThrows(UrlFetchException.ClientError.class, () -> fetcher.bytes(this.baseUri.resolve("/missing")));
        assertThrows(UrlFetchException.ClientError.class, () -> fetcher.bytes(this.baseUri.resolve("/missing")));
        assertThat(this.missingHits.get(), is(2));
    }

    @Test
    @DisplayName("A 5xx raises a UrlFetchException that is not a ClientError")
    void serverErrorStatusIsNotAClientError() {
        UrlFetchException raised = assertThrows(
            UrlFetchException.class,
            () -> buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(this.baseUri.resolve("/bad"))
        );

        assertThat(raised, is(not(instanceOf(UrlFetchException.ClientError.class))));
        assertThat(raised.getStatus(), is(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("A 4xx whose body is larger than the cap raises ClientError rather than BodyCapExceeded")
    void clientErrorOverTheCapRaisesClientError() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        assertThrows(UrlFetchException.ClientError.class, () -> fetcher.get(this.baseUri.resolve("/missing-big"), 1024));
    }

    @Test
    @DisplayName("A 4xx body larger than the cap is cut at the cap")
    void clientErrorBodyIsCutAtTheCap() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        UrlFetchException.ClientError raised = assertThrows(
            UrlFetchException.ClientError.class,
            () -> fetcher.bytes(this.baseUri.resolve("/missing-big"), 1024)
        );

        assertThat(raised.getBody().orElseThrow().length, is(1024));
    }

    @Test
    @DisplayName("A 5xx whose body is larger than the cap raises its status rather than BodyCapExceeded")
    void serverErrorOverTheCapRaisesItsStatus() {
        UrlFetcher fetcher = buildFetcher(1024);

        UrlFetchException raised = assertThrows(UrlFetchException.class, () -> fetcher.bytes(this.baseUri.resolve("/bad-big")));

        assertThat(raised.getStatus(), is(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("A cached 4xx whose body is larger than the cap raises ClientError with the body cut at the cap")
    void cachedClientErrorOverTheCapRaisesClientError() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/missing");
        storeDirectly(fetcher.getResponseCache(), uri, HttpStatus.NOT_FOUND, new byte[4096]);

        UrlFetchException.ClientError raised = assertThrows(UrlFetchException.ClientError.class, () -> fetcher.bytes(uri, 1024));

        assertThat(raised.getBody().orElseThrow().length, is(1024));
    }

    @Test
    @DisplayName("A 4xx answering a revalidation raises rather than replaying the stale entry")
    void clientErrorOnRevalidationRaises() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/retired");

        Response<String> live = fetcher.get(uri);
        UrlFetchException.ClientError raised = assertThrows(UrlFetchException.ClientError.class, () -> fetcher.get(uri));

        assertThat(live.getBody(), is(equalTo("retiring")));
        assertThat(raised.getStatus(), is(HttpStatus.GONE));
        assertThat(this.retiredHits.get(), is(2));
    }

    @Test
    @DisplayName("A 4xx another writer stored in a shared cache raises ClientError on replay without reaching the origin")
    void cachedClientErrorRaisesOnReplay() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/missing");
        storeDirectly(fetcher.getResponseCache(), uri, HttpStatus.NOT_FOUND, "stored".getBytes(StandardCharsets.UTF_8));

        UrlFetchException.ClientError raised = assertThrows(UrlFetchException.ClientError.class, () -> fetcher.get(uri));

        assertThat(raised.isFromCache(), is(true));
        assertThat(new String(raised.getBody().orElseThrow(), StandardCharsets.UTF_8), is(equalTo("stored")));
        assertThat(this.missingHits.get(), is(0));
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
