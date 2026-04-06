package dev.simplified.client.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.simplified.client.Client;
import dev.simplified.client.decoder.InternalResponseDecoder;
import feign.FeignException;
import feign.Response;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.function.UnaryOperator;

/**
 * Feign {@link Decoder} that reads XML responses through Jackson's
 * {@link XmlMapper#readTree(InputStream)}, converts the resulting
 * {@link JsonNode Jackson tree} into a Gson {@link JsonElement} tree, and hands the
 * tree off to a shared {@link Gson} instance for final binding into a typed DTO.
 * <p>
 * The design keeps Jackson strictly in the role of an XML parser: no typed Jackson
 * binding occurs, so every custom {@link com.google.gson.TypeAdapter TypeAdapter},
 * {@link com.google.gson.TypeAdapterFactory TypeAdapterFactory}, and
 * {@link com.google.gson.annotations.SerializedName @SerializedName} annotation
 * already registered on the caller's {@link Gson} instance fires exactly as it would
 * for JSON responses. DTO classes stay 100% Gson-annotated and never need to
 * reference Jackson types.
 *
 * <p><b>Tree conventions.</b> The {@linkplain #defaultXmlMapper() default} {@link XmlMapper}
 * is configured so that:
 * <ul>
 *   <li>Element local names become object keys in the tree (namespace prefixes are stripped).</li>
 *   <li>XML attributes become sibling fields alongside child elements - consumers relying on
 *       attribute disambiguation should either ensure attribute names do not collide with
 *       child element names or supply a custom {@link XmlMapper}.</li>
 *   <li>Mixed-content text (an element carrying both attributes and text content) is exposed
 *       under the key {@value #TEXT_KEY}, letting DTOs bind it via
 *       {@code @SerializedName("$")} without colliding with attribute or child-element names.</li>
 * </ul>
 *
 * <p><b>Tree transformation.</b> An optional {@link UnaryOperator} allows callers to rewrite
 * the converted {@link JsonElement} tree before it is passed to Gson. This is the intended
 * escape hatch for feed-specific quirks - for example, folding a same-local-name sibling
 * collision (such as RSS {@code <link>} versus {@code <atom:link>}) down to the expected
 * scalar before Gson attempts to bind the DTO field.
 *
 * <p><b>Lifecycle.</b> This decoder is typically returned from {@link Client#configureDecoder()}
 * and wrapped by {@link InternalResponseDecoder}, which handles {@link InputStream} and
 * {@code byte[]} return types and closes the response body after decoding. Exceptions thrown
 * by this decoder are caught by {@link InternalResponseDecoder} and re-wrapped as
 * {@link dev.simplified.client.exception.ApiDecodeException ApiDecodeException}, so this
 * class does not need to do its own exception wrapping.
 *
 * @see Client#configureDecoder()
 * @see XmlEncoder
 * @see InternalResponseDecoder
 */
@Getter
public final class XmlDecoder implements Decoder {

    /**
     * The object-key under which Jackson emits mixed-content text when an XML element
     * carries both attributes and text content. Chosen to match the common
     * attribute-prefix JSON convention and to avoid collisions with ordinary element names.
     */
    public static final @NotNull String TEXT_KEY = "$";

    /** The Gson instance used for the final {@link JsonElement} to DTO binding. */
    private final @NotNull Gson gson;

    /** The Jackson {@link XmlMapper} used to parse the raw XML into a {@link JsonNode} tree. */
    private final @NotNull XmlMapper xmlMapper;

    /**
     * An optional rewriter applied to the converted Gson {@link JsonElement} tree before it
     * is passed to Gson, used as an escape hatch for feed-specific quirks such as
     * namespace-prefix collisions. Defaults to {@link UnaryOperator#identity()}.
     */
    private final @NotNull UnaryOperator<JsonElement> treeTransformer;

    /**
     * Constructs a new {@code XmlDecoder} using the {@linkplain #defaultXmlMapper() default
     * XmlMapper} and an identity tree transformer.
     *
     * @param gson the Gson instance used for the final {@link JsonElement} to DTO binding
     */
    public XmlDecoder(@NotNull Gson gson) {
        this(gson, defaultXmlMapper(), UnaryOperator.identity());
    }

