package dev.simplified.client.response;

import dev.simplified.util.StringUtil;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Enumeration of HTTP response status code categories, grouping numeric status codes
 * into logical ranges that represent informational, success, redirection, client error,
 * server error, and application-specific error states.
 * <p>
 * Each constant defines an inclusive range ({@code getMinCode()} to {@code getMaxCode()})
 * and an {@code isError()} flag. Standard HTTP categories cover the five canonical ranges
 * ({@code 1xx} through {@code 5xx}), while additional constants capture vendor-specific
 * and application-specific ranges:
 * <ul>
 *     <li>{@link #NGINX_ERROR} - Nginx-specific status codes ({@code 494-499})</li>
 *     <li>{@link #CLOUDFLARE_ERROR} - Cloudflare-specific status codes ({@code 520-530})</li>
 *     <li>{@link #NETWORK_ERROR} - network-level timeout errors ({@code 598-599})</li>
 *     <li>{@link #JAVA_ERROR} - application-level Java errors ({@code 990-999})</li>
 * </ul>
 * <p>
 * The {@link #of(int)} factory method resolves an arbitrary numeric code to its most
 * specific state, preferring narrower vendor/application ranges over the broad
 * {@link #SERVER_ERROR} catch-all. Each {@link HttpStatus} constant holds a reference
 * to its owning {@code HttpState}, enabling callers to quickly determine whether a
 * response is successful, informational, or erroneous.
 *
 * @see HttpStatus
 */
@Getter
public enum HttpState {

    /** Informational responses ({@code 100-199}). */
    INFORMATIONAL(100, 199),

    /** Successful responses ({@code 200-299}). */
    SUCCESS(200, 299),

    /** Redirection responses ({@code 300-399}). */
    REDIRECTION(300, 399),

    /** Client error responses ({@code 400-451}). */
    CLIENT_ERROR(400, 451),

    /** General server error responses ({@code 500-599}), used as a catch-all when no more specific server-side state matches. */
    SERVER_ERROR(500, 599, true),

    /** Nginx-specific server error responses ({@code 494-499}). */
    NGINX_ERROR(494, 499, true),

    /** Cloudflare-specific server error responses ({@code 520-530}). */
    CLOUDFLARE_ERROR(520, 530, true),

    /** Network-level timeout error responses ({@code 598-599}). */
    NETWORK_ERROR(598, 599, true),

    /** Application-level Java error responses ({@code 990-999}). */
    JAVA_ERROR(990, 999, true);

    /** Cached snapshot of {@link #values()} reused by lookups to avoid the per-call defensive array clone. */
    private static final HttpState @NotNull [] CACHED_VALUES = values();

    /** The human-readable title derived from the constant name, formatted with {@link StringUtil#capitalizeFully(String)}. */
    private final @NotNull String title;

    /** The lower bound (inclusive) of the status code range represented by this state. */
    private final int minCode;

    /** The upper bound (inclusive) of the status code range represented by this state. */
    private final int maxCode;

    /** Whether this state represents an error condition. */
    private final boolean error;

    HttpState(int minCode, int maxCode) {
        this(minCode, maxCode, false);
    }

    HttpState(int minCode, int maxCode, boolean error) {
        this.title = StringUtil.capitalizeFully(this.name().replace("_", " "));
        this.minCode = minCode;
        this.maxCode = maxCode;
        this.error = error;
    }

    /**
     * Checks whether the given HTTP status code falls within this state's inclusive range.
     *
     * @param code the HTTP status code to test
     * @return {@code true} if {@code code} is between {@link #getMinCode()} and
     *         {@link #getMaxCode()} (inclusive); {@code false} otherwise
     */
    public boolean containsCode(int code) {
        return code >= this.getMinCode() && code <= this.getMaxCode();
    }

    /**
     * Checks whether this state represents a successful HTTP outcome.
     *
     * @return {@code true} if this state is {@link #SUCCESS}; {@code false} otherwise
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * Resolves the most specific {@link HttpState} for the given HTTP status code.
     * <p>
     * Vendor-specific and application-specific states ({@link #NGINX_ERROR},
     * {@link #CLOUDFLARE_ERROR}, {@link #NETWORK_ERROR}, {@link #JAVA_ERROR}) are
     * checked before the broad {@link #SERVER_ERROR} range, ensuring that codes
     * belonging to a narrower range are not swallowed by the general server-error
     * category. If no state matches, an {@link IllegalArgumentException} is thrown.
     *
     * @param code the HTTP status code to resolve
     * @return the most specific {@link HttpState} whose range contains {@code code}
     * @throws IllegalArgumentException if {@code code} does not fall within any
     *                                  defined state range
     */
    public static @NotNull HttpState of(int code) {
        for (HttpState httpState : CACHED_VALUES) {
            if (httpState == SERVER_ERROR)
                continue;

            if (httpState.containsCode(code))
                return httpState;
        }

        if (SERVER_ERROR.containsCode(code))
            return SERVER_ERROR;

        throw new IllegalArgumentException("Invalid HTTP status code: " + code);
    }

}
