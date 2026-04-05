package dev.simplified.client.request;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents the metadata of an HTTP request, providing access to the request's
 * {@link HttpMethod} and URL.
 * <p>
 * This interface is used within the {@link dev.simplified.client.Client} framework to capture
 * and expose request details for response processing, error decoding, and diagnostic purposes.
 * A default implementation is provided by the inner class {@link Impl}.
 *
 * @see Impl
 * @see HttpMethod
 * @see dev.simplified.client.response.Response
 */
public interface Request {

    /** The {@link HttpMethod} used for this request. */
    @NotNull HttpMethod getMethod();

    /** The fully-qualified URL that this request was issued against. */
    @NotNull String getUrl();

    /**
     * Immutable data holder that implements {@link Request} using Lombok-generated accessors.
     *
     * @see Request
     */
    @Getter
    @RequiredArgsConstructor
    class Impl implements Request {

        /** The HTTP method used for this request. */
        private final @NotNull HttpMethod method;

        /** The fully-qualified URL that this request targeted. */
        private final @NotNull String url;

    }

}
