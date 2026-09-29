package dev.simplified.client;

import dev.simplified.client.decoder.ClientErrorDecoder;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import dev.simplified.gson.GsonSettings;
import feign.Headers;
import feign.Logger;
import feign.Request;
import feign.RequestLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a {@link Client}'s configured headers reach the origin and nothing that prints the
 * request: a {@link Client} configured with a static {@code API-Key} and a dynamic
 * {@code Authorization}, sending through the production Apache transport to a
 * {@link LoopbackOrigin} that answers {@code /resource} with a {@code 200} and {@code /missing}
 * with a {@code 404}.
 */
class ClientConfiguredHeadersTest {

    @Route(value = "127.0.0.1", rateLimit = @RateLimitConfig(unlimited = true))
    interface Origin extends Contract {

        @RequestLine("GET /resource")
        @Headers("X-Contract: contract")
        Response<byte[]> resource();

        @RequestLine("GET /missing")
        Response<byte[]> missing();

    }

    private static final String STATIC_SECRET = "static-secret-4f1c";

    private static final String DYNAMIC_SECRET = "Bearer dynamic-secret-9a2e";

    /**
     * The context of the last error response the client's error decoder was handed.
     */
    private final AtomicReference<ErrorContext> failed = new AtomicReference<>();

    private final LoopbackOrigin origin = new LoopbackOrigin((request, exchange) -> LoopbackOrigin.respond(
        exchange,
        request.uri().getPath().equals("/resource") ? 200 : 404,
        "body",
        "Cache-Control", "no-store"
    ));

    private final Client<Origin> client = this.origin.client(
        ClientConfig.builder(Origin.class, GsonSettings.builder().build())
            .withHeader("API-Key", STATIC_SECRET)
            .withDynamicHeader("Authorization", () -> Optional.of(DYNAMIC_SECRET))
            .withErrorDecoder((ClientErrorDecoder) context -> {
                this.failed.set(context);
                return new ApiException(null, "Origin", context);
            })
            .build()
    );

    ClientConfiguredHeadersTest() throws IOException, GeneralSecurityException { }

    @AfterEach
    void stopOrigin() {
        this.origin.close();
    }

    /**
     * Logs a request through a Feign {@link Logger} at {@link Logger.Level#HEADERS}.
     *
     * @param request the request to log
     * @return the lines logged
     */
    private static List<String> logged(Request request) {
        List<String> lines = new ArrayList<>();

        new Logger() {

            {
                this.logRequest("Origin#resource()", Level.HEADERS, request);
            }

            @Override
            protected void log(String configKey, String format, Object... args) {
                lines.add(String.format(format, args));
            }

        };

        return lines;
    }

    /**
     * Asserts that a request reached the origin with the contract's header and both configured
     * values.
     *
     * @param received the request the origin received
     */
    private static void assertSentWithTheConfiguredValues(LoopbackOrigin.Received received) {
        assertThat(received.header("API-Key"), contains(STATIC_SECRET));
        assertThat(received.header("Authorization"), contains(DYNAMIC_SECRET));
    }

    @Test
    @DisplayName("The origin receives the configured headers, and neither the request Feign built, its toString nor a HEADERS log carries their values")
    void feignRequestCarriesNoConfiguredValue() {
        Response<?> response = this.client.getContract().resource();
        Request built = ((Response.Impl<?>) response).getAnchor().request();
        List<String> lines = logged(built);

        assertThat(this.origin.received(), hasSize(1));
        assertSentWithTheConfiguredValues(this.origin.received().getFirst());
        assertThat(this.origin.received().getFirst().header("X-Contract"), contains("contract"));
        assertThat(built.headers(), hasKey("X-Contract"));
        assertThat(built.headers(), not(hasKey("API-Key")));
        assertThat(built.headers(), not(hasKey("Authorization")));
        assertThat(built.toString(), not(containsString(STATIC_SECRET)));
        assertThat(built.toString(), not(containsString(DYNAMIC_SECRET)));
        assertThat(lines, hasItem("X-Contract: contract"));
        assertThat(String.join("\n", lines), not(containsString(STATIC_SECRET)));
        assertThat(String.join("\n", lines), not(containsString(DYNAMIC_SECRET)));
    }

    @Test
    @DisplayName("An error response's ErrorContext carries neither configured value, which the origin received")
    void errorContextCarriesNoConfiguredValue() {
        ApiException raised = assertThrows(ApiException.class, () -> this.client.getContract().missing());
        ErrorContext context = this.failed.get();

        assertThat(raised.getStatusCode(), is(404));
        assertThat(this.origin.received(), hasSize(1));
        assertSentWithTheConfiguredValues(this.origin.received().getFirst());
        assertThat(context.requestHeaders(), not(hasKey("API-Key")));
        assertThat(context.requestHeaders(), not(hasKey("Authorization")));
        assertThat(context.toString(), not(containsString(STATIC_SECRET)));
        assertThat(context.toString(), not(containsString(DYNAMIC_SECRET)));
    }

}
