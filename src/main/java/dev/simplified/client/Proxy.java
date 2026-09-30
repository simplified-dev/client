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
import dev.simplified.client.subnet.pool.ClientPool;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Pool of {@link Client} instances fronting a single {@link Contract} type.
 * <p>
 * With a {@link SubnetRotation}, each {@link #getClient()} picks a subnet bucket with remaining
 * budget (per the configured selection strategy) and returns a client bound to a random address
 * inside it. Without one, every call returns the same client, sending from the host's default
 * source address. Every client shares one {@link RateLimitManager}, and a call no client can serve
 * throws {@link RateLimitException}.
 * <p>
 * Because {@code Proxy} implements {@link AsyncAccess}, it stands in for a {@link Client} anywhere
 * an {@code AsyncAccess<C>} is accepted.
 *
 * @param <C> the {@link Contract} interface type that the underlying clients target
 * @see Client
 * @see ClientConfig
 * @see SubnetRotation
 * @see ClientPool
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Proxy<C extends Contract> implements AsyncAccess<C> {

    /**
     * The shared base options every client in the pool derives from.
     */
    private final @NotNull ClientConfig<C> baseOptions;

    /**
     * The rotation the clients are spread across, empty for a proxy sending from the host's
     * default source address.
     */
    private final @NotNull Optional<SubnetRotation> rotation;

    /**
     * The pool that selects the client each call is served through.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull ClientPool<C> pool;

    /**
     * Returns a new {@link Builder} that produces proxies pooling clients derived from the given
     * base options.
     * <p>
     * The default {@linkplain Builder#withPerClientMutator per-client mutator} is the identity
     * operator (no per-client variance beyond the address a rotation binds) and the default
     * {@linkplain Builder#withAvailability availability predicate} treats a client as
     * available when {@link Client#isRateLimited()} returns {@code false}, which checks the
     * type-level {@link Route @Route} bucket. Single-domain endpoints can rely
     * on the default predicate; multi-domain endpoints should override it to target the relevant
     * bucket.
     * <p>
     * A proxy built with a {@linkplain Builder#withSubnetRotation(SubnetRotation) rotation} spreads
     * its clients across the subnets it names; one built without serves every call through one
     * client sending from the host's default source address.
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
     * Each call resolves a client via {@link #getClient()}. With a rotation the returned contract
     * reflects the selection at the moment of the call; without one it is always the contract of
     * the proxy's one client.
     *
     * @return the contract proxy of the selected client
     * @throws RateLimitException if no client of the pool can serve a request, or if the one subnet
     *     bucket a rotation spreading its clients over several chose was spent between the pool's
     *     saturation check and its selection - naming that bucket's subnet, while another bucket
     *     may serve a retry
     */
    @Override
    public @NotNull C getContract() {
        return this.getClient().getContract();
    }

    /**
     * Selects a client able to serve a request.
     * <p>
     * With a rotation, the client is bound to a random address inside a subnet bucket with
     * remaining budget. Without one, it is the proxy's one client, built on the first call and
     * returned by every later one.
     *
     * @return an available client
     * @throws RateLimitException if no client of the pool can serve a request, or if the one subnet
     *     bucket a rotation spreading its clients over several chose was spent between the pool's
     *     saturation check and its selection - naming that bucket's subnet, while another bucket
     *     may serve a retry
     */
    public @NotNull Client<C> getClient() {
        return this.pool.selectClient();
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
        private @NotNull Optional<SubnetRotation> rotation = Optional.empty();

        private Builder(@NotNull ClientConfig<C> baseOptions) {
            this.baseOptions = baseOptions;
        }

        /**
         * Sets the operator applied to the base options builder when constructing a new client.
         * <p>
         * The operator receives a fresh {@link ClientConfig.Builder} seeded from the base
         * options each time the pool builds a client: once per subnet bucket for a proxy with a
         * rotation, once in all for a proxy without one.
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
         *   <li>source == bucket -&gt; single bucket, one client at a random address inside it</li>
         *   <li>source &gt; bucket -&gt; pass-through (rotation gains nothing)</li>
         * </ul>
         *
         * <h4>Hurricane Electric IPv6 Tunnel Setup (example /48 provider)</h4>
         * <ol>
         *     <li>Go to <a href="https://tunnelbroker.net/">TunnelBroker</a></li>
         *     <li>Create an account or login</li>
         *     <li>Click on Create Regular Tunnel
         *     <ul>
         *         <li>Enter ipv4 address of your server
         *         <ul>
         *             <li>If it gives an error, use the pingable IP of nginx.com</li>
         *         </ul>
         *         </li>
         *         <li>Select an origin city for your tunnel</li>
         *         <li>Click Create</li>
         *     </ul>
         *     </li>
         *     <li>Click on your tunnel name
         *     <ul>
         *         <li>If you entered the nginx.com IP, change it to the ipv4 address of your server</li>
         *     </ul>
         *     </li>
         *     <li>Click on Generate /48</li>
         * </ol>
         *
         * <h4>Variables</h4>
         * <pre><code>
         * SERVER_IPV4 = Server IPv4 Address
         * CLIENT_IPV4 = Client IPv4 Address
         * CLIENT_IPV6 = Client IPv6 Address
         * ROUTED_48   = Routed /48 prefix
         * </code></pre>
         *
         * <h4>Create Routing Table</h4>
         * <pre><code>
         * grep -q '^100 he' /etc/iproute2/rt_tables || echo "100 he" &gt;&gt; /etc/iproute2/rt_tables
         * </code></pre>
         *
         * <h4>Enable IPv6 Non-Local Binding &amp; Forwarding and TCP Optimizations</h4>
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
         * <h4>Enable Non-Local IPv6 Binding</h4>
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
         * <h4>Launch Service</h4>
         * <pre><code>
         * systemctl daemon-reload
         * systemctl enable he-ipv6
         * systemctl start he-ipv6
         * </code></pre>
         *
         * <h4>JVM Requirement</h4>
         * <p>The JVM must be started with {@code -Djava.net.preferIPv6Addresses=true}.
         * Without this, Java resolves hostnames to IPv4 addresses first, and an IPv6-bound
         * socket cannot connect to an IPv4 destination ({@code Network unreachable}).
         *
         * @param rotation the rotation configuration
         * @return this builder
         */
        public @NotNull Builder<C> withSubnetRotation(@NotNull SubnetRotation rotation) {
            return this.withSubnetRotation(Optional.of(rotation));
        }

        /**
         * Sets the rotation from an {@link Optional}; an empty rotation builds a proxy over one
         * client sending from the host's default source address.
         *
         * @param rotation the rotation configuration, or empty for none
         * @return this builder
         * @see #withSubnetRotation(SubnetRotation)
         */
        public @NotNull Builder<C> withSubnetRotation(@NotNull Optional<SubnetRotation> rotation) {
            this.rotation = rotation;
            return this;
        }

        /**
         * Constructs an immutable {@link Proxy} from the current builder state. Builds no client
         * and opens no connection; the first {@link Proxy#getClient()} does.
         *
         * @return a new {@code Proxy}
         * @throws IllegalArgumentException if the contract declares no type-level route
         */
        public @NotNull Proxy<C> build() {
            RateLimitManager sharedManager = new RateLimitManager();
            RouteDiscovery.Metadata anchorRoute = new RouteDiscovery(this.baseOptions).getDefaultRoute();

            // Every client the pool builds carries the one shared manager, so all of them count
            // against one tracker. The mutator is read once here, so a later call on this builder
            // does not reach the built proxy. A rotating pool binds the subnet prefix and address
            // after this mutator when it builds a client.
            UnaryOperator<ClientConfig.Builder<C>> mutator = this.perClientMutator;
            UnaryOperator<ClientConfig.Builder<C>> sharing = builder -> mutator.apply(builder)
                .withRateLimitManager(sharedManager);

            ClientPool<C> pool = ClientPool.create(
                this.rotation,
                sharedManager,
                anchorRoute,
                this.baseOptions,
                sharing,
                this.availability
            );
            return new Proxy<>(this.baseOptions, this.rotation, pool);
        }

    }

}
