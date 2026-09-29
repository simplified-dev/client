package dev.simplified.client;

import dev.simplified.client.exception.ApiDecodeException;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import dev.simplified.gson.GsonSettings;
import feign.Request;
import feign.RequestLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a {@link Client}'s configured headers reach its response cache as they reach the
 * origin: a {@link Client} built over a scripted transport, sending the static {@code Accept} and
 * the dynamic {@code Authorization} header a GitHub client sends, answered with GitHub's
 * {@code Vary}.
 */
class ClientVaryTest {

    @Route(value = "127.0.0.1:0", rateLimit = @RateLimitConfig(unlimited = true))
    interface Repository extends Contract {

        @RequestLine("GET /repos/owner/repo/commits/main")
        Response<byte[]> tip();

        @RequestLine("GET /repos/owner/repo/commits/validated")
        Response<byte[]> validated();

        @RequestLine("GET /repos/owner/repo/commits/malformed")
        Response<Commit> malformed();

    }

    /**
     * The body {@code /malformed} is read into.
     */
    static final class Commit {

        private String sha = "";

    }

    private static final String ACCEPT = "application/vnd.github+json";

    /**
     * Every {@code GET} that reached the transport, in the order it was sent.
     */
    private final List<Request> sent = new CopyOnWriteArrayList<>();

    /**
     * The {@code Authorization} value the client's dynamic header supplies.
     */
    private final AtomicReference<String> token = new AtomicReference<>("Bearer one");

    private final Client<Repository> client = new Client<>(
        ClientConfig.builder(Repository.class, GsonSettings.builder().build())
            .withHeader("Accept", ACCEPT)
            .withDynamicHeader("Authorization", () -> Optional.of(this.token.get()))
            .build(),
        this::answer
    );

    /**
     * Answers a {@code GET} with its own {@code Authorization} value as the body, fresh for a
     * minute and varying on {@code Accept} and {@code Authorization}; answers anything else, the
     * client's connection warm-up, with an empty {@code 200}. {@code /validated} is answered stale
     * on arrival under an {@code ETag}, and a request carrying it with a {@code 304} fresh for a
     * minute; {@code /malformed} is answered with a body that does not decode into a
     * {@link Commit}.
     *
     * @param request the request
     * @param options the request options
     * @return the answer
     */
    private feign.Response answer(Request request, Request.Options options) {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byte[] body = new byte[0];
        int status = 200;

        if (request.httpMethod() == Request.HttpMethod.GET) {
            this.sent.add(request);
            headers.put("Vary", List.of("Accept, Authorization"));
            headers.put("Cache-Control", List.of("private, max-age=60"));
            body = String.join(",", request.headers().getOrDefault("Authorization", List.of())).getBytes(StandardCharsets.UTF_8);

            if (request.url().endsWith("/validated")) {
                headers.put("ETag", List.of("\"v1\""));

                if (request.headers().containsKey("If-None-Match")) {
                    status = 304;
                    body = new byte[0];
                } else
                    headers.put("Cache-Control", List.of("private, max-age=0"));
            }

            if (request.url().endsWith("/malformed"))
                body = "[]".getBytes(StandardCharsets.UTF_8);
        }

        return feign.Response.builder()
            .status(status)
            .reason("OK")
            .request(request)
            .headers(headers)
            .body(body)
            .build();
    }

    @Test
    @DisplayName("A response varying on the client's Accept and Authorization is replayed while both hold, and not once the token changes")
    void variantFollowsTheClientHeaders() {
        Repository repository = this.client.getContract();

        Response<byte[]> live = repository.tip();
        Response<byte[]> replay = repository.tip();

        this.token.set("Bearer two");
        Response<byte[]> rotated = repository.tip();
        Response<byte[]> rotatedReplay = repository.tip();

        this.token.set("Bearer one");
        Response<byte[]> restored = repository.tip();

        assertThat(live.isFromCache(), is(false));
        assertThat(replay.isFromCache(), is(true));
        assertThat(rotated.isFromCache(), is(false));
        assertThat(new String(rotated.getBody(), StandardCharsets.UTF_8), is("Bearer two"));
        assertThat(rotatedReplay.isFromCache(), is(true));
        assertThat(restored.isFromCache(), is(true));
        assertThat(new String(restored.getBody(), StandardCharsets.UTF_8), is("Bearer one"));
        assertThat(this.sent, hasSize(2));
        assertThat(this.sent.getFirst().headers().get("Accept"), contains(ACCEPT));
        assertThat(this.sent.getFirst().headers().get("Authorization"), contains("Bearer one"));
        assertThat(this.sent.getLast().headers().get("Accept"), contains(ACCEPT));
        assertThat(this.sent.getLast().headers().get("Authorization"), contains("Bearer two"));
    }

    @Test
    @DisplayName("A 304 refreshes the variant the client's headers select, so the next request is answered fresh")
    void notModifiedRefreshesTheVariantTheClientHeadersSelect() {
        Repository repository = this.client.getContract();

        Response<byte[]> live = repository.validated();
        Response<byte[]> revalidated = repository.validated();
        Response<byte[]> fresh = repository.validated();

        assertThat(live.isFromCache(), is(false));
        assertThat(revalidated.isFromCache(), is(true));
        assertThat(fresh.isFromCache(), is(true));
        assertThat(new String(fresh.getBody(), StandardCharsets.UTF_8), is("Bearer one"));
        assertThat(this.sent, hasSize(2));
        assertThat(this.sent.getLast().headers().get("If-None-Match"), contains("\"v1\""));
        assertThat(this.sent.getLast().headers().get("Authorization"), contains("Bearer one"));
    }

    @Test
    @DisplayName("A body that fails to decode is discarded from the variant the client's headers select, so the next request reaches the origin")
    void undecodableBodyIsDiscardedFromTheVariantTheClientHeadersSelect() {
        Repository repository = this.client.getContract();

        Response<Commit> live = repository.malformed();
        assertThrows(ApiDecodeException.class, live::getBody);
        Response<Commit> next = repository.malformed();

        assertThat(next.isFromCache(), is(false));
        assertThat(this.sent, hasSize(2));
    }

}
