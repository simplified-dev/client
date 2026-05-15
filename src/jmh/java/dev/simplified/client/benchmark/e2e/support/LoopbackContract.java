package dev.simplified.client.benchmark.e2e.support;

import dev.simplified.client.request.Contract;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import dev.simplified.client.route.RouteDiscovery.Metadata;
import feign.RequestLine;

import java.util.Map;

/**
 * Feign contract for the loopback HTTPS benchmark. Pinned to {@link LoopbackHttpsServer#PORT}
 * so {@link Metadata#getFullUrl()} resolves to
 * {@code https://127.0.0.1:47652}.
 */
@Route(LoopbackContract.ROUTE)
public interface LoopbackContract extends Contract {

    /**
     * Route literal matching the fixed loopback server port.
     */
    String ROUTE = "127.0.0.1:47652";

    @RequestLine("GET /small")
    Response<Map<String, Object>> smallJson();

    @RequestLine("GET /cacheable")
    Response<Map<String, Object>> cacheable();

    @RequestLine("GET /error")
    Response<Map<String, Object>> error();

}
