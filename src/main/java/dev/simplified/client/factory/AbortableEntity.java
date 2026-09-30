package dev.simplified.client.factory;

import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.apache.hc.core5.http.io.EofSensorWatcher;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;

/**
 * Response entity whose body is read through an {@link EofSensorInputStream} that aborts the
 * exchange the body arrived on, whether or not the transport decodes the body.
 * <p>
 * The Apache transport reads a body sent without a {@code Content-Encoding} through an
 * {@code EofSensorInputStream} over its exchange, and a body sent with one through a decoding
 * stream over that, which offers no abort. This entity wraps the body as the transport hands it
 * back, decoded or not, and answers every {@link #getContent()} with one
 * {@code EofSensorInputStream} whose {@link EofSensorInputStream#abort() abort} aborts the
 * transport's stream over the exchange: the connection is closed at once rather than handed back
 * to the pool, so none of the body still to come is downloaded, and closing the response then
 * reads at most what the connection had already buffered. Reading and closing the body behave as
 * they do on the entity it wraps.
 * <p>
 * The wrapped entity's content is opened on the first read or close, so a body aborted before
 * either never has its decoding stream opened, which would read the encoding's header.
 */
final class AbortableEntity extends HttpEntityWrapper implements EofSensorWatcher {

    /**
     * The transport's stream over the exchange the body arrived on, whose abort closes its
     * connection.
     */
    private final @NotNull EofSensorInputStream exchange;

    /**
     * The stream every {@link #getContent()} answers, or {@code null} until the first.
     */
    private @Nullable EofSensorInputStream content;

    /**
     * Constructs a new {@code AbortableEntity} over a body as the transport hands it back.
     *
     * @param body the body, decoded when the transport decodes it
     * @param exchange the transport's stream over the exchange the body arrived on
     */
    AbortableEntity(@NotNull HttpEntity body, @NotNull EofSensorInputStream exchange) {
        super(body);
        this.exchange = exchange;
    }

    /**
     * Opens the body as the entity this one wraps answers it.
     *
     * @return the wrapped entity's content
     * @throws IOException if the wrapped entity cannot open its content
     */
    private @NotNull InputStream open() throws IOException {
        return super.getContent();
    }

    @Override
    public synchronized @NotNull InputStream getContent() {
        if (this.content == null)
            this.content = new EofSensorInputStream(new Deferred(), this);

        return this.content;
    }

    @Override
    public boolean eofDetected(@NotNull InputStream wrapped) {
        return true;
    }

    @Override
    public boolean streamClosed(@NotNull InputStream wrapped) {
        return true;
    }

    @Override
    public boolean streamAbort(@NotNull InputStream wrapped) throws IOException {
        this.exchange.abort();
        return false;
    }

    /**
     * Stream over the wrapped entity's content, opened on its first read or close.
     */
    private final class Deferred extends InputStream {

        /**
         * The wrapped entity's content, or {@code null} until it is first needed.
         */
        private @Nullable InputStream opened;

        /**
         * Opens the wrapped entity's content on the first call and answers it on every call.
         *
         * @return the wrapped entity's content
         * @throws IOException if the wrapped entity cannot open its content
         */
        private @NotNull InputStream opened() throws IOException {
            if (this.opened == null)
                this.opened = AbortableEntity.this.open();

            return this.opened;
        }

        @Override
        public int read() throws IOException {
            return this.opened().read();
        }

        @Override
        public int read(byte @NotNull [] buffer, int offset, int length) throws IOException {
            return this.opened().read(buffer, offset, length);
        }

        @Override
        public int available() throws IOException {
            return this.opened == null ? 0 : this.opened.available();
        }

        @Override
        public void close() throws IOException {
            this.opened().close();
        }

    }

}
