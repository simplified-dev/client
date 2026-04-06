package dev.simplified.client;

import dev.simplified.client.request.AsyncAccess;
import dev.simplified.client.request.Contract;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generic pool of {@link Client} instances that fronts a single contract type with availability
 * filtering and per-client configuration variance.
 * <p>
 * A {@code Proxy} wraps a list of clients all sharing a common {@link ClientOptions} base. When a
 * caller asks for the contract proxy, the proxy selects the first available client - typically the
 * first one whose rate-limit bucket is not exhausted - and falls back to constructing a new client
 * (mutated by an optional {@link UnaryOperator}) when none of the existing pool members are
 * available. This generalizes the IPv6-rotation pattern used to avoid per-IP rate limits, while
 * supporting arbitrary per-client configuration variance via {@link Builder#withPerClientMutator}.
 * <p>
 * Because {@code Proxy} implements {@link AsyncAccess}, it is a drop-in replacement for
 * {@link Client} anywhere an {@code AsyncAccess<E>} is accepted - including the asynchronous
 * {@code fromBlocking} and {@code fluxFromBlocking} default methods.
 *
 * @param <C> the {@link Contract} interface type that the underlying clients target
 * @see Client
 * @see ClientOptions
 * @see AsyncAccess
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Proxy<C extends Contract> implements AsyncAccess<C> {

    /** The shared base options every client in the pool derives from. */
    private final @NotNull ClientOptions<C> baseOptions;

    /** Operator applied to the base options builder when constructing a new client. */
    @Getter(AccessLevel.NONE)
    private final @NotNull UnaryOperator<ClientOptions.Builder<C>> perClientMutator;

    /** Predicate that determines whether a pooled client is currently available to serve a request. */
    @Getter(AccessLevel.NONE)
    private final @NotNull Predicate<Client<C>> availability;

    /** The lazily populated pool of clients. Grows when no existing client is available. */
    @Getter(AccessLevel.NONE)
    private final @NotNull ConcurrentList<Client<C>> clients = Concurrent.newList();

    /**
     * Returns a new {@link Builder} that produces proxies pooling clients derived from the given
     * base options.
     * <p>
     * The default {@linkplain Builder#withPerClientMutator per-client mutator} is the identity
     * operator (every client is an identical clone of the base options) and the default
     * {@linkplain Builder#withAvailability availability predicate} treats a client as available
     * when {@link Client#isRateLimited()} returns {@code false}, which checks the type-level
     * {@link dev.simplified.client.route.Route @Route} bucket. Single-domain endpoints can rely
     * on the default predicate; multi-domain endpoints should override it to target the relevant
     * bucket.
     *
     * @param <C> the contract interface type
     * @param baseOptions the shared base options
     * @return a builder pre-populated with default behaviors
     */
    public static <C extends Contract> @NotNull Builder<C> builder(@NotNull ClientOptions<C> baseOptions) {
        return new Builder<>(baseOptions);
    }

    /**
     * Returns the synchronous Feign-generated contract proxy of the currently selected client.
     * <p>
     * Each call resolves a fresh client via {@link #getClient()}; the returned contract reflects
     * the selection at the moment of the call.
     *
     * @return the contract proxy of the selected client
     */
    @Override
    public @NotNull C getContract() {
        return this.getClient().getContract();
    }

    /**
     * Selects an available client from the pool, or constructs a new one if none are available.
     * <p>
     * Construction order:
     * <ol>
     *   <li>If the pool is empty, a first client is constructed eagerly.</li>
     *   <li>The first pooled client passing the availability predicate is returned.</li>
     *   <li>If no pooled client is available, a new client is constructed and added to the pool.</li>
     *   <li>If the new client cannot be added (concurrent modification), the first pooled client
     *       is returned as a final fallback so callers always receive a usable instance.</li>
     * </ol>
     *
     * @return an available client, never {@code null}
     */
    public @NotNull Client<C> getClient() {
        if (this.clients.isEmpty())
            this.clients.add(this.createClient());

        return this.clients.stream()
            .filter(this.availability)
            .findFirst()
            .or(() -> Optional.of(this.createClient()))
            .filter(this.clients::add)
            .orElse(this.clients.getFirst());
    }

    /**
     * Constructs a new {@link Client} from the base options after applying the per-client mutator.
     *
     * @return a freshly built client
     */
    private @NotNull Client<C> createClient() {
        return Client.create(this.perClientMutator.apply(this.baseOptions.mutate()).build());
    }

    /**
     * Fluent builder for constructing {@link Proxy} instances.
     *
     * @param <C> the contract interface type
     */
    public static final class Builder<C extends Contract> {

        private final @NotNull ClientOptions<C> baseOptions;
        private @NotNull UnaryOperator<ClientOptions.Builder<C>> perClientMutator = UnaryOperator.identity();
        private @NotNull Predicate<Client<C>> availability = client -> !client.isRateLimited();

        private Builder(@NotNull ClientOptions<C> baseOptions) {
            this.baseOptions = baseOptions;
        }

        /**
         * Sets the operator applied to the base options builder when constructing a new client.
         * <p>
         * The operator receives a fresh {@link ClientOptions.Builder} seeded from the base
         * options on each call to {@link #getClient()} that needs to add a new pool member, and
         * returns a builder that {@link Builder#build()} will then call to produce the per-client
         * options. Use this to inject per-client variance such as a fresh IPv6 source address.
         * <p>
         * Default: {@link UnaryOperator#identity()} - every client is an identical clone of the
         * base options.
         *
         * @param mutator the per-client options mutator
         * @return this builder
         */
        public @NotNull Builder<C> withPerClientMutator(@NotNull UnaryOperator<ClientOptions.Builder<C>> mutator) {
            this.perClientMutator = mutator;
            return this;
        }

        /**
         * Sets the predicate used to determine whether a pooled client is available to serve a
         * request.
         * <p>
         * Default: {@code client -> !client.isRateLimited()}, which checks the type-level
         * {@link dev.simplified.client.route.Route @Route} bucket via the no-arg
         * {@link Client#isRateLimited()}. Override this for multi-domain endpoints where the
         * relevant bucket is identified by a specific
         * {@link dev.simplified.client.route.DynamicRouteProvider}.
         *
         * @param predicate the availability predicate
         * @return this builder
         */
        public @NotNull Builder<C> withAvailability(@NotNull Predicate<Client<C>> predicate) {
            this.availability = predicate;
            return this;
        }

        /**
         * Configures the per-client mutator to assign a fresh randomized IPv6 local address from
         * the given CIDR network prefix to every newly constructed client.
         * <p>
         * Accepts a standard CIDR notation string (e.g., {@code "2000:444:ffff::/48"}). The
         * prefix length suffix is stripped, the address portion is parsed into groups, and the
         * remaining groups are filled with random 16-bit values via {@link ThreadLocalRandom} to
         * complete a {@link Inet6Address}. The resulting address is bound as the local source
         * address of the new client.
         *
         * <h5>Create Hurricane Electric IPv6 Tunnel</h5>
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
         * @param cidrPrefix an IPv6 network prefix in CIDR notation (e.g., {@code "2000:444:33ff::/48"})
         * @return this builder
         */
        public @NotNull Builder<C> withInet6Rotation(@NotNull String cidrPrefix) {
            Integer[] networkPrefix = parseCidr(cidrPrefix);
            return this.withPerClientMutator(builder -> builder.withInet6Address(generateInet6Address(networkPrefix)));
        }

        /**
         * Constructs an immutable {@link Proxy} from the current builder state.
         *
         * @return a new {@code Proxy}
         */
        public @NotNull Proxy<C> build() {
            return new Proxy<>(this.baseOptions, this.perClientMutator, this.availability);
        }

    }

    /**
     * Parses an IPv6 CIDR notation string into the array of leading 16-bit groups it declares.
     * <p>
     * The {@code /n} prefix length suffix is stripped, and the address portion is split on
     * colons; empty groups produced by the {@code ::} shorthand are filtered out so the
     * resulting array contains only the explicitly named groups.
     *
     * @param cidr the CIDR notation string (e.g., {@code "2000:444:ffff::/48"})
     * @return the parsed prefix groups as 16-bit unsigned integers
     */
    private static @NotNull Integer[] parseCidr(@NotNull String cidr) {
        String address = cidr.split("/")[0];
        return Arrays.stream(address.split(":"))
            .filter(group -> !group.isEmpty())
            .map(group -> Integer.parseInt(group, 16))
            .toArray(Integer[]::new);
    }

    /**
     * Generates a randomized {@link Inet6Address} by appending random 16-bit groups to the given
     * prefix groups until the address has eight groups total, then resolving the resulting string.
     *
     * @param networkPrefix the leading prefix groups
     * @return an {@link Optional} containing the randomized address
     * @throws RuntimeException wrapping any {@link UnknownHostException} thrown during resolution
     */
    private static @NotNull Optional<Inet6Address> generateInet6Address(@NotNull Integer[] networkPrefix) {
        String prefix = Arrays.stream(networkPrefix)
            .map(group -> String.format("%04x", group))
            .collect(Collectors.joining(":"));
        String tail = IntStream.range(0, 8 - networkPrefix.length)
            .mapToObj(i -> String.format("%04x", randomInet6Group()))
            .collect(Collectors.joining(":"));

        try {
            return Optional.of((Inet6Address) Inet6Address.getByName(prefix + ":" + tail));
        } catch (UnknownHostException uhex) {
            throw new RuntimeException(uhex);
        }
    }

    /**
     * Returns a random 16-bit unsigned integer suitable for use as an IPv6 group.
     *
     * @return a random value in the range {@code [0, 65535]}
     */
    private static int randomInet6Group() {
        return ThreadLocalRandom.current().nextInt() & 0xFFFF;
    }

}