    /**
     * Constructs a new {@code XmlDecoder} with a caller-provided {@link XmlMapper} and an
     * identity tree transformer.
     *
     * @param gson the Gson instance used for the final {@link JsonElement} to DTO binding
     * @param xmlMapper the Jackson {@link XmlMapper} used to parse raw XML into a
     *                  {@link JsonNode} tree
     */
    public XmlDecoder(@NotNull Gson gson, @NotNull XmlMapper xmlMapper) {
        this(gson, xmlMapper, UnaryOperator.identity());
    }

    /**
     * Constructs a new {@code XmlDecoder} with a caller-provided {@link XmlMapper} and a
     * tree rewriter applied before Gson binding.
     *
     * @param gson the Gson instance used for the final {@link JsonElement} to DTO binding
     * @param xmlMapper the Jackson {@link XmlMapper} used to parse raw XML into a
     *                  {@link JsonNode} tree
     * @param treeTransformer a rewriter applied to the converted Gson {@link JsonElement}
     *                        tree before it is passed to Gson, used as an escape hatch for
     *                        feed-specific quirks such as namespace-prefix collisions
     */
    public XmlDecoder(@NotNull Gson gson, @NotNull XmlMapper xmlMapper, @NotNull UnaryOperator<JsonElement> treeTransformer) {
        this.gson = gson;
        this.xmlMapper = xmlMapper;
        this.treeTransformer = treeTransformer;
    }

    /**
     * Builds the default {@link XmlMapper} used by the no-argument constructor.
     * <p>
     * The returned mapper is configured so that mixed-content text is emitted under the
     * key {@value #TEXT_KEY} and single-child elements are not wrapped in an intermediate
     * array, matching the conventions documented on this class.
     *
     * @return a freshly constructed {@link XmlMapper} configured for Gson tree conversion
     */
    public static @NotNull XmlMapper defaultXmlMapper() {
        JacksonXmlModule module = new JacksonXmlModule();
        module.setDefaultUseWrapper(false);
        module.setXMLTextElementName(TEXT_KEY);
        return new XmlMapper(module);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object decode(@NotNull Response response, @NotNull Type type) throws IOException, DecodeException, FeignException {
        if (response.body() == null)
            return null;

        try (InputStream in = response.body().asInputStream()) {
            JsonNode jacksonTree = this.xmlMapper.readTree(in);
            JsonElement gsonTree = toGsonTree(jacksonTree);
            JsonElement transformed = this.treeTransformer.apply(gsonTree);
            return this.gson.fromJson(transformed, type);
        }
    }

    /**
     * Recursively converts a Jackson {@link JsonNode} tree into an equivalent Gson
     * {@link JsonElement} tree, preserving object keys, array ordering, and primitive
     * types (text, boolean, number).
     *
     * @param node the Jackson node to convert, which may be {@code null} or a null node
     * @return the equivalent Gson {@link JsonElement}, or {@link JsonNull#INSTANCE} if
     *         the input is {@code null} or a JSON null
     */
    private static @NotNull JsonElement toGsonTree(JsonNode node) {
        if (node == null || node.isNull())
            return JsonNull.INSTANCE;

        if (node.isTextual())
            return new JsonPrimitive(node.asText());

        if (node.isNumber())
            return new JsonPrimitive(node.numberValue());

        if (node.isBoolean())
            return new JsonPrimitive(node.booleanValue());

        if (node.isArray()) {
            JsonArray array = new JsonArray(node.size());

            for (JsonNode child : node)
                array.add(toGsonTree(child));

            return array;
        }

        if (node.isObject()) {
            JsonObject object = new JsonObject();
            Iterator<String> names = node.fieldNames();

            while (names.hasNext()) {
                String name = names.next();
                object.add(name, toGsonTree(node.get(name)));
            }

            return object;
        }

        return new JsonPrimitive(node.asText());
    }

}
