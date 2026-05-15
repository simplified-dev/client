package dev.simplified.client.springbench;

import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.Route;
import feign.Param;
import feign.RequestLine;
import org.jetbrains.annotations.NotNull;

/**
 * Bench-only Feign contract pointing at the {@link MockMojangServer} on
 * {@code 127.0.0.1:47652}. Mirrors the live Mojang lookup-by-name routes so the
 * controller code looks exactly like a production call.
 *
 * <p>Two methods cover the two scenarios:</p>
 * <ul>
 *   <li>{@link #getPlayer(String)} - cache-miss path. No {@code Cache-Control}
 *       in the mock's response, so every call burns through the full HC5 pool +
 *       TLS + decode pipeline.</li>
 *   <li>{@link #getPlayerCached(String)} - cache-hit path. The mock emits
 *       {@code Cache-Control: public, max-age=60} so repeat calls for the same
 *       username short-circuit at {@link CachingFeignClient}.</li>
 * </ul>
 */
@Route("127.0.0.1:47652")
public interface BenchMojangContract extends Contract {

    /**
     * Looks up a player by username on the cache-miss path.
     *
     * @param username the player username segment of the URL
     * @return the deterministic mock payload for that username
     */
    @RequestLine("GET /minecraft/profile/lookup/name/{username}")
    @NotNull BenchMojangUsername getPlayer(@NotNull @Param("username") String username);

    /**
     * Looks up a player by username on the cache-hit path. The mock emits a 60s
     * {@code Cache-Control} header so repeat calls hit {@code CachingFeignClient}
     * without crossing the network.
     *
     * @param username the player username segment of the URL
     * @return the deterministic mock payload for that username
     */
    @RequestLine("GET /minecraft/profile/lookup/name-cached/{username}")
    @NotNull BenchMojangUsername getPlayerCached(@NotNull @Param("username") String username);

}
