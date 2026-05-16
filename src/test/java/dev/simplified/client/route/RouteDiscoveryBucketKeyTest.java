package dev.simplified.client.route;

import dev.simplified.client.ClientConfig;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class RouteDiscoveryBucketKeyTest {

    @Route(value = "127.0.0.1:0", rateLimit = @RateLimitConfig(unlimited = true))
    interface KeyTestContract extends Contract {
    }

    private static RouteDiscovery discovery(Inet6Address boundIp, int bucketPrefixLength) {
        ClientConfig.Builder<KeyTestContract> builder = ClientConfig.builder(KeyTestContract.class, GsonSettings.builder().build());
        if (boundIp != null) {
            builder.withInet6Address(boundIp);
            if (bucketPrefixLength < IPv6Prefix.IPV6_BITS) {
                builder.withSubnetPrefix(IPv6Prefix.of(boundIp.getAddress(), bucketPrefixLength));
            }
        }
        return new RouteDiscovery(builder.build());
    }

    private static Inet6Address ipv6(String literal) throws UnknownHostException {
        return (Inet6Address) InetAddress.getByName(literal);
    }

    @Test
    @DisplayName("bucketKey is the bare route when no subnet prefix is configured")
    void bareKeyWithoutSubnetPrefix() {
        RouteDiscovery d = discovery(null, 128);
        assertThat(d.getDefaultRoute().getBucketKey(), is("127.0.0.1:0"));
    }

    @Test
    @DisplayName("bucketKey is the bare route when rotation prefix length is 128 (per-IP)")
    void bareKeyAtPerIpPrefix() throws Exception {
        RouteDiscovery d = discovery(ipv6("2602:fa02:0a00:1234::beef"), 128);
        assertThat(d.getDefaultRoute().getBucketKey(), is("127.0.0.1:0"));
    }

    @Test
    @DisplayName("bucketKey composes route + /56 prefix when bound + /56 length")
    void composesAt56() throws Exception {
        Inet6Address ip = ipv6("2602:fa02:0a00:1234::beef");
        RouteDiscovery d = discovery(ip, 56);
        String expected = "127.0.0.1:0@" + IPv6Prefix.of(ip.getAddress(), 56);
        assertThat(d.getDefaultRoute().getBucketKey(), is(expected));
    }

    @Test
    @DisplayName("bucketKey composes route + /48 prefix when bound + /48 length")
    void composesAt48() throws Exception {
        Inet6Address ip = ipv6("2001:470:1abc:dead::1");
        RouteDiscovery d = discovery(ip, 48);
        String expected = "127.0.0.1:0@" + IPv6Prefix.of(ip.getAddress(), 48);
        assertThat(d.getDefaultRoute().getBucketKey(), is(expected));
    }

    @Test
    @DisplayName("Two RouteDiscoveries with addresses in the same /56 produce the same bucketKey")
    void sameSubnetSameKey() throws Exception {
        RouteDiscovery a = discovery(ipv6("2602:fa02:0a00:1234::1"), 56);
        RouteDiscovery b = discovery(ipv6("2602:fa02:0a00:1234::beef"), 56);
        assertThat(b.getDefaultRoute().getBucketKey(), is(a.getDefaultRoute().getBucketKey()));
    }

    @Test
    @DisplayName("Two RouteDiscoveries in different /56s produce distinct bucketKeys")
    void differentSubnetsDistinctKeys() throws Exception {
        RouteDiscovery a = discovery(ipv6("2602:fa02:0a00:1234::1"), 56);
        RouteDiscovery b = discovery(ipv6("2602:fa02:0a01:5678::1"), 56);
        assertThat(b.getDefaultRoute().getBucketKey(), is(not(a.getDefaultRoute().getBucketKey())));
    }

}
