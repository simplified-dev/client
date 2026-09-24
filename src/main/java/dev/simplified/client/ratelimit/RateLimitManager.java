package dev.simplified.client.ratelimit;

import dev.simplified.annotations.NoArgsConstructor;
import dev.simplified.client.Client;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central registry that tracks per-route rate-limit state across multiple
 * {@link RateLimitBucket} instances.
 * <p>
 * Each {@link Client} owns a single {@code RateLimitManager}
 * whose buckets are keyed by route identifiers (typically the resolved domain or
 * domain+path string from {@link RouteDiscovery}), or by the quota a server names for its
 * responses, such as GitHub's {@code core} and {@code search}; see
 * {@link #getBucketKey(String, String)} for which bucket a request counts against.
 * The manager coordinates proactive (client-side) rate-limit enforcement by
 * providing query and mutation methods used by the request and response
 * interceptor pipeline.
 * <p>
 * Bucket creation is lazy: buckets are instantiated on first access when a
 * {@link RateLimit} policy is supplied, and are never created by read-only
 * query methods.
 *
 * @see RateLimitBucket
 * @see RateLimit
 * @see dev.simplified.client.Client
 */
@NoArgsConstructor
public class RateLimitManager {

    /**
     * Map of route identifiers to their corresponding rate-limit buckets.
     */
    private final @NotNull ConcurrentMap<String, RateLimitBucket> buckets = Concurrent.newMap();

    /**
     * Map of route identifiers to the bucket key of the quota their latest response named.
     */
    private final @NotNull ConcurrentMap<String, String> routeQuotas = Concurrent.newMap();

    /**
     * Map of route identifiers to, per endpoint on the route, the bucket key of the quota the
     * endpoint's latest response named.
     */
    private final @NotNull ConcurrentMap<String, ConcurrentMap<String, String>> endpointQuotas = Concurrent.newMap();

    /**
     * The number {@link #nextSequence()} last gave a request.
     */
    private final @NotNull AtomicLong sequence = new AtomicLong();

    /**
     * Retrieves an existing bucket for the given identifier, or creates a new
     * one initialized with the specified {@link RateLimit} policy if none exists.
     *
     * @param bucketId the route identifier for the bucket
     * @param rateLimit the rate-limit policy to use if a new bucket must be created
     * @return the existing or newly created bucket, never {@code null}
     */
    private @NotNull RateLimitBucket getOrCreateBucket(@NotNull String bucketId, @NotNull RateLimit rateLimit) {
        return this.getOrCreateBucket(bucketId, rateLimit, System.currentTimeMillis());
    }

    /**
     * Retrieves an existing bucket for the given identifier, or creates a new one whose first
     * window opens at {@code now} under the specified {@link RateLimit} policy if none exists.
     *
     * @param bucketId the route identifier for the bucket
     * @param rateLimit the rate-limit policy to use if a new bucket must be created
     * @param now the epoch-millisecond timestamp a new bucket's first window opens at
     * @return the existing or newly created bucket, never {@code null}
     */
    private @NotNull RateLimitBucket getOrCreateBucket(@NotNull String bucketId, @NotNull RateLimit rateLimit, long now) {
        return this.buckets.computeIfAbsent(bucketId, __ -> new RateLimitBucket(rateLimit, now));
    }

    /**
     * Checks whether the bucket identified by {@code bucketId} has exhausted
     * its quota for the current window.
     * <p>
     * This overload does <em>not</em> create a missing bucket; if no bucket
     * exists for the given identifier, {@code false} is returned.
     *
     * @param bucketId the route identifier to check
     * @return {@code true} if the bucket exists and is currently rate-limited;
     *         {@code false} otherwise
     */
    public boolean isRateLimited(@NotNull String bucketId) {
        RateLimitBucket bucket = this.buckets.get(bucketId);
        return bucket != null && bucket.isRateLimited();
    }

    /**
     * Checks whether the bucket identified by {@code bucketId} has exhausted
     * its quota for the current window.
     * <p>
     * Unlike {@link #isRateLimited(String)}, this overload creates the bucket
     * with the supplied {@link RateLimit} policy if it does not already exist,
     * making it suitable for use during known request flows where the policy is
     * always available.
     *
     * @param bucketId the route identifier to check
     * @param rateLimit the rate-limit policy to use if the bucket must be created
     * @return {@code true} if the bucket is currently rate-limited; {@code false}
     *         otherwise
     */
    public boolean isRateLimited(@NotNull String bucketId, @NotNull RateLimit rateLimit) {
        return this.getOrCreateBucket(bucketId, rateLimit).isRateLimited();
    }

    /**
     * Variant of {@link #isRateLimited(String, RateLimit)} that accepts a pre-sampled
     * epoch-millisecond timestamp, allowing callers that already hold a clock reading to
     * avoid an extra {@link System#currentTimeMillis()} sample.
     *
     * @param bucketId the route identifier to check
     * @param rateLimit the rate-limit policy to use if the bucket must be created
     * @param now the pre-sampled epoch-millisecond timestamp to evaluate the window against
     * @return {@code true} if the bucket is currently rate-limited; {@code false} otherwise
     */
    public boolean isRateLimited(@NotNull String bucketId, @NotNull RateLimit rateLimit, long now) {
        return this.getOrCreateBucket(bucketId, rateLimit, now).isRateLimited(now);
    }

    /**
     * Records a single request against the bucket identified by {@code bucketId}.
     * <p>
     * Creates the bucket with the supplied {@link RateLimit} policy if it does
     * not already exist.
     *
     * @param bucketId the route identifier to track
     * @param rateLimit the rate-limit policy to use if the bucket must be created
     */
    public void trackRequest(@NotNull String bucketId, @NotNull RateLimit rateLimit) {
        this.getOrCreateBucket(bucketId, rateLimit).trackRequest();
    }

    /**
     * Variant of {@link #trackRequest(String, RateLimit)} that accepts a pre-sampled
     * epoch-millisecond timestamp, allowing callers that already hold a clock reading to
     * avoid an extra {@link System#currentTimeMillis()} sample.
     *
     * @param bucketId the route identifier to track
     * @param rateLimit the rate-limit policy to use if the bucket must be created
     * @param now the pre-sampled epoch-millisecond timestamp to record this request against
     */
    public void trackRequest(@NotNull String bucketId, @NotNull RateLimit rateLimit, long now) {
        this.getOrCreateBucket(bucketId, rateLimit, now).trackRequest(now);
    }

    /**
     * Replaces the rate-limit policy for the bucket identified by
     * {@code bucketId}.
     * <p>
     * If the bucket already exists, its policy is updated in place.  If no
     * bucket exists, a new one is created with the given policy and an initial
     * request count of zero.  This is typically called when updated rate-limit
     * information is received from the server via response headers.
     *
     * @param bucketId the route identifier whose policy should be updated
     * @param newLimit the updated rate-limit policy
     */
    public void updateRateLimit(@NotNull String bucketId, @NotNull RateLimit newLimit) {
        this.updateRateLimit(bucketId, newLimit, System.currentTimeMillis());
    }

    /**
     * Variant of {@link #updateRateLimit(String, RateLimit)} that accepts a pre-sampled
     * epoch-millisecond timestamp.
     * <p>
     * The bucket's window follows the new policy as
     * {@link RateLimitBucket#updateRateLimit(RateLimit, long)} describes: a server-advertised
     * policy ends it at the server's reset instant.
     *
     * @param bucketId the route identifier whose policy should be updated
     * @param newLimit the updated rate-limit policy
     * @param now the pre-sampled epoch-millisecond timestamp the policy was received at
     */
    public void updateRateLimit(@NotNull String bucketId, @NotNull RateLimit newLimit, long now) {
        this.getOrCreateBucket(bucketId, newLimit, now).updateRateLimit(newLimit, now);
    }

    /**
     * Applies the rate-limit headers of a server response to the bucket identified by
     * {@code bucketId}.
     *
     * @param bucketId the route identifier the response belongs to
     * @param headers the response headers to read
     * @see #updateFromHeaders(String, Map, long)
     */
    public void updateFromHeaders(@NotNull String bucketId, @NotNull Map<String, Collection<String>> headers) {
        this.updateFromHeaders(bucketId, headers, System.currentTimeMillis());
    }

    /**
     * Applies the rate-limit headers of a server response, received at a pre-sampled timestamp,
     * to the bucket identified by {@code bucketId}.
     * <p>
     * The policy parsed by {@link RateLimit#fromHeaders(Map, long)} replaces the bucket's as
     * {@link #updateRateLimit(String, RateLimit, long)} does, so the bucket's window ends when
     * the server's quota resets. A remaining count parsed by
     * {@link RateLimit#remainingFromHeaders(Map)} then
     * {@linkplain RateLimitBucket#syncRemaining(long) syncs} the bucket's request count to the
     * server's. Headers without both a limit and a reset leave every bucket untouched and create
     * none. The headers are applied in whatever order responses arrive;
     * {@link #updateFromHeaders(String, Map, long, long)} orders them by the request they answered.
     *
     * @param bucketId the route identifier the response belongs to
     * @param headers the response headers to read
     * @param now the pre-sampled epoch-millisecond timestamp the response was received at
     */
    public void updateFromHeaders(@NotNull String bucketId, @NotNull Map<String, Collection<String>> headers, long now) {
        Optional<RateLimit> serverLimit = RateLimit.fromHeaders(headers, now);

        if (serverLimit.isEmpty())
            return;

        RateLimitBucket bucket = this.getOrCreateBucket(bucketId, serverLimit.get(), now);
        bucket.updateRateLimit(serverLimit.get(), now);
        RateLimit.remainingFromHeaders(headers).ifPresent(bucket::syncRemaining);
    }

    /**
     * Applies the rate-limit headers of the response to a numbered request, received at a
     * pre-sampled timestamp, to the bucket identified by {@code bucketId}, unless the response
     * to a later request has already been applied to it.
     * <p>
     * The newest response a bucket has seen is applied as
     * {@link #updateFromHeaders(String, Map, long)} applies one; an older one is ignored whole,
     * for the reasons {@link RateLimitBucket#updateFromServer} gives. Headers without both a
     * limit and a reset leave every bucket untouched and create none.
     *
     * @param bucketId the route identifier the response belongs to
     * @param headers the response headers to read
     * @param now the pre-sampled epoch-millisecond timestamp the response was received at
     * @param sequence the number {@link #nextSequence()} gave the request the response answered
     */
    public void updateFromHeaders(@NotNull String bucketId, @NotNull Map<String, Collection<String>> headers, long now, long sequence) {
        Optional<RateLimit> serverLimit = RateLimit.fromHeaders(headers, now);

        if (serverLimit.isEmpty())
            return;

        this.getOrCreateBucket(bucketId, serverLimit.get(), now)
            .updateFromServer(serverLimit.get(), RateLimit.remainingFromHeaders(headers), now, sequence);
    }

    /**
     * Applies the rate-limit headers of the response to a numbered request to the bucket of the
     * quota the response counted against, unless the response to a later request has already
     * been applied to it.
     * <p>
     * When the response names its quota, as GitHub does in {@code X-RateLimit-Resource}, the
     * bucket is the {@linkplain RouteDiscovery.Metadata#getQuotaKey(String) quota's}, shared by
     * every route on the host that names it; otherwise it is the route's own. The headers are
     * applied as {@link #updateFromHeaders(String, Map, long, long)} applies them, and the
     * bucket they reached becomes the one {@link #getBucketKey(String, String)} resolves the
     * endpoint to and {@link #getBucketKey(String)} resolves the route to. Headers without both
     * a limit and a reset leave every bucket untouched and create none.
     *
     * @param route the route the request was sent through
     * @param endpoint identifies the endpoint the request invoked, or {@code null} if unknown
     * @param headers the response headers to read
     * @param now the pre-sampled epoch-millisecond timestamp the response was received at
     * @param sequence the number {@link #nextSequence()} gave the request the response answered
     */
    public void updateFromHeaders(
        @NotNull RouteDiscovery.Metadata route,
        @Nullable String endpoint,
        @NotNull Map<String, Collection<String>> headers,
        long now,
        long sequence
    ) {
        Optional<RateLimit> serverLimit = RateLimit.fromHeaders(headers, now);

        if (serverLimit.isEmpty())
            return;

        String routeKey = route.getBucketKey();
        Optional<String> quota = RateLimit.quotaFromHeaders(headers);
        String bucketId = quota.map(route::getQuotaKey).orElse(routeKey);

        this.getOrCreateBucket(bucketId, serverLimit.get(), now)
            .updateFromServer(serverLimit.get(), RateLimit.remainingFromHeaders(headers), now, sequence);

        if (quota.isPresent()) {
            this.routeQuotas.put(routeKey, bucketId);

            if (endpoint != null)
                this.endpointQuotas.computeIfAbsent(routeKey, key -> Concurrent.newMap()).put(endpoint, bucketId);
        } else {
            this.routeQuotas.remove(routeKey);
            ConcurrentMap<String, String> endpoints = this.endpointQuotas.get(routeKey);

            if (endpoints != null && endpoint != null)
                endpoints.remove(endpoint);
        }
    }

    /**
     * Resolves the bucket a route's requests count against, for queries that name only the
     * route.
     * <p>
     * That is the bucket of the quota the route's latest response named, or the route's own
     * bucket while no response has named one. A route whose endpoints count against different
     * quotas resolves to the one named last; {@link #getBucketKey(String, String)} resolves
     * each endpoint to its own.
     *
     * @param routeKey the route's bucket key
     * @return the bucket key the route's requests count against
     */
    public @NotNull String getBucketKey(@NotNull String routeKey) {
        return this.routeQuotas.getOrDefault(routeKey, routeKey);
    }

    /**
     * Resolves the bucket an endpoint's requests are gated and counted against.
     * <p>
     * That is the bucket of the quota the endpoint's latest response named, so two endpoints on
     * one route that count against different quotas never share one, or the route's own bucket
     * while no response to the endpoint has named one.
     *
     * @param routeKey the bucket key of the route the endpoint is sent through
     * @param endpoint identifies the endpoint, as the response side passes it to
     *                 {@link #updateFromHeaders(RouteDiscovery.Metadata, String, Map, long, long)}
     * @return the bucket key the endpoint's requests count against
     */
    public @NotNull String getBucketKey(@NotNull String routeKey, @NotNull String endpoint) {
        ConcurrentMap<String, String> endpoints = this.endpointQuotas.get(routeKey);

        if (endpoints == null)
            return routeKey;

        String quotaKey = endpoints.get(endpoint);
        return quotaKey != null ? quotaKey : routeKey;
    }

    /**
     * Numbers a request as it is sent, so the response that answers it can be ordered against
     * the responses to other requests through this manager.
     * <p>
     * The number travels with the request and back with its response to
     * {@link #updateFromHeaders(String, Map, long, long)}.
     *
     * @return a number greater than every one this manager has returned before
     */
    public long nextSequence() {
        return this.sequence.incrementAndGet();
    }

    /**
     * Returns the number of requests recorded in the current window for the
     * specified bucket.
     * <p>
     * Does <em>not</em> create a missing bucket; returns {@code 0} if no
     * bucket exists for the given identifier.
     *
     * @param bucketId the route identifier to query
     * @return the current window request count, or {@code 0} if the bucket does not exist
     */
    public long getRequestCount(@NotNull String bucketId) {
        RateLimitBucket bucket = this.buckets.get(bucketId);
        return bucket != null ? bucket.getCount() : 0;
    }

    /**
     * Returns the number of requests remaining before the specified bucket's
     * quota is exhausted in the current window.
     * <p>
     * Does <em>not</em> create a missing bucket; returns
     * {@link RateLimit#UNLIMITED}'s limit if no bucket exists for the given
     * identifier.
     *
     * @param bucketId the route identifier to query
     * @return the number of remaining requests, or the unlimited sentinel value
     *         if the bucket does not exist
     */
    public long getRemaining(@NotNull String bucketId) {
        RateLimitBucket bucket = this.buckets.get(bucketId);
        return bucket != null ? bucket.getRemaining() : RateLimit.UNLIMITED.getLimit();
    }

    /**
     * Removes all buckets from this manager, discarding all tracked state, including the quotas
     * routes and endpoints resolve to.
     */
    public void clear() {
        this.buckets.clear();
        this.routeQuotas.clear();
        this.endpointQuotas.clear();
    }

    /**
     * Checks whether a bucket with the given identifier exists in this manager.
     * <p>
     * Does <em>not</em> create a missing bucket.
     *
     * @param bucketId the route identifier to look up
     * @return {@code true} if a bucket exists for the given identifier;
     *         {@code false} otherwise
     */
    public boolean hasBucket(@NotNull String bucketId) {
        return this.buckets.containsKey(bucketId);
    }

    /**
     * Resets the window start and request count for the bucket identified by
     * {@code bucketId}.
     * <p>
     * Does <em>not</em> create a missing bucket; if no bucket exists for the
     * given identifier, this method is a no-op.
     *
     * @param bucketId the route identifier whose bucket should be reset
     */
    public void reset(@NotNull String bucketId) {
        RateLimitBucket bucket = this.buckets.get(bucketId);

        if (bucket != null)
            bucket.reset();
    }

}
