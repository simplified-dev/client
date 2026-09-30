package dev.simplified.client;

import com.sun.net.httpserver.HttpExchange;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.BodyCapExceededException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.fetch.UrlFetcherConfig;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import dev.simplified.gson.GsonSettings;
import feign.Request;
import feign.RequestLine;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests how a {@link Client} holds the bodies it reads to the cap
 * {@link ClientConfig.Builder#withMaxBodyBytes(long)} sets: a {@link Client} built over a scripted
 * transport that answers every {@code GET} with the body a test sets, and one talking to a
 * {@link LoopbackOrigin} through the production Apache transport, whose endless body shows whether
 * the client hung up on it.
 */
class ClientBodyCapTest {

    @Route(value = "127.0.0.1:0", rateLimit = @RateLimitConfig(unlimited = true))
    interface Resource extends Contract {

        @RequestLine("GET /resource")
        Thing typed();

        @RequestLine("GET /resource")
        Response<byte[]> raw();

        @RequestLine("GET /resource")
        InputStream stream();

        @RequestLine("GET /resource")
        void touch();

    }

    /**
     * The body the resource is read into by {@link Resource#typed()}.
     */
    static final class Thing {

        private String name = "";

    }

    /**
     * The exception the client's error decoder raises.
     */
    static final class OriginException extends ApiException {

        OriginException(ErrorContext context) {
            super(null, "Origin", context);
        }

    }

    /**
     * The cap every capped client is held to.
     */
    private static final long CAP = 1024;

    /**
     * The most an {@linkplain #endless endless} body sends, far more than any socket buffers
     * between the origin and the client hold.
     */
    private static final long ENDLESS_BYTES = 64L * 1024 * 1024;

    /**
     * The body every scripted {@code GET} is answered with.
     */
    private final AtomicReference<byte[]> body = new AtomicReference<>(json("scripted"));

    /**
     * The scripted {@code GET} requests answered.
     */
    private final AtomicInteger answered = new AtomicInteger();

    /**
     * The last scripted {@code GET} request answered.
     */
    private final AtomicReference<Request> lastGet = new AtomicReference<>();

    /**
     * Encodes a {@link Thing} named {@code name} as JSON.
     *
     * @param name the name
     * @return the JSON bytes
     */
    private static byte @NotNull [] json(@NotNull String name) {
        return ("{\"name\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes a {@link Thing} whose JSON is exactly {@code length} bytes.
     *
     * @param length the length of the JSON in bytes
     * @return the JSON bytes
     */
    private static byte @NotNull [] jsonOfLength(int length) {
        return json("e".repeat(length - json("").length));
    }

    /**
     * Seeds a configuration for {@link Resource}.
     *
     * @return the builder
     */
    private static ClientConfig.@NotNull Builder<Resource> builder() {
        return ClientConfig.builder(Resource.class, GsonSettings.builder().build());
    }

    /**
     * Builds a client over the scripted transport, held to a cap.
     *
     * @param maxBodyBytes the cap
     * @return the client
     */
    private @NotNull Client<Resource> scripted(long maxBodyBytes) {
        return new Client<>(builder().withMaxBodyBytes(maxBodyBytes).build(), this::answer);
    }

    /**
     * Answers a {@code GET} with the scripted body; answers anything else, the client's connection
     * warm-up, with an empty {@code 200}.
     *
     * @param request the request
     * @param options the request options
     * @return the answer
     */
    private feign.Response answer(Request request, Request.Options options) {
        boolean scripted = request.httpMethod() == Request.HttpMethod.GET;

        if (scripted) {
            this.answered.incrementAndGet();
            this.lastGet.set(request);
        }

        return feign.Response.builder()
            .status(200)
            .reason("OK")
            .request(request)
            .headers(Map.of())
            .body(scripted ? this.body.get() : new byte[0])
            .build();
    }

    /**
     * Answers with {@code status} and a chunked body of {@link #ENDLESS_BYTES}, recording whether
     * the client hung up before the whole body went out.
     *
     * @param exchange the exchange to answer
     * @param status the status to answer with
     * @param hungUp completed with {@code true} if a write failed because the client hung up, or
     *               {@code false} if the whole body went out
     */
    private static void endless(HttpExchange exchange, int status, CompletableFuture<Boolean> hungUp) {
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'e');

        try {
            exchange.sendResponseHeaders(status, 0);

            try (OutputStream out = exchange.getResponseBody()) {
                for (long sent = 0; sent < ENDLESS_BYTES; sent += chunk.length)
                    out.write(chunk);
            }

            hungUp.complete(false);
        } catch (IOException ex) {
            hungUp.complete(true);
        } finally {
            exchange.close();
        }
    }

    @Test
    @DisplayName("A body within the cap decodes, one exactly the cap's size included")
    void bodyWithinTheCapDecodes() {
        Client<Resource> client = this.scripted(CAP);
        this.body.set(json("under"));

        assertThat(client.getContract().typed().name, is(equalTo("under")));

        byte[] exact = jsonOfLength((int) CAP);
        this.body.set(exact);

        assertThat(client.getContract().raw().getBody(), is(exact));
        assertThat(client.getContract().typed().name.length(), is(exact.length - json("").length));
    }

    @Test
    @DisplayName("A body over the cap raises BodyCapExceededException, recorded as the last response, and the origin sees the client hang up on it")
    void bodyOverTheCapRaisesAndHangsUp() throws Exception {
        CompletableFuture<Boolean> hungUp = new CompletableFuture<>();

        try (LoopbackOrigin origin = new LoopbackOrigin((request, exchange) -> endless(exchange, 200, hungUp))) {
            Client<Resource> client = origin.client(builder().withMaxBodyBytes(CAP).build());

            BodyCapExceededException raised = assertThrows(BodyCapExceededException.class, () -> client.getContract().typed());

            assertThat(raised.getMaxBytes(), is(CAP));
            assertThat(raised.getStatus(), is(HttpStatus.IO_ERROR));
            assertThat(raised.getBody().isPresent(), is(false));
            assertThat(raised.getRequest().getMethod(), is(HttpMethod.GET));
            assertThat(client.getLastResponse().orElseThrow(), is(sameInstance(raised)));
            assertThat(hungUp.get(10, TimeUnit.SECONDS), is(true));
        }
    }

    @Test
    @DisplayName("A byte[] body over the cap raises BodyCapExceededException, and the origin sees the client hang up on it")
    void rawBodyOverTheCapRaisesAndHangsUp() throws Exception {
        CompletableFuture<Boolean> hungUp = new CompletableFuture<>();

        try (LoopbackOrigin origin = new LoopbackOrigin((request, exchange) -> endless(exchange, 200, hungUp))) {
            Client<Resource> client = origin.client(builder().withMaxBodyBytes(CAP).build());

            assertThrows(BodyCapExceededException.class, () -> client.getContract().raw());
            assertThat(hungUp.get(10, TimeUnit.SECONDS), is(true));
        }
    }

    @Test
    @DisplayName("An error body over the cap raises its status carrying the body cut at the cap, and the origin sees the client hang up on it")
    void errorBodyOverTheCapIsCut() throws Exception {
        CompletableFuture<Boolean> hungUp = new CompletableFuture<>();

        try (LoopbackOrigin origin = new LoopbackOrigin((request, exchange) -> endless(exchange, 404, hungUp))) {
            Client<Resource> client = origin.client(builder().withMaxBodyBytes(CAP).withErrorDecoder(OriginException::new).build());

            OriginException raised = assertThrows(OriginException.class, () -> client.getContract().typed());

            assertThat(raised.getStatus(), is(HttpStatus.NOT_FOUND));
            assertThat(raised.getBody().orElseThrow().length, is((int) CAP));
            assertThat(hungUp.get(10, TimeUnit.SECONDS), is(true));
        }
    }

    @Test
    @DisplayName("A void method answered with a body over the cap returns, and the origin sees the client hang up on it")
    void voidMethodHangsUpPastTheCap() throws Exception {
        CompletableFuture<Boolean> hungUp = new CompletableFuture<>();

        try (LoopbackOrigin origin = new LoopbackOrigin((request, exchange) -> endless(exchange, 200, hungUp))) {
            Client<Resource> client = origin.client(builder().withMaxBodyBytes(CAP).build());

            assertDoesNotThrow(() -> client.getContract().touch());
            assertThat(hungUp.get(10, TimeUnit.SECONDS), is(true));
        }
    }

    @Test
    @DisplayName("A cached body over the cap raises BodyCapExceededException on a fresh hit without a request being sent, and stays cached")
    void cachedBodyOverTheCapRaises() {
        Client<Resource> client = this.scripted(CAP);
        client.getContract().touch();
        Request sent = this.lastGet.get();
        byte[] large = jsonOfLength((int) CAP * 4);
        String now = Instant.now().toString();
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Cache-Control", List.of("max-age=60"));
        headers.put(NetworkDetails.REQUEST_START, List.of(now));
        headers.put(NetworkDetails.RESPONSE_RECEIVED, List.of(now));
        feign.Response live = feign.Response.builder()
            .status(200)
            .reason("OK")
            .request(sent)
            .headers(headers)
            .body(large)
            .build();
        client.getResponseCache().store(new Response.Impl<>(live, () -> large), large, sent.headers());
        int answeredBefore = this.answered.get();

        BodyCapExceededException typed = assertThrows(BodyCapExceededException.class, () -> client.getContract().typed());
        assertThrows(BodyCapExceededException.class, () -> client.getContract().raw());

        assertThat(typed.getMaxBytes(), is(CAP));
        assertThat(this.answered.get(), is(answeredBefore));
        assertThat(client.getResponseCache().lookup(HttpMethod.GET, sent.url(), sent.headers()).isPresent(), is(true));
    }

    @Test
    @DisplayName("A streaming body is handed over unread and is not held to the cap")
    void streamingBodyIsNotHeldToTheCap() throws IOException {
        byte[] large = jsonOfLength((int) CAP * 4);
        this.body.set(large);

        try (InputStream stream = this.scripted(CAP).getContract().stream()) {
            assertThat(stream.readAllBytes(), is(large));
        }
    }

    @Test
    @DisplayName("A client configured with no cap reads a body larger than the fetcher's default cap whole")
    void noCapReadsALargeBodyWhole() {
        ClientConfig<Resource> config = builder().build();
        Client<Resource> client = new Client<>(config, this::answer);
        byte[] large = jsonOfLength((int) UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES + 1);
        this.body.set(large);

        assertThat(config.getMaxBodyBytes(), is(ClientConfig.DEFAULT_MAX_BODY_BYTES));
        assertThat(client.getContract().raw().getBody().length, is(large.length));
        assertThat(client.getContract().typed().name.length(), is(large.length - json("").length));
    }

    @Test
    @DisplayName("A negative cap is refused, a cap of zero is accepted, and a derived configuration keeps the cap")
    void negativeCapIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> builder().withMaxBodyBytes(-1));

        assertThat(builder().withMaxBodyBytes(0).build().getMaxBodyBytes(), is(0L));
        assertThat(builder().withMaxBodyBytes(CAP).build().mutate().build().getMaxBodyBytes(), is(CAP));
    }

}
