package dev.simplified.client.request;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Enumeration of standard HTTP request methods as defined by RFC 7231 and RFC 5789.
 * <p>
 * Each constant corresponds to a well-known HTTP method and tracks whether that method
 * conventionally carries a request body. Methods such as {@link #POST}, {@link #PUT}, and
 * {@link #PATCH} are flagged as body-bearing; all others default to no body.
 * <p>
 * This enum is used by {@link Request} to describe the HTTP method of a captured request
 * and by the broader {@link dev.simplified.client.Client} infrastructure to determine
 * request semantics.
 *
 * @see Request
 * @see dev.simplified.client.Client
 */
@Getter
@RequiredArgsConstructor
public enum HttpMethod {

    /** HTTP GET method; retrieves a resource without a request body. */
    GET,

    /** HTTP HEAD method; identical to GET but returns only headers, no body. */
    HEAD,

    /** HTTP POST method; submits data in a request body to create or process a resource. */
    POST(true),

    /** HTTP PUT method; replaces or creates a resource using the supplied request body. */
    PUT(true),

    /** HTTP DELETE method; removes the specified resource without a request body. */
    DELETE,

    /** HTTP CONNECT method; establishes a tunnel to the server, typically for HTTPS proxying. */
    CONNECT,

    /** HTTP OPTIONS method; describes the communication options for the target resource. */
    OPTIONS,

    /** HTTP TRACE method; performs a message loop-back test along the path to the target resource. */
    TRACE,

    /** HTTP PATCH method; applies partial modifications to a resource using the supplied request body. */
    PATCH(true);

    /** Cached snapshot of {@link #values()} reused by lookups to avoid the per-call defensive array clone. */
    private static final HttpMethod @NotNull [] CACHED_VALUES = values();

    /** Index of uppercase method names to enum constants for O(1) lookup by canonical name. */
    private static final @NotNull Map<String, HttpMethod> BY_NAME;

    static {
        Map<String, HttpMethod> byName = new HashMap<>(CACHED_VALUES.length * 2);

        for (HttpMethod value : CACHED_VALUES)
            byName.put(value.name(), value);

        BY_NAME = Map.copyOf(byName);
    }

    /** Whether this HTTP method conventionally carries a request body. */
    private final boolean withBody;

    /**
     * Constructs an {@code HttpMethod} constant that does not carry a request body.
     */
    HttpMethod() {
        this(false);
    }

    /**
     * Indicates whether this method is considered <i>safe</i> per
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-4.2.1">RFC 7231
     * Section 4.2.1</a>.
     * <p>
     * Safe methods ({@code GET}, {@code HEAD}, {@code OPTIONS}, {@code TRACE}) are
     * defined as being essentially read-only and must not have server-side side effects
     * beyond what a reasonable cache or log would capture. The HTTP response cache uses
     * this distinction to decide whether to invalidate cached entries for a URL after a
     * successful request (unsafe methods invalidate; safe methods do not).
     *
     * @return {@code true} if this method is safe per RFC 7231 §4.2.1
     */
    public boolean isSafe() {
        return this == GET || this == HEAD || this == OPTIONS || this == TRACE;
    }

    /**
     * Indicates whether responses to this method may be stored in an HTTP cache by
     * default per
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-4.2.3">RFC 7231
     * Section 4.2.3</a>.
     * <p>
     * Only {@code GET} and {@code HEAD} are cacheable by default in the general case.
     * Other methods may carry an explicit cache-control directive permitting storage,
     * but the response cache in this client only considers cacheable methods as
     * candidates for storage and lookup.
     *
     * @return {@code true} if responses to this method are cacheable by default
     */
    public boolean isCacheable() {
        return this == GET || this == HEAD;
    }

    /**
     * Resolves an {@code HttpMethod} from its name using a case-insensitive comparison.
     * <p>
     * If no matching constant is found, {@link #GET} is returned as the default.
     *
     * @param name the HTTP method name to look up (e.g. {@code "post"}, {@code "GET"})
     * @return the matching {@code HttpMethod}, or {@link #GET} if no match is found
     */
    public static @NotNull HttpMethod of(@NotNull String name) {
        HttpMethod method = BY_NAME.get(name.toUpperCase(Locale.ROOT));
        return method != null ? method : GET;
    }

}
