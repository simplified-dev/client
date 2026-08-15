package dev.simplified.client;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.AsyncAccess;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.DynamicRouteProvider;
import dev.simplified.client.route.Route;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.client.subnet.pool.SubnetBucketPool;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Subnet-aware IPv6 source-address rotator for a single {@link Contract} type.
 * <p>
 * A {@code Proxy} owns a {@link SubnetBucketPool} configured via
 * {@link SubnetRotation}. Each call to {@link #getClient()} picks a bucket
 * with remaining budget (per the configured selection strategy) and returns
 * an available {@link Client} bound to a random address within that bucket's
 * subnet. When all buckets are saturated, the call throws
 * {@link RateLimitException}.
 * <p>
 * The bucket layer is internal - callers see only {@link Client} instances.
 * Because {@code Proxy} implements {@link AsyncAccess}, it is a drop-in
 * replacement for {@link Client} anywhere an {@code AsyncAccess<E>} is
 * accepted.
 *
 * @param <C> the {@link Contract} interface type that the underlying clients target
 * @see Client
 * @see ClientConfig
 * @see SubnetRotation
 * @see SubnetBucketPool
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Proxy<C extends Contract> implements AsyncAccess<C> {

    /**
     * The shared base options every client in the pool derives from.
     */
    private final @NotNull ClientConfig<C> baseOptions;

    /**
     * The rotation configuration backing this proxy.
     */
    private final @NotNull SubnetRotation rotation;

    /**
     * The bucket pool that selects subnets and spawns clients.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull SubnetBucketPool<C> bucketPool;

    /**
     * Returns a new {@link Builder} that produces proxies pooling clients derived from the given
     * base options.
     * <p>
     * The default {@linkplain Builder#withPerClientMutator per-client mutator} is the identity
     * operator (no additional per-client variance beyond the bucket's address binding) and the
     * default {@linkplain Builder#withAvailability availability predicate} treats a client as
     * available when {@link Client#isRateLimited()} returns {@code false}, which checks the
     * type-level {@link Route @Route} bucket. Single-domain endpoints can rely
     * on the default predicate; multi-domain endpoints should override it to target the relevant
     * bucket.
     * <p>
     * The {@linkplain Builder#withSubnetRotation rotation} must be set before {@link Builder#build()}.
     *
     * @param <C> the contract interface type
     * @param baseOptions the shared base options
     * @return a builder pre-populated with default behaviors
     */
    public static <C extends Contract> @NotNull Builder<C> builder(@NotNull ClientConfig<C> baseOptions) {
        return new Builder<>(baseOptions);
    }

    /**
     * Returns the synchronous Feign-generated contract proxy of the currently selected client.
     * <p>
     * Each call resolves a fresh client via {@link #getClient()}; the returned contract reflects
     * the selection at the moment of the call.
     *
     * @return the contract proxy of the selected client
     * @throws RateLimitException if all buckets are saturated
     */
    @Override
    public @NotNull C getContract() {
        return this.getClient().getContract();
    }

    /**
     * Selects an available client through the bucket pool.
     *
     * @return an available client, never {@code null}
     * @throws RateLimitException if all buckets are saturated
     */
    public @NotNull Client<C> getClient() {
        return this.bucketPool.selectClient();
    }

    /**
     * Fluent builder for constructing {@link Proxy} instances.
     *
     * @param <C> the contract interface type
     */
    public static final class Builder<C extends Contract> {

        private final @NotNull ClientConfig<C> baseOptions;
        private @NotNull UnaryOperator<ClientConfig.Builder<C>> perClientMutator = UnaryOperator.identity();
        private @NotNull Predicate<Client<C>> availability = client -> !client.isRateLimited();
        private SubnetRotation rotation;

        private Builder(@NotNull ClientConfig<C> baseOptions) {
            this.baseOptions = baseOptions;
        }

        /**
         * Sets the operator applied to the base options builder when constructing a new client.
         * <p>
         * The operator receives a fresh {@link ClientConfig.Builder} seeded from the base
         * options on each call to {@link #getClient()} that needs to add a new pool member.
         * The bucket's random source-address binding is applied <em>after</em> this mutator,
         * so the mutator cannot override the address selected by the rotation layer.
         * <p>
         * Default: {@link UnaryOperator#identity()} - no extra per-client variance.
         *
         * @param mutator the per-client options mutator
         * @return this builder
         */
        public @NotNull Builder<C> withPerClientMutator(@NotNull UnaryOperator<ClientConfig.Builder<C>> mutator) {
            this.perClientMutator = mutator;
            return this;
        }

        /**
         * Sets the predicate used to determine whether a pooled client is available to serve a
         * request.
         * <p>
         * Default: {@code client -> !client.isRateLimited()}, which checks the type-level
         * {@link Route @Route} bucket via the no-arg
         * {@link Client#isRateLimited()}. Override this for multi-domain endpoints where the
         * relevant bucket is identified by a specific
         * {@link DynamicRouteProvider}.
         *
         * @param predicate the availability predicate
         * @return this builder
         */
        public @NotNull Builder<C> withAvailability(@NotNull Predicate<Client<C>> predicate) {
            this.availability = predicate;
            return this;
        }

        /**
         * Configures the rotation strategy for this proxy.
         * <p>
         * The {@link SubnetRotation} declares the source prefix, the rate-limit-relevant subnet
         * size, the per-bucket budget, and the selection strategy. The bucket pool implementation
         * is chosen automatically from the relationship between source prefix length and bucket
         * prefix length:
         * <ul>
         *   <li>source &lt; bucket -&gt; fan-out across all contained subnets</li>
         *   <li>source == bucket -&gt; single bucket, address-only rotation</li>
         *   <li>source &gt; bucket -&gt; pass-through (rotation gains nothing)</li>
         * </ul>
         *
         * <h5>Hurricane Electric IPv6 Tunnel Setup (example /48 provider)</h5>
         * <ol>
         *     <li>Go to <a href="https://tunnelbroker.net/">TunnelBroker</a></li>
         *     <li>Create an account or login</li>
         *     <li>Click on Create Regular Tunnel</li>
         *     <ul>
         *         <li>Enter ipv4 address of your server</li>
         *         <ul>
         *             <li>If it gives an error, use the pingable IP of nginx.com</li>
         *         </ul>
         *         <li>Select an origin city for your tunnel</li>
         *         <li>Click Create</li>
         *     </ul>
         *     <li>Click on your tunnel name</li>
         *     <ul>
         *         <li>If you entered the nginx.com IP, change it to the ipv4 address of your server</li>
         *     </ul>
         *     <li>Click on Generate /48</li>
         * </ol>
         *
         * <h5>Variables</h5>
         * <pre><code>
         * SERVER_IPV4 = Server IPv4 Address
         * CLIENT_IPV4 = Client IPv4 Address
         * CLIENT_IPV6 = Client IPv6 Address
         * ROUTED_48   = Routed /48 prefix
         * </code></pre>
         *
         * <h5>Create Routing Table</h5>
         * <pre><code>
         * grep -q '^100 he' /etc/iproute2/rt_tables || echo "100 he" &gt;&gt; /etc/iproute2/rt_tables
         * </code></pre>
         *
         * <h5>Enable IPv6 Non-Local Binding &amp; Forwarding and TCP Optimizations</h5>
         * <pre><code>
         * cat &gt; /etc/sysctl.d/99-he-tunnel.conf &lt;&lt; 'EOF'
         * # Enable nonlocal bind
         * net.ipv6.ip_nonlocal_bind = 1
         *
         * # Enable ipv6 forwarding
         * net.ipv6.conf.all.forwarding = 1
         *
         * # Enable tcp optimizations
         * net.ipv4.tcp_fastopen = 3
         * net.core.default_qdisc = fq
         * net.ipv4.tcp_congestion_control = bbr
         * net.ipv4.tcp_slow_start_after_idle = 0
         * EOF
         * sysctl -p /etc/sysctl.d/99-he-tunnel.conf
         * </code></pre>
         *
         * <h5>Enable Non-Local IPv6 Binding</h5>
         * <pre><code>
         * cat &gt; /etc/systemd/system/he-ipv6.service &lt;&lt; 'EOF'
         * [Unit]
         * Description=Hurricane Electric IPv6 Tunnel
         * After=network-online.target
         * Wants=network-online.target
         *
         * [Service]
         * Type=oneshot
         * RemainAfterExit=yes
         *
         * ExecStart=/usr/sbin/modprobe ipv6
         * ExecStart=/usr/sbin/modprobe sit
         * ExecStart=/usr/sbin/ip tunnel add he-ipv6 mode sit remote SERVER_IPV4 local CLIENT_IPV4 ttl 255
         * ExecStart=/usr/sbin/ip link set he-ipv6 up
         * ExecStart=/usr/sbin/ip link set he-ipv6 mtu 1480
         * ExecStart=/usr/sbin/ip -6 addr add CLIENT_IPV6 dev he-ipv6
         * ExecStart=/usr/sbin/ip -6 addr add ROUTED_48::2/48 dev he-ipv6
         * ExecStart=/usr/sbin/ip -6 route add local ROUTED_48::/48 dev lo
         * ExecStart=/usr/sbin/ip -6 route add default dev he-ipv6 table he
         * ExecStart=/usr/sbin/ip -6 rule add pref 1000 from ROUTED_48::/48 lookup he
         *
         * ExecStop=/usr/sbin/ip -6 rule del pref 1000 from ROUTED_48::/48 lookup he
         * ExecStop=/usr/sbin/ip tunnel del he-ipv6
         *
         * [Install]
         * WantedBy=multi-user.target
         * EOF
         * </code></pre>
         *
         * <h5>Launch Service</h5>
         * <pre><code>
         * systemctl daemon-reload
         * systemctl enable he-ipv6
         * systemctl start he-ipv6
         * </code></pre>
         *
         * <h5>JVM Requirement</h5>
         * <p>The JVM must be started with {@code -Djava.net.preferIPv6Addresses=true}.
         * Without this, Java resolves hostnames to IPv4 addresses first, and an IPv6-bound
         * socket cannot connect to an IPv4 destination ({@code Network unreachable}).
         *
         * @param rotation the rotation configuration
         * @return this builder
         */
        public @NotNull Builder<C> withSubnetRotation(@NotNull SubnetRotation rotation) {
            this.rotation = rotation;
            return this;
        }

        /**
         * Constructs an immutable {@link Proxy} from the current builder state.
         *
         * @return a new {@code Proxy}
         * @throws IllegalStateException if {@link #withSubnetRotation(SubnetRotation)} was not called
         */
        public @NotNull Proxy<C> build() {
            if (this.rotation == null)
                throw new IllegalStateException("withSubnetRotation must be set");

            RateLimitManager sharedManager = new RateLimitManager();
            String anchorRouteId = new RouteDiscovery(this.baseOptions)
                .getDefaultRoute()
                .getRoute();

            // Inject the shared manager into every spawned client's config so all clients
            // aggregate against one tracker. The per-bucket subnet IPv6Prefix is injected by
            // SubnetBucket.createClient when it actually spawns a client - it has the canonical
            // prefix instance and passes it via withSubnetPrefix.
            UnaryOperator<ClientConfig.Builder<C>> wrappedMutator = builder -> this.perClientMutator
                .apply(builder)
                .withRateLimitManager(sharedManager);

            SubnetBucketPool<C> pool = SubnetBucketPool.create(
                this.rotation,
                sharedManager,
                anchorRouteId,
                this.baseOptions,
                wrappedMutator,
                this.availability
            );
            return new Proxy<>(this.baseOptions, this.rotation, pool);
        }

    }

}
