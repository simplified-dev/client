package dev.simplified.client.exception;

import dev.simplified.client.decoder.InternalErrorDecoder;
import dev.simplified.client.response.ETag;
import dev.simplified.client.response.HttpStatus;
import feign.FeignException;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a remote API responds with {@code 412 Precondition Failed} to a conditional
 * request.
 *
 * <p>A {@code 412} response indicates that a precondition carried by the request - typically
 * an {@code If-Match} or {@code If-Unmodified-Since} header - evaluated to false on the
 * server. For mutating requests ({@code PUT}, {@code PATCH}, {@code DELETE}), this is the
 * standard signal that the resource has been modified since the caller last observed it and
 * that blindly applying the pending change would overwrite a concurrent update. Callers
 * should re-read the resource, merge or discard the pending change, and retry.
 *
 * <p>The framework's {@link InternalErrorDecoder} routes every {@code 412} response through
 * this type before the domain {@link dev.simplified.client.decoder.ClientErrorDecoder} runs,
 * following the same pattern used for {@code 3xx} ({@link NotModifiedException}) and
 * {@code 429} ({@link RateLimitException}). Domain error decoders therefore cannot intercept
 * {@code 412} responses - if a custom parse is needed, catch this exception at the call site
 * and inspect its body and headers directly.
 *
 * <p>Intended usage pattern:
 * <pre>{@code
 * try {
 *     contract.updateUser(userId, updated);  // has @Headers("If-Match: {etag}")
 * } catch (PreconditionFailedException ex) {
 *     // concurrent update detected - reload and retry
 *     User current = contract.getUser(userId);
 *     contract.updateUser(userId, merge(current, updated));
 * }
 * }</pre>
 *
 * @see ETag
 * @see HttpStatus#PRECONDITION_FAILED
 * @see InternalErrorDecoder
 */
public class PreconditionFailedException extends ApiException {

    /** The short name identifying this exception type in logs and error tracking. */
    public static final @NotNull String NAME = "PreconditionFailed";

    /**
     * Constructs a new {@code PreconditionFailedException} from a Feign method key and the raw
     * {@code 412} response.
     *
     * @param methodKey the Feign method key identifying the endpoint that was probed
     * @param response the raw Feign HTTP response carrying the {@code 412} status and any
     *                 accompanying headers
     */
    public PreconditionFailedException(@NotNull String methodKey, @NotNull feign.Response response) {
        super(FeignException.errorStatus(methodKey, response), response, NAME);
    }

}
