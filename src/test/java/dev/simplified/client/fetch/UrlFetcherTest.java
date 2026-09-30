package dev.simplified.client.fetch;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.exception.UrlFetchException;
import dev.simplified.client.ratelimit.RateLimit;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
     * The requests {@code /unknown-success} has answered.
     */
    private final AtomicInteger unknownSuccessHits = new AtomicInteger();

    /**
     * The requests {@code /choices} has answered.
     */
    private final AtomicInteger choicesHits = new AtomicInteger();

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
            this.unknownSuccessHits.incrementAndGet();
            byte[] body = "odd".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(299, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/unknown-success-big", exchange -> {
            byte[] body = new byte[64 * 1024];
            java.util.Arrays.fill(body, (byte) 's');
            exchange.sendResponseHeaders(299, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/choices", exchange -> {
            this.choicesHits.incrementAndGet();
            byte[] body = "pick one".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(300, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/unknown-redirection", exchange -> {
            byte[] body = "odd".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(399, body.length);
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

    /**
     * The most an {@linkplain #endless endless} body sends, far more than any socket buffers
     * between the origin and the fetcher hold.
     */
    private static final long ENDLESS_BYTES = 64L * 1024 * 1024;

    /**
     * Answers with {@code status} and a chunked body of {@link #ENDLESS_BYTES}, recording whether
     * the fetcher hung up before the whole body went out.
     *
     * @param exchange the exchange to answer
     * @param status the status to answer with
     * @param hungUp completed with {@code true} if a write failed because the fetcher hung up, or
     *               {@code false} if the whole body went out
     */
    private static void endless(HttpExchange exchange, int status, CompletableFuture<Boolean> hungUp) {
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'e');

        try {
            exchange.sendResponseHeaders(status, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                for (long sent = 0; sent < ENDLESS_BYTES; sent += chunk.length)
                    os.write(chunk);
            }

            hungUp.complete(false);
        } catch (IOException ex) {
            hungUp.complete(true);
        } finally {
            exchange.close();
        }
    }

    /**
     * Builds a fetcher with the default timings but for its connect and socket timeouts.
     *
     * @param connectTimeout the connect timeout in milliseconds
     * @param socketTimeout the socket timeout in milliseconds
     * @return the fetcher
     */
    private static UrlFetcher timedFetcher(long connectTimeout, long socketTimeout) {
        Timings defaults = Timings.createDefault();

        return UrlFetcher.create(
            UrlFetcherConfig.builder(new Gson())
                .withTimings(new Timings(
                    defaults.connectionTimeToLive(),
                    defaults.connectionIdleTimeout(),
                    defaults.connectionKeepAlive(),
                    connectTimeout,
                    socketTimeout,
                    defaults.maxConnections(),
                    defaults.maxConnectionsPerRoute(),
                    defaults.maxCacheBytes(),
                    defaults.cacheSafetyFallback(),
                    defaults.cacheStaleRetention()
                ))
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
    @DisplayName("A negative configured cap is refused")
    void negativeConfiguredCapIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> UrlFetcherConfig.builder(new Gson()).withMaxBodyBytes(-1));
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
    @DisplayName("A 2xx HttpStatus has no constant for is a 200 success, recorded as the last response and never stored")
    void unknownSuccessStatusIsReadAsOk() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/unknown-success");

        Response<String> first = fetcher.get(uri);
        Response<String> second = fetcher.get(uri);
        Response<?> last = fetcher.getLastResponse().orElseThrow();

        assertThat(first.getStatus(), is(HttpStatus.OK));
        assertThat(first.getBody(), is(equalTo("odd")));
        assertThat(second.isFromCache(), is(false));
        assertThat(last, is(not(instanceOf(UrlFetchException.class))));
        assertThat(last.getStatus(), is(HttpStatus.OK));
        assertThat(this.unknownSuccessHits.get(), is(2));
    }

    @Test
    @DisplayName("A 2xx HttpStatus has no constant for, with a body larger than the cap, raises BodyCapExceeded as a 200 does")
    void unknownSuccessOverTheCapRaisesBodyCapExceeded() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);

        UrlFetchException.BodyCapExceeded raised = assertThrows(
            UrlFetchException.BodyCapExceeded.class,
            () -> fetcher.bytes(this.baseUri.resolve("/unknown-success-big"), 1024)
        );

        assertThat(raised.getMaxBytes(), is(1024L));
    }

    @Test
    @DisplayName("A 3xx the transport does not follow raises Redirection, recorded as the last response and never stored")
    void redirectionStatusRaisesRedirection() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/choices");

        UrlFetchException.Redirection first = assertThrows(UrlFetchException.Redirection.class, () -> fetcher.get(uri));
        UrlFetchException.Redirection second = assertThrows(UrlFetchException.Redirection.class, () -> fetcher.get(uri));

        assertThat(first.getStatus(), is(HttpStatus.MULTIPLE_CHOICES));
        assertThat(first.getStatusCode(), is(300));
        assertThat(new String(first.getBody().orElseThrow(), StandardCharsets.UTF_8), is(equalTo("pick one")));
        assertThat(second.isFromCache(), is(false));
        assertThat(fetcher.getLastResponse().orElseThrow(), is(sameInstance(second)));
        assertThat(this.choicesHits.get(), is(2));
    }

    @Test
    @DisplayName("A 3xx HttpStatus has no constant for raises Redirection as the 3xx constants do")
    void unknownRedirectionStatusRaisesRedirection() {
        UrlFetchException.Redirection raised = assertThrows(
            UrlFetchException.Redirection.class,
            () -> buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(this.baseUri.resolve("/unknown-redirection"))
        );

        assertThat(raised.getStatusCode(), is(399));
        assertThat(raised.getStatus(), is(HttpStatus.UNKNOWN_ERROR));
        assertThat(raised.getMessage(), containsString("399"));
    }

    @Test
    @DisplayName("A 3xx another writer stored in a shared cache raises Redirection on replay without reaching the origin")
    void cachedRedirectionRaisesOnReplay() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/choices");
        storeDirectly(fetcher.getResponseCache(), uri, HttpStatus.MULTIPLE_CHOICES, "stored".getBytes(StandardCharsets.UTF_8));

        UrlFetchException.Redirection raised = assertThrows(UrlFetchException.Redirection.class, () -> fetcher.get(uri));

        assertThat(raised.isFromCache(), is(true));
        assertThat(fetcher.getLastResponse().orElseThrow(), is(sameInstance(raised)));
        assertThat(this.choicesHits.get(), is(0));
    }

    @Test
    @DisplayName("A request carrying its own If-None-Match is sent past a fresh cached entry, and the 304 answering it raises Redirection")
    void callersConditionalRequestBypassesTheCache() {
        UrlFetcher fetcher = UrlFetcher.create(
            UrlFetcherConfig.builder(new Gson())
                .withHeader("If-None-Match", "\"v1\"")
                .build()
        );
        URI uri = this.baseUri.resolve("/validated");
        storeDirectly(fetcher.getResponseCache(), uri, HttpStatus.OK, "stored".getBytes(StandardCharsets.UTF_8));

        UrlFetchException.Redirection raised = assertThrows(UrlFetchException.Redirection.class, () -> fetcher.get(uri));

        assertThat(raised.getStatus(), is(HttpStatus.NOT_MODIFIED));
        assertThat(this.validatedValidators, contains(List.of("\"v1\"")));
    }

    @Test
    @DisplayName("The exception raised for an error status is the fetcher's last response")
    void errorStatusIsTheLastResponse() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        fetcher.get(this.baseUri.resolve("/fresh"));

        UrlFetchException.ClientError raised = assertThrows(
            UrlFetchException.ClientError.class,
            () -> fetcher.get(this.baseUri.resolve("/missing"))
        );

        assertThat(fetcher.getLastResponse().orElseThrow(), is(sameInstance(raised)));
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
        for (int code : new int[] { 399, 460, 498, 561, 218 }) {
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
    @DisplayName("A 4xx another writer stored in a shared cache raises ClientError on replay without reaching the origin, recorded as the last response")
    void cachedClientErrorRaisesOnReplay() {
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/missing");
        storeDirectly(fetcher.getResponseCache(), uri, HttpStatus.NOT_FOUND, "stored".getBytes(StandardCharsets.UTF_8));

        UrlFetchException.ClientError raised = assertThrows(UrlFetchException.ClientError.class, () -> fetcher.get(uri));

        assertThat(raised.isFromCache(), is(true));
        assertThat(new String(raised.getBody().orElseThrow(), StandardCharsets.UTF_8), is(equalTo("stored")));
        assertThat(fetcher.getLastResponse().orElseThrow(), is(sameInstance(raised)));
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
    @DisplayName("A 304 that re-varies its entry out of the cache is answered with the entry under the 304's headers, aged from the revalidation")
    void notModifiedThatRefreshesNothingReplaysUnderItsHeaders() {
        AtomicInteger hits = new AtomicInteger();
        this.server.createContext("/revaried-away", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("ETag", "\"v1\"");

            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                exchange.getResponseHeaders().add("Vary", "*");
                exchange.getResponseHeaders().add("Cache-Control", "max-age=100");
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }

            byte[] body = "unvaried".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.getResponseHeaders().add("Age", "120");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/revaried-away");

        fetcher.get(uri);
        Response<String> revalidated = fetcher.get(uri);
        Response<String> again = fetcher.get(uri);

        assertThat(revalidated.isFromCache(), is(true));
        assertThat(revalidated.getBody(), is(equalTo("unvaried")));
        assertThat(revalidated.getHeaders().get("Cache-Control"), contains("max-age=100"));
        assertThat(revalidated.getHeaders().get("Vary"), contains("*"));
        assertThat(Long.parseLong(revalidated.getHeaders().get("Age").getFirst()), is(lessThan(120L)));
        assertThat(again.isFromCache(), is(false));
        assertThat(hits.get(), is(3));
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
    @DisplayName("Fetchers sharing a cache with different static queries each reach the origin, and each is answered from the cache only with its own")
    void staticQueriesKeyTheCache() {
        List<String> received = new CopyOnWriteArrayList<>();
        this.server.createContext("/static-query", exchange -> {
            String query = Objects.toString(exchange.getRequestURI().getRawQuery(), "");
            received.add(query);
            byte[] body = query.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        URI uri = this.baseUri.resolve("/static-query");
        UrlFetcher first = UrlFetcher.create(UrlFetcherConfig.builder(new Gson()).withQuery("key", "first-secret").build());
        UrlFetcher second = UrlFetcher.create(
            UrlFetcherConfig.builder(new Gson())
                .withQuery("key", "second-secret")
                .withSharedCache(first.getResponseCache())
                .build()
        );
        UrlFetcher none = UrlFetcher.create(UrlFetcherConfig.builder(new Gson()).withSharedCache(first.getResponseCache()).build());

        Response<String> noneLive = none.get(uri);
        Response<String> firstLive = first.get(uri);
        Response<String> secondLive = second.get(uri);
        Response<String> noneReplay = none.get(uri);
        Response<String> firstReplay = first.get(uri);
        Response<String> secondReplay = second.get(uri);

        assertThat(received, contains("", "key=first-secret", "key=second-secret"));
        assertThat(noneLive.isFromCache(), is(false));
        assertThat(firstLive.isFromCache(), is(false));
        assertThat(secondLive.isFromCache(), is(false));
        assertThat(firstReplay.isFromCache(), is(true));
        assertThat(firstReplay.getBody(), is(equalTo("key=first-secret")));
        assertThat(secondReplay.isFromCache(), is(true));
        assertThat(secondReplay.getBody(), is(equalTo("key=second-secret")));
        assertThat(noneReplay.isFromCache(), is(true));
        assertThat(noneReplay.getBody(), is(equalTo("")));
        assertThat(firstReplay.getRequest().getUrl(), not(containsString("first-secret")));
    }

    @Test
    @DisplayName("No internal header reaches the origin")
    void sendsNoInternalHeader() {
        List<List<String>> internal = new CopyOnWriteArrayList<>();
        this.server.createContext("/wire", exchange -> {
            internal.add(exchange.getRequestHeaders().keySet().stream().filter(NetworkDetails::isInternalHeader).toList());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(this.baseUri.resolve("/wire"));

        assertThat(internal, is(equalTo(List.<List<String>>of(List.of()))));
    }

    @Test
    @DisplayName("A request carrying a configured Authorization does not follow a redirect to another host, and raises Redirection")
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
            UrlFetcher credentialed = UrlFetcher.create(
                UrlFetcherConfig.builder(new Gson())
                    .withHeader("Authorization", "Bearer one")
                    .build()
            );

            UrlFetchException.Redirection raised = assertThrows(UrlFetchException.Redirection.class, () -> credentialed.get(uri));

            assertThat(anonymous.getStatus().getCode(), is(200));
            assertThat(anonymous.getBody(), is(equalTo("landed")));
            assertThat(raised.getStatus(), is(HttpStatus.FOUND));
            assertThat(raised.getHeaders().get("Location"), contains(landing));
            assertThat(landed, is(equalTo(List.<List<String>>of(List.of()))));
        } finally {
            elsewhere.stop(0);
        }
    }

    @Test
    @DisplayName("A body past the cap is not downloaded further: the fetch hangs up on it and raises BodyCapExceeded")
    void bodyCapAbortsTheDownload() throws Exception {
        CompletableFuture<Boolean> hungUp = new CompletableFuture<>();
        this.server.createContext("/endless", exchange -> endless(exchange, 200, hungUp));

        assertThrows(UrlFetchException.BodyCapExceeded.class, () -> buildFetcher(1024).bytes(this.baseUri.resolve("/endless")));

        assertThat(hungUp.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    @DisplayName("An error body past the cap is cut there and not downloaded further")
    void errorBodyCutAtTheCapAbortsTheDownload() throws Exception {
        CompletableFuture<Boolean> hungUp = new CompletableFuture<>();
        this.server.createContext("/endless-missing", exchange -> endless(exchange, 404, hungUp));

        UrlFetchException.ClientError raised = assertThrows(
            UrlFetchException.ClientError.class,
            () -> buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(this.baseUri.resolve("/endless-missing"), 1024)
        );

        assertThat(raised.getBody().orElseThrow().length, is(1024));
        assertThat(hungUp.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    @DisplayName("A 5xx replaced by a stale-if-error replay is not downloaded: the fetch hangs up on it")
    void staleIfErrorReplayAbortsTheDownload() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CompletableFuture<Boolean> hungUp = new CompletableFuture<>();
        this.server.createContext("/stale-endless", exchange -> {
            if (hits.getAndIncrement() > 0) {
                endless(exchange, 503, hungUp);
                return;
            }

            byte[] body = "steady".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=0, stale-if-error=60");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/stale-endless");

        fetcher.get(uri);
        Response<String> replay = fetcher.get(uri);

        assertThat(replay.getBody(), is(equalTo("steady")));
        assertThat(replay.isStaleFromCache(), is(true));
        assertThat(hungUp.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    @DisplayName("A 5xx arriving after the entry's stale-if-error window closed is replaced by the entry when the window was open as the fetch began")
    void staleIfErrorIsJudgedAsTheFetchBegins() {
        AtomicInteger hits = new AtomicInteger();
        this.server.createContext("/stale-slow", exchange -> {
            boolean first = hits.getAndIncrement() == 0;

            if (!first) {
                try {
                    Thread.sleep(2_500L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] body = (first ? "steady" : "down").getBytes(StandardCharsets.UTF_8);

            if (first) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                exchange.getResponseHeaders().add("Cache-Control", "max-age=0, stale-if-error=1");
            }

            exchange.sendResponseHeaders(first ? 200 : 503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/stale-slow");

        fetcher.get(uri);
        Response<String> replay = fetcher.get(uri);

        assertThat(replay.getBody(), is(equalTo("steady")));
        assertThat(replay.isStaleFromCache(), is(true));
        assertThat(hits.get(), is(2));
    }

    @Test
    @DisplayName("A 5xx answering the request for an entry without a validator is replaced by the entry within its stale-if-error window")
    void staleIfErrorWithoutValidatorServesStale() {
        List<Boolean> conditional = new CopyOnWriteArrayList<>();
        this.server.createContext("/unvalidated", exchange -> {
            Headers sent = exchange.getRequestHeaders();
            conditional.add(sent.containsKey("If-None-Match") || sent.containsKey("If-Modified-Since"));
            boolean first = conditional.size() == 1;
            byte[] body = (first ? "steady" : "down").getBytes(StandardCharsets.UTF_8);

            if (first)
                exchange.getResponseHeaders().add("Cache-Control", "max-age=0, stale-if-error=60");

            exchange.sendResponseHeaders(first ? 200 : 503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        UrlFetcher fetcher = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES);
        URI uri = this.baseUri.resolve("/unvalidated");

        Response<String> live = fetcher.get(uri);
        Response<String> replay = fetcher.get(uri);

        assertThat(live.isFromCache(), is(false));
        assertThat(replay.getBody(), is(equalTo("steady")));
        assertThat(replay.isStaleFromCache(), is(true));
        assertThat(conditional, contains(false, false));
    }

    @Test
    @DisplayName("The fetcher's socket timeout bounds a read the origin never answers")
    void socketTimeoutBoundsAStalledRead() throws IOException {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            UrlFetcher fetcher = timedFetcher(60_000L, 500L);
            URI uri = URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/stalled");

            assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> assertThrows(UrlFetchException.Transport.class, () -> fetcher.bytes(uri))
            );
        }
    }

    @Test
    @DisplayName("The fetcher's connect timeout bounds a TLS handshake the origin never answers")
    void connectTimeoutBoundsAStalledHandshake() throws IOException {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            UrlFetcher fetcher = timedFetcher(500L, 60_000L);
            URI uri = URI.create("https://127.0.0.1:" + silent.getLocalPort() + "/stalled");

            assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> assertThrows(UrlFetchException.Transport.class, () -> fetcher.bytes(uri))
            );
        }
    }

    @Test
    @DisplayName("Fetches sent together are admitted no further than the fetcher's rate limit allows")
    void concurrentFetchesAreAdmittedNoFurtherThanTheLimit() throws Exception {
        AtomicInteger counted = new AtomicInteger();
        this.server.createContext("/counted", exchange -> {
            counted.incrementAndGet();
            byte[] body = "counted".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        UrlFetcher fetcher = UrlFetcher.create(
            UrlFetcherConfig.builder(new Gson())
                .withDefaultRateLimit(RateLimit.builder().limit(3).window(60, ChronoUnit.SECONDS).build())
                .build()
        );
        URI uri = this.baseUri.resolve("/counted");
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int round = 0; round < 50; round++) {
                fetcher.getRateLimitManager().clear();
                counted.set(0);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> fetches = new ArrayList<>();

                for (int i = 0; i < threads; i++) {
                    fetches.add(pool.submit(() -> {
                        start.await();

                        try {
                            fetcher.bytes(uri);
                            return true;
                        } catch (UrlFetchException.RateLimited refused) {
                            return false;
                        }
                    }));
                }

                start.countDown();
                long admitted = 0;

                for (Future<Boolean> fetch : fetches) {
                    if (fetch.get())
                        admitted++;
                }

                assertThat("round " + round, admitted, is(3L));
                assertThat("round " + round, counted.get(), is(3));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A header a caller names with the internal prefix reaches the origin")
    void sendsACallersInternalHeader() {
        List<List<String>> tenants = new CopyOnWriteArrayList<>();
        this.server.createContext("/tenant", exchange -> {
            tenants.add(List.copyOf(exchange.getRequestHeaders().getOrDefault("X-Internal-Tenant", List.of())));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        UrlFetcher.create(UrlFetcherConfig.builder(new Gson()).withHeader("X-Internal-Tenant", "t1").build())
            .bytes(this.baseUri.resolve("/tenant"));

        assertThat(tenants, is(equalTo(List.of(List.of("t1")))));
    }

}
