package dev.simplified.client;

import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.Route;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests that a {@link Proxy} builds without a {@link SubnetRotation} and then serves every call
 * through one client, that a built proxy keeps the per-client mutator it was built with, and that a
 * rotation given through either overload is the one it holds.
 */
class ProxyTest {

    @Route(value = "127.0.0.1:0", rateLimit = @RateLimitConfig(unlimited = true))
    interface Resource extends Contract {
    }

    private static ClientConfig<Resource> baseOptions() {
        return ClientConfig.builder(Resource.class, GsonSettings.builder().build()).build();
    }

    @Test
    @DisplayName("A proxy without a rotation builds, holds no rotation, and returns one client")
    void buildsWithoutRotation() {
        Proxy<Resource> proxy = Proxy.builder(baseOptions()).build();

        assertThat(proxy.getRotation().isEmpty(), is(true));

        Client<Resource> first = proxy.getClient();
        for (int i = 0; i < 10; i++)
            assertThat(proxy.getClient(), is(sameInstance(first)));
    }

    @Test
    @DisplayName("A per-client mutator set on the builder after build() does not reach the built proxy's client")
    void builtProxyIgnoresLaterMutator() {
        Proxy.Builder<Resource> builder = Proxy.builder(baseOptions());
        Proxy<Resource> proxy = builder.build();
        AtomicInteger applied = new AtomicInteger();

        builder.withPerClientMutator(options -> {
            applied.incrementAndGet();
            return options;
        });
        proxy.getClient();

        assertThat(applied.get(), is(0));
    }

    @Test
    @DisplayName("A rotation given directly or as an Optional is the one the proxy holds")
    void holdsGivenRotation() {
        SubnetRotation rotation = SubnetRotation.builder()
            .sourcePrefix("2001:db8::/56")
            .bucketPrefixLength(56)
            .build();

        assertThat(Proxy.builder(baseOptions()).withSubnetRotation(rotation).build().getRotation(), is(Optional.of(rotation)));
        assertThat(Proxy.builder(baseOptions()).withSubnetRotation(Optional.of(rotation)).build().getRotation(), is(Optional.of(rotation)));
        assertThat(Proxy.builder(baseOptions()).withSubnetRotation(rotation).withSubnetRotation(Optional.empty()).build().getRotation().isEmpty(), is(true));
    }

}
