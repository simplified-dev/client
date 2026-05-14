package dev.simplified.client.benchmark.e2e.support;

import feign.Client;
import feign.Request;
import feign.Response;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link feign.Client} that resolves a request to a canned {@link feign.Response}
 * by URL path. Skips the entire Apache HTTP / socket stack so the JMH benchmarks isolate
 * library-level work (interceptors, decoders, route discovery, rate limit, response cache,
 * Gson) from kernel and transport overhead.
 */
public final class CannedFeignClient implements Client {

    private final Map<String, Canned> byPath = new ConcurrentHashMap<>();

    public CannedFeignClient register(@NotNull String path, int status, @NotNull byte[] body, @NotNull Map<String, Collection<String>> headers) {
        this.byPath.put(path, new Canned(status, body, headers));
        return this;
    }

    public CannedFeignClient registerJson(@NotNull String path, int status, @NotNull byte[] body) {
        return this.register(path, status, body, Map.of(
            "Content-Type", List.of("application/json; charset=utf-8"),
            "Content-Length", List.of(Integer.toString(body.length))
        ));
    }

    public CannedFeignClient registerCacheable(@NotNull String path, @NotNull byte[] body) {
        return this.register(path, 200, body, Map.of(
            "Content-Type", List.of("application/json; charset=utf-8"),
            "Content-Length", List.of(Integer.toString(body.length)),
            "Cache-Control", List.of("public, max-age=3600"),
            "ETag", List.of("\"benchmark-cacheable-v1\"")
        ));
    }

    @Override
    public Response execute(Request request, Request.Options options) {
        String path = URI.create(request.url()).getPath();
        Canned canned = this.byPath.getOrDefault(path, Canned.NOT_FOUND);
        return Response.builder()
            .status(canned.status)
            .reason("OK")
            .request(request)
            .headers(canned.headers)
            .body(canned.body)
            .build();
    }

    private record Canned(int status, byte[] body, Map<String, Collection<String>> headers) {

        static final Canned NOT_FOUND = new Canned(404, FixtureBodies.EMPTY, Map.of(
            "Content-Length", List.of("0")
        ));

    }

}
