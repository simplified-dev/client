package dev.simplified.client.request;

import dev.simplified.client.Client;
import dev.simplified.collection.concurrent.Concurrent;
import dev.simplified.collection.concurrent.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Provides asynchronous wrappers around synchronous {@link Endpoint} method calls.
 * <p>
 * Because Feign endpoint interfaces are inherently blocking, this interface offers
 * convenience methods that lift those calls into {@link CompletableFuture} instances,
 * allowing callers to compose non-blocking pipelines. The {@link Client}
 * class implements this interface, making every client automatically capable of
 * asynchronous invocation.
 * <p>
 * Two families of methods are provided:
 * <ul>
 *   <li>{@link #fromBlocking(Function)} / {@link #fromBlocking(Function, Executor)} -
 *       wraps a single-value endpoint call in a {@link CompletableFuture}.</li>
 *   <li>{@link #fluxFromBlocking(Function)} - wraps a collection-returning endpoint
 *       call and produces an unmodifiable {@link ConcurrentList} result.</li>
 * </ul>
 *
 * @param <E> the {@link Endpoint} type whose methods are invoked asynchronously
 * @see Endpoint
 * @see Client
 */
public interface ReactiveEndpoint<E extends Endpoint> {

    /** The synchronous endpoint proxy managed by the owning client. */
    @NotNull E getEndpoint();

    /**
     * Executes a blocking endpoint call asynchronously using the common
     * {@link java.util.concurrent.ForkJoinPool}.
     * <p>
     * The supplied function receives the endpoint proxy and should invoke exactly
     * one Feign-declared method on it. The call is dispatched via
     * {@link CompletableFuture#supplyAsync(java.util.function.Supplier)}.
     *
     * @param <T> the result type produced by the endpoint call
     * @param endpointCall a function that accepts the endpoint proxy and returns a result
     * @return a {@link CompletableFuture} that completes with the endpoint call's return value
     */
    default <T> @NotNull CompletableFuture<T> fromBlocking(@NotNull Function<E, T> endpointCall) {
        return CompletableFuture.supplyAsync(
            () -> endpointCall.apply(this.getEndpoint())
        );
    }

    /**
     * Executes a blocking endpoint call asynchronously using the specified {@link Executor}.
     * <p>
     * This overload is useful when the caller wants to control the thread pool used for
     * the asynchronous dispatch, for example to avoid saturating the common fork-join pool
     * with long-running HTTP calls.
     *
     * @param <T> the result type produced by the endpoint call
     * @param endpointCall a function that accepts the endpoint proxy and returns a result
     * @param executor the {@link Executor} on which to run the blocking call
     * @return a {@link CompletableFuture} that completes with the endpoint call's return value
     */
    default <T> @NotNull CompletableFuture<T> fromBlocking(@NotNull Function<E, T> endpointCall, @NotNull Executor executor) {
        return CompletableFuture.supplyAsync(
            () -> endpointCall.apply(this.getEndpoint()),
            executor
        );
    }

    /**
     * Executes a blocking, collection-returning endpoint call asynchronously and wraps the
     * result in an unmodifiable {@link ConcurrentList}.
     * <p>
     * The returned list is created via {@link Concurrent#newUnmodifiableList(Collection)},
     * ensuring that downstream consumers cannot mutate the snapshot of results.
     *
     * @param <T> the element type of the collection returned by the endpoint call
     * @param endpointCall a function that accepts the endpoint proxy and returns a collection
     * @return a {@link CompletableFuture} that completes with an unmodifiable
     *         {@link ConcurrentList} containing the endpoint call's results
     */
    default <T> @NotNull CompletableFuture<ConcurrentList<T>> fluxFromBlocking(@NotNull Function<E, ? extends Collection<T>> endpointCall) {
        return CompletableFuture.supplyAsync(
            () -> Concurrent.newUnmodifiableList(endpointCall.apply(this.getEndpoint()))
        );
    }

}
