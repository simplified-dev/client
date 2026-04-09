package dev.simplified.client.response;

import dev.simplified.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * An immutable HTTP entity tag parsed from an {@code ETag} response header or the inverse of an
 * {@code If-Match} / {@code If-None-Match} request header.
 * <p>
 * Per <a href="https://datatracker.ietf.org/doc/html/rfc7232#section-2.3">RFC 7232 Section 2.3</a>
 * an entity tag is an opaque quoted string optionally prefixed with {@code W/} to mark it as a
 * weak validator. Strong validators must be byte-identical to match and are therefore suitable
 * for sub-range requests and {@code If-Match}; weak validators are semantically equivalent but
 * not necessarily byte-identical and are used only with weak comparison (such as the 304
 * cache-revalidation path).
 * <p>
 * Use {@link #parse(String)} to produce an {@code ETag} from a raw header value, guarding
 * against null, blank, and malformed inputs. Use {@link #toHeaderValue()} to re-emit an ETag
 * for an outbound conditional request, preserving the strong or weak prefix.
 *
 * @param value the opaque entity tag value with surrounding quotes and any weak prefix removed
 * @param weak {@code true} if the tag was prefixed with {@code W/}, indicating a weak validator
 * @see Response#getETag()
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7232#section-2.3">RFC 7232 Section 2.3</a>
 */
public record ETag(@NotNull String value, boolean weak) {

    /** The canonical {@code ETag} HTTP response header name. */
    public static final @NotNull String HEADER_KEY = "ETag";

    /** The canonical {@code If-Match} HTTP request header name. */
    public static final @NotNull String IF_MATCH_HEADER = "If-Match";

    /** The canonical {@code If-None-Match} HTTP request header name. */
    public static final @NotNull String IF_NONE_MATCH_HEADER = "If-None-Match";

    /**
     * Parses a raw {@code ETag} header value into an {@link ETag}.
     * <p>
     * Handles null, blank, and wildcard ({@code *}) inputs by returning
     * {@link Optional#empty()}. Strips a leading {@code W/} prefix to detect weak validators
     * and removes surrounding double-quotes. Returns empty if the tag value is empty after
     * unquoting, guarding against malformed inputs such as a bare {@code ""}.
     *
     * @param raw the raw header value, may be {@code null}
     * @return an {@code ETag} parsed from {@code raw}, or {@link Optional#empty()} if the value
     *         is absent, blank, a wildcard, or malformed
     */
    public static @NotNull Optional<ETag> parse(@Nullable String raw) {
        if (StringUtil.isBlank(raw))
            return Optional.empty();

        assert raw != null;
        String trimmed = raw.trim();

        // Wildcard is valid in If-Match/If-None-Match headers but is not itself an entity tag;
        // callers who need 'If-Match: *' can set the header directly.
        if ("*".equals(trimmed))
            return Optional.empty();

        boolean weak = false;

        if (trimmed.startsWith("W/")) {
            weak = true;
            trimmed = trimmed.substring(2).trim();
        }

        // Strip the surrounding quotes.
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"')
            trimmed = trimmed.substring(1, trimmed.length() - 1);

        if (trimmed.isEmpty())
            return Optional.empty();

        return Optional.of(new ETag(trimmed, weak));
    }

    /**
     * Renders this entity tag as a valid HTTP header value, including the surrounding
     * double-quotes and the {@code W/} prefix when {@linkplain #weak() weak}.
     *
     * @return the wire-format header value for this tag
     */
    public @NotNull String toHeaderValue() {
        return this.weak ? "W/\"" + this.value + '"' : '"' + this.value + '"';
    }

}
