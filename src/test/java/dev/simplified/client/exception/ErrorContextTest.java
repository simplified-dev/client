package dev.simplified.client.exception;

import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import feign.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests how an {@link ErrorContext} carries a status code, including one {@link HttpStatus} has
 * no constant for, and how an {@link ApiException} built over it reports that code.
 */
class ErrorContextTest {

    private static final String URL = "https://example.com/resource";

    private static feign.Response answer(int status) {
        return feign.Response.builder()
            .status(status)
            .reason("")
            .request(Request.create(Request.HttpMethod.GET, URL, Map.of(), null, StandardCharsets.UTF_8, null))
            .headers(Map.of())
            .body(new byte[0])
            .build();
    }

    private static ErrorContext context(HttpStatus status, int statusCode) {
        return new ErrorContext(status, statusCode, HttpMethod.GET, URL, Map.of(), Map.of(), new byte[0]);
    }

    @Test
    @DisplayName("findByCode answers the constant of a code HttpStatus names, and empty for one it does not")
    void findByCode() {
        assertThat(HttpStatus.findByCode(404), is(Optional.of(HttpStatus.NOT_FOUND)));
        assertThat(HttpStatus.findByCode(460), is(Optional.empty()));
    }

    @Test
    @DisplayName("fromFeign carries a code HttpStatus has no constant for as the status code, with UNKNOWN_ERROR as the status")
    void fromFeignCarriesAnUnknownCode() {
        ErrorContext context = ErrorContext.fromFeign(answer(460), new byte[0]);

        assertThat(context.status(), is(HttpStatus.UNKNOWN_ERROR));
        assertThat(context.statusCode(), is(460));
        assertThat(context.isKnownStatus(), is(false));
    }

    @Test
    @DisplayName("fromFeign carries a code HttpStatus names as its constant and its code")
    void fromFeignCarriesAKnownCode() {
        ErrorContext context = ErrorContext.fromFeign(answer(404), new byte[0]);

        assertThat(context.status(), is(HttpStatus.NOT_FOUND));
        assertThat(context.statusCode(), is(404));
        assertThat(context.isKnownStatus(), is(true));
    }

    @Test
    @DisplayName("fromFeign carries an origin's 999 as the UNKNOWN_ERROR constant it is, a known status")
    void fromFeignCarriesTheUnknownErrorCode() {
        ErrorContext context = ErrorContext.fromFeign(answer(999), new byte[0]);

        assertThat(context.status(), is(HttpStatus.UNKNOWN_ERROR));
        assertThat(context.statusCode(), is(999));
        assertThat(context.isKnownStatus(), is(true));
    }

    @Test
    @DisplayName("The constructor without a status code takes the code of the status")
    void statusConstructorTakesTheStatusCode() {
        ErrorContext context = new ErrorContext(HttpStatus.IO_ERROR, HttpMethod.GET, URL, Map.of(), Map.of(), new byte[0]);

        assertThat(context.statusCode(), is(HttpStatus.IO_ERROR.getCode()));
        assertThat(context.isKnownStatus(), is(true));
    }

    @Test
    @DisplayName("A status that is not the constant of the code is refused")
    void mismatchedStatusIsRefused() {
        IllegalArgumentException named = assertThrows(IllegalArgumentException.class, () -> context(HttpStatus.NOT_FOUND, 460));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> context(HttpStatus.UNKNOWN_ERROR, 404));

        assertThat(named.getMessage(), is(equalTo("Status 'NOT_FOUND' is not the status of code '460'")));
        assertThat(unknown.getMessage(), is(equalTo("Status 'UNKNOWN_ERROR' is not the status of code '404'")));
    }

    @Test
    @DisplayName("An ApiException over a code HttpStatus has no constant for reports the code and names it in its message")
    void apiExceptionReportsAnUnknownCode() {
        ApiException raised = new ApiException(null, "Test", context(HttpStatus.UNKNOWN_ERROR, 460));

        assertThat(raised.getStatusCode(), is(460));
        assertThat(raised.getStatus(), is(HttpStatus.UNKNOWN_ERROR));
        assertThat(raised.getMessage(), is(equalTo("GET " + URL + " failed with unknown status 460")));
    }

    @Test
    @DisplayName("An ApiException over a code HttpStatus names reports the code and the constant's message")
    void apiExceptionReportsAKnownCode() {
        ApiException raised = new ApiException(null, "Test", context(HttpStatus.NOT_FOUND, 404));

        assertThat(raised.getStatusCode(), is(404));
        assertThat(raised.getMessage(), is(equalTo("GET " + URL + " failed with status 404 Not Found")));
    }

    @Test
    @DisplayName("A retryable wrapper hands Feign the code the origin sent, not UNKNOWN_ERROR's")
    void retryableWrapperCarriesTheOriginCode() {
        ApiException raised = new ApiException(null, "Test", context(HttpStatus.UNKNOWN_ERROR, 460));
        Request request = Request.create(Request.HttpMethod.GET, URL, Map.of(), null, StandardCharsets.UTF_8, null);

        assertThat(new RetryableApiException(raised, 0L, request).status(), is(460));
    }

}
