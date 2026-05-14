package dev.simplified.client.decoder;

import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.request.HttpMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class InternalErrorDecoderInitTest {

    @Test
    @DisplayName("InternalErrorDecoder loads without throwing - VarHandle resolves across packages")
    void classLoadsSuccessfully() {
        // Touching the class triggers <clinit>; a privateLookupIn failure would fail-fast here.
        Class<?> loaded = InternalErrorDecoder.class;
        assertThat(loaded.getName(), is(equalTo("dev.simplified.client.decoder.InternalErrorDecoder")));
    }

    @Test
    @DisplayName("ApiException.retryAttempts is writable after construction")
    void retryAttemptsRemainsWritableThroughTheNewHandle() throws Exception {
        ErrorContext ctx = new ErrorContext(
            HttpStatus.SERVICE_UNAVAILABLE,
            HttpMethod.GET,
            "https://example.com/r",
            Collections.emptyMap(),
            Collections.emptyMap(),
            new byte[0]
        );
        ApiException ex = new ApiException(null, "Bench", ctx);
        assertThat(ex.getRetryAttempts(), is(equalTo(0)));

        java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.privateLookupIn(
            ApiException.class,
            java.lang.invoke.MethodHandles.lookup()
        );
        java.lang.invoke.VarHandle handle = lookup.findVarHandle(ApiException.class, "retryAttempts", int.class);
        handle.set(ex, 7);

        assertThat(ex.getRetryAttempts(), is(equalTo(7)));
    }

}
