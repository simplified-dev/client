package dev.simplified.client.springbench;

import dev.simplified.client.cache.CachingFeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exercising {@link BenchMojangContract} from a Spring MVC
 * handler thread. The two endpoints map one-to-one to the contract's
 * cache-miss and cache-hit methods so k6 (or wrk) can target either path
 * without route-level changes.
 *
 * <p>Each handler does the absolute minimum so the measurement reflects
 * the library's overhead, not the controller's: receive the path variable,
 * call the contract, return the deserialized record (Spring serializes it
 * back to JSON via Jackson).</p>
 */
@RestController
@RequestMapping("/mojang/user")
public class MojangController {

    private final BenchMojangContract contract;

    public MojangController(BenchMojangContract contract) {
        this.contract = contract;
    }

    /**
     * Cache-miss path. Repeat calls for the same username still go through
     * the full HC5 round-trip because the mock omits {@code Cache-Control}.
     *
     * @param username the player username
     * @return the deserialized mock payload
     */
    @GetMapping("/{username}")
    public BenchMojangUsername getPlayer(@PathVariable("username") String username) {
        return this.contract.getPlayer(username);
    }

    /**
     * Cache-hit path. Repeat calls for the same username short-circuit at
     * {@link CachingFeignClient}.
     *
     * @param username the player username
     * @return the deserialized payload (cached after the first call)
     */
    @GetMapping("/{username}/cached")
    public BenchMojangUsername getPlayerCached(@PathVariable("username") String username) {
        return this.contract.getPlayerCached(username);
    }

}
