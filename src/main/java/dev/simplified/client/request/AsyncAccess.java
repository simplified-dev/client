package dev.simplified.client.request;

import dev.simplified.client.Client;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Mixin interface that provides asynchronous access to a synchronous {@link Contract}.
 * <p>
 * Because Feign-generated proxies are inherently blocking, this interface offers convenience
 * methods that lift those calls into {@link CompletableFuture} instances, allowing callers to
 * compose non-blocking pipelines around the underlying contract. The {@link Client} class and
 * the {@code Proxy} pool both implement this interface, so every client and proxy is
 * automatically capable of asynchronous invocation through the same API.
 * <p>
 * Two families of methods are provided:
 * <ul>
 *   <li>{@link #fromBlocking(Function)} / {@link #fromBlocking(Function, Executor)} - wraps a
 *       single-value contract call in a {@link CompletableFuture}.</li>
 *   <li>{@link #fluxFromBlocking(Function)} - wraps a collection-returning contract call and
 *       produces an unmodifiable {@link ConcurrentList} result.</li>
 * </ul>
 *
 * @param <C> the {@link Contract} type whose methods are invoked asynchronously
 * @see Contract
 * @see Client
 */
public interface AsyncAccess<C extends Contract> {

    /** The synchronous Feign-generated proxy implementing the underlying {@link Contract}. */
    @NotNull C getContract();

    /**
     * Executes a blocking contract call asynchronously using the common
     * {@link java.util.concurrent.ForkJoinPool}.
     * <p>
     * The supplied function receives the contract proxy and should invoke exactly one
     * Feign-declared method on it. The call is dispatched via
     * {@link CompletableFuture#supplyAsync(java.util.function.Supplier)}.
     *
     * @param <T> the result type produced by the contract call
     * @param call a function that accepts the contract proxy and returns a result
     * @return a {@link CompletableFuture} that completes with the call's return value
     */
    default <T> @NotNull CompletableFuture<T> fromBlocking(@NotNull Function<C, T> call) {
        return CompletableFuture.supplyAsync(
            () -> call.apply(this.getContract())
        );
    }

    /**
     * Executes a blocking contract call asynchronously using the specified {@link Executor}.
     * <p>
     * This overload is useful when the caller wants to control the thread pool used for the
     * asynchronous dispatch, for example to avoid saturating the common fork-join pool with
     * long-running HTTP calls.
     *
     * @param <T> the result type produced by the contract call
     * @param call a function that accepts the contract proxy and returns a result
     * @param executor the {@link Executor} on which to run the blocking call
     * @return a {@link CompletableFuture} that completes with the call's return value
     */
    default <T> @NotNull CompletableFuture<T> fromBlocking(@NotNull Function<C, T> call, @NotNull Executor executor) {
        return CompletableFuture.supplyAsync(
            () -> call.apply(this.getContract()),
            executor
        );
    }

    /**
     * Executes a blocking, collection-returning contract call asynchronously and wraps the
     * result in an unmodifiable {@link ConcurrentList}.
     * <p>
     * The returned list is created via {@link Concurrent#newUnmodifiableList(Collection)},
     * ensuring that downstream consumers cannot mutate the snapshot of results.
     *
     * @param <T> the element type of the collection returned by the contract call
     * @param call a function that accepts the contract proxy and returns a collection
     * @return a {@link CompletableFuture} that completes with an unmodifiable
     *         {@link ConcurrentList} containing the call's results
     */
    default <T> @NotNull CompletableFuture<ConcurrentList<T>> fluxFromBlocking(@NotNull Function<C, ? extends Collection<T>> call) {
        return CompletableFuture.supplyAsync(
            () -> Concurrent.newUnmodifiableList(call.apply(this.getContract()))
        );
    }

}
