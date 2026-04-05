package dev.simplified.client.request;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

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

    /** Whether this HTTP method conventionally carries a request body. */
    private final boolean withBody;

    /**
     * Constructs an {@code HttpMethod} constant that does not carry a request body.
     */
    HttpMethod() {
        this(false);
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
        return Arrays.stream(values())
            .filter(value -> value.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(GET);
    }

}
