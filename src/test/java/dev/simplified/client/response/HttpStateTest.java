package dev.simplified.client.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class HttpStateTest {

    @Test
    @DisplayName("A client error status is an error")
    void clientErrorIsAnError() {
        assertThat(HttpStatus.NOT_FOUND.getState(), is(HttpState.CLIENT_ERROR));
        assertThat(HttpState.CLIENT_ERROR.isError(), is(true));
    }

    @Test
    @DisplayName("Success, redirection and informational statuses are not errors")
    void nonErrorStates() {
        assertThat(HttpState.SUCCESS.isError(), is(false));
        assertThat(HttpState.REDIRECTION.isError(), is(false));
        assertThat(HttpState.INFORMATIONAL.isError(), is(false));
    }

}
