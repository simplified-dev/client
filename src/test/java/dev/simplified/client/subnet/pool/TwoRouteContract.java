package dev.simplified.client.subnet.pool;

import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.Route;
import feign.RequestLine;

/**
 * {@link Contract} of two routes under different policies, used by subnet-package tests to check
 * which policy a pool's refusal carries. Both routes point at a disconnected localhost port; no
 * request is sent.
 */
@Route(value = TwoRoutes.ANCHOR, rateLimit = @RateLimitConfig(limit = TwoRoutes.ANCHOR_LIMIT, window = TwoRoutes.ANCHOR_WINDOW))
interface TwoRouteContract extends Contract {

    @Route(value = TwoRoutes.SECOND, rateLimit = @RateLimitConfig(limit = TwoRoutes.SECOND_LIMIT, window = TwoRoutes.SECOND_WINDOW))
    @RequestLine("GET /second")
    String second();

}
