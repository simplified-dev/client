package dev.simplified.client.codec;

import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import dev.simplified.client.ClientConfig;
import feign.RequestTemplate;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.function.Function;

/**
 * Feign {@link Encoder} that serializes a typed DTO back into valid RSS or Atom XML by
 * routing through ROME's {@link SyndFeedOutput}.
 * <p>
 * ROME is the only piece of this pipeline that knows which {@code xmlns:*} declarations
 * belong on which root element for which feed version; Jackson-based round-trips lose
 * namespace bookkeeping because neither {@link com.fasterxml.jackson.databind.JsonNode}
 * nor Gson's {@link com.google.gson.JsonElement} carries namespace URIs. Delegating the
 * write path to ROME keeps the output bit-for-bit compatible with strict RSS aggregators.
 *
 * <p><b>Usage.</b> Callers supply a type token and a mapping function that converts a DTO
 * instance into a {@link SyndFeed}:
 * <pre>{@code
 * XmlEncoder encoder = XmlEncoder.of(
 *     HypixelForum.class,
 *     HypixelForum::toSyndFeed,
 *     "rss_2.0"
 * );
 * }</pre>
 *
 * <p>The encoder checks at runtime that every body passed to
 * {@link #encode(Object, Type, RequestTemplate)} is an instance of the declared type and
 * throws {@link EncodeException} otherwise, giving a clear error when a client attempts
 * to serialize an unexpected type through this encoder. For clients that only need to
 * serialize DTOs to an XML {@link String} (independent of Feign), the public
 * {@link #serialize(Object)} convenience method performs the same conversion without
 * touching a {@link RequestTemplate}.
 *
 * <p><b>Lifecycle.</b> This encoder is typically returned from
 * {@link ClientConfig#getEncoderFactory()} when the surrounding client exposes RSS/Atom write
 * endpoints, or instantiated on demand as a standalone serializer for clients whose
 * remote APIs are read-only (such as the Hypixel forum feeds).
 *
 * @see ClientConfig#getEncoderFactory()
 * @see XmlDecoder
 * @see SyndFeedOutput
 */
@Getter
public final class XmlEncoder implements Encoder {

    /** The default ROME feed type, covering the overwhelming majority of RSS 2.0 feeds. */
    public static final @NotNull String DEFAULT_FEED_TYPE = "rss_2.0";

    /** The runtime type token used to validate incoming bodies before delegating to the mapper. */
    private final @NotNull Class<?> expectedType;

    /** The mapping function that converts a typed DTO into a ROME {@link SyndFeed}. */
    private final @NotNull Function<Object, SyndFeed> feedMapper;

    /** The ROME feed type string (e.g. {@code "rss_2.0"}, {@code "atom_1.0"}) applied when the mapper does not set one. */
    private final @NotNull String feedType;

    /**
     * Constructs a new {@code XmlEncoder}. Not public - call
     * {@link #of(Class, Function)} or {@link #of(Class, Function, String)} for type-safe
     * construction.
     *
     * @param expectedType the runtime type token validated against every body
     * @param feedMapper the mapping function converting a typed DTO into a {@link SyndFeed}
     * @param feedType the ROME feed type string to apply when the mapper returns a feed
     *                 with no explicit type set
     */
    private XmlEncoder(@NotNull Class<?> expectedType, @NotNull Function<Object, SyndFeed> feedMapper, @NotNull String feedType) {
        this.expectedType = expectedType;
        this.feedMapper = feedMapper;
        this.feedType = feedType;
    }

    /**
     * Creates a new {@code XmlEncoder} for the given DTO type with the
     * {@linkplain #DEFAULT_FEED_TYPE default feed type}.
     *
     * @param <T> the DTO type handled by this encoder
     * @param expectedType the class token for the DTO type
     * @param feedMapper the mapping function converting an instance of {@code T} into a
     *                   {@link SyndFeed}
     * @return a new type-safe {@code XmlEncoder} bound to {@code expectedType}
     */
    public static <T> @NotNull XmlEncoder of(@NotNull Class<T> expectedType, @NotNull Function<T, SyndFeed> feedMapper) {
        return of(expectedType, feedMapper, DEFAULT_FEED_TYPE);
    }

    /**
     * Creates a new {@code XmlEncoder} for the given DTO type and ROME feed type.
     *
     * @param <T> the DTO type handled by this encoder
     * @param expectedType the class token for the DTO type
     * @param feedMapper the mapping function converting an instance of {@code T} into a
     *                   {@link SyndFeed}
     * @param feedType the ROME feed type string (e.g. {@code "rss_2.0"},
     *                 {@code "atom_1.0"}) applied when the mapper returns a feed with no
     *                 explicit type set
     * @return a new type-safe {@code XmlEncoder} bound to {@code expectedType}
     */
    @SuppressWarnings("unchecked")
    public static <T> @NotNull XmlEncoder of(@NotNull Class<T> expectedType, @NotNull Function<T, SyndFeed> feedMapper, @NotNull String feedType) {
        Function<Object, SyndFeed> erased = (Function<Object, SyndFeed>) feedMapper;
        return new XmlEncoder(expectedType, erased, feedType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void encode(@NotNull Object object, @NotNull Type bodyType, @NotNull RequestTemplate template) throws EncodeException {
        String body = this.serialize(object);
        template.body(body);
    }

    /**
     * Serializes a typed DTO into a RSS or Atom XML string by delegating to
     * {@link SyndFeedOutput}.
     * <p>
     * This is the primary entry point for callers that want to dump a DTO back to XML
     * independently of a Feign {@link RequestTemplate}.
     *
     * @param object the DTO to serialize, which must be an instance of
     *               {@link #getExpectedType()}
     * @return the serialized XML document
     * @throws EncodeException if {@code object} is not an instance of
     *                         {@link #getExpectedType()}, if the mapper fails, or if ROME
     *                         rejects the produced {@link SyndFeed}
     */
    public @NotNull String serialize(@NotNull Object object) throws EncodeException {
        if (!this.expectedType.isInstance(object))
            throw new EncodeException(String.format(
                "XmlEncoder bound to '%s' cannot serialize '%s'",
                this.expectedType.getName(),
                object.getClass().getName()
            ));

        SyndFeed feed = this.feedMapper.apply(object);

        if (feed.getFeedType() == null || feed.getFeedType().isEmpty())
            feed.setFeedType(this.feedType);

        try {
            StringWriter writer = new StringWriter();
            new SyndFeedOutput().output(feed, writer);
            return writer.toString();
        } catch (FeedException | java.io.IOException ex) {
            throw new EncodeException(
                String.format("Failed to serialize '%s' to feed type '%s'", this.expectedType.getName(), this.feedType),
                ex
            );
        }
    }

}
