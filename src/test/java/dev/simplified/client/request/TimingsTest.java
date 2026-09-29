package dev.simplified.client.request;

import dev.simplified.client.cache.ResponseCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class TimingsTest {

    @Test
    @DisplayName("The defaults and the nine-parameter constructor keep a validated cache entry for the default stale retention")
    void staleRetentionDefaults() {
        Timings defaults = Timings.createDefault();
        Timings nine = new Timings(
            defaults.connectionTimeToLive(),
            defaults.connectionIdleTimeout(),
            defaults.connectionKeepAlive(),
            defaults.connectTimeout(),
            defaults.socketTimeout(),
            defaults.maxConnections(),
            defaults.maxConnectionsPerRoute(),
            defaults.maxCacheBytes(),
            defaults.cacheSafetyFallback()
        );

        assertThat(defaults.cacheStaleRetention(), is(ResponseCache.DEFAULT_STALE_RETENTION_MILLIS));
        assertThat(ResponseCache.DEFAULT_STALE_RETENTION_MILLIS, is(3_600_000L));
        assertThat(nine, is(defaults));
    }

}
