package dev.simplified.client.fetch;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import dev.simplified.client.exception.UrlFetchException;
import dev.simplified.client.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class UrlFetcherTest {

    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/hello", exchange -> {
            byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/big", exchange -> {
            byte[] body = new byte[64 * 1024];
            java.util.Arrays.fill(body, (byte) 'a');
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.createContext("/bad", exchange -> {
            byte[] body = "nope".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        this.server.start();
        this.baseUri = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (this.server != null) this.server.stop(0);
    }

    private UrlFetcher buildFetcher(long maxBytes) {
        return UrlFetcher.create(UrlFetcherConfig.builder(new Gson()).withMaxBodyBytes(maxBytes).build());
    }

    @Test
    @DisplayName("Fetches a small body and decodes by Content-Type charset")
    void fetchesSmallBody() {
        Response<String> result = buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).get(this.baseUri.resolve("/hello"));
        assertThat(result.getStatus().getCode(), is(equalTo(200)));
        assertThat(result.getBody(), is(equalTo("hello world")));
        assertThat(result.getContentType().orElse(""), containsString("text/plain"));
    }

    @Test
    @DisplayName("Refuses bodies that exceed the configured cap")
    void refusesOversizedBody() {
        UrlFetcher fetcher = buildFetcher(1024);
        try {
            fetcher.bytes(this.baseUri.resolve("/big"));
        } catch (UrlFetchException.BodyCapExceeded expected) {
            assertThat(expected.getMessage(), containsString("exceeded cap"));
            return;
        }
        throw new AssertionError("Expected BodyCapExceeded for oversized body");
    }

    @Test
    @DisplayName("Throws on non-2xx responses")
    void throwsOnFailureStatus() {
        try {
            buildFetcher(UrlFetcherConfig.DEFAULT_MAX_BODY_BYTES).bytes(this.baseUri.resolve("/bad"));
        } catch (UrlFetchException expected) {
            assertThat(expected.getMessage(), containsString("503"));
            return;
        }
        throw new AssertionError("Expected UrlFetchException for HTTP 503");
    }

}
