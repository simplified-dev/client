package dev.simplified.client.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * Prebuilt {@link UnaryOperator} rewriters that fix common tree-shape quirks produced
 * when parsing real-world RSS feeds through {@link XmlDecoder}.
 * <p>
 * Each transformer is a stateless, idempotent {@link UnaryOperator} that can be passed
 * directly to
 * {@link XmlDecoder#XmlDecoder(com.google.gson.Gson,
 *        com.fasterxml.jackson.dataformat.xml.XmlMapper, UnaryOperator)
 * XmlDecoder}'s three-argument constructor. They are no-ops on trees that do not
 * exhibit the target quirk, so installing one unconditionally on an RSS client is
 * safe even when the quirk is not present in every response.
 * <p>
 * These transformers address <b>tree shape</b>, not <b>feed semantics</b>. For feed
 * version normalization (RSS 0.9x vs 1.0 RDF vs 2.0 vs Atom) or extension-element
 * resolution (Media RSS, iTunes, Dublin Core), reach for ROME's
 * {@link com.rometools.rome.feed.synd.SyndFeed SyndFeed} instead - it normalizes
 * every flavor behind a single model.
 *
 * @see XmlDecoder
 */
@UtilityClass
public final class RssTreeTransformers {

    /**
     * Collapses the {@code channel.link} array produced by the RSS 2.0
     * {@code <link>} vs {@code <atom:link rel="self">} namespace collision down to
     * the canonical text-content element.
     * <p>
     * RSS 2.0 feeds that declare {@code xmlns:atom="http://www.w3.org/2005/Atom"}
     * almost universally emit both a standard
     * {@code <link>https://example.com/</link>} element and an
     * {@code <atom:link rel="self" .../>} sibling inside {@code <channel>}.
     * {@link XmlDecoder}'s {@linkplain XmlDecoder#defaultXmlMapper() default
     * XmlMapper} strips namespace prefixes, so the two elements collapse to a single
     * local name and the resulting tree exposes {@code channel.link} as a
     * heterogeneous {@link JsonArray} of the form
     * {@code [ "https://example.com/", { "href": "...", "rel": "self", ... } ]}.
     * <p>
     * DTOs that bind {@code link} to a {@link String} cannot consume that array, so
     * this transformer walks the tree, locates the {@code channel.link} array, and
     * replaces it with its first primitive string element. It is a no-op when
     * {@code channel.link} is already a scalar or when no {@code <channel>} object
     * exists at the root, so it is safe to install on any RSS 2.0 client
     * unconditionally.
     * <p>
     * This is by far the most common XML-to-JSON collision encountered in the wild -
     * almost every major CMS (WordPress, Ghost, Substack, Feedburner, XenForo) and
     * every major video/podcast feed (YouTube, Mastodon, Spotify) emits the Atom
     * self-link alongside the standard {@code <link>}.
     */
    public static final @NotNull UnaryOperator<JsonElement> ATOM_SELF_LINK_COLLISION_FIX = tree -> {
        if (!tree.isJsonObject())
            return tree;

        JsonObject root = tree.getAsJsonObject();
        JsonElement channelElement = root.get("channel");

        if (channelElement == null || !channelElement.isJsonObject())
            return tree;

        JsonObject channel = channelElement.getAsJsonObject();
        JsonElement linkElement = channel.get("link");

        if (linkElement == null || !linkElement.isJsonArray())
            return tree;

        JsonArray linkArray = linkElement.getAsJsonArray();

        for (JsonElement candidate : linkArray) {
            if (candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString()) {
                channel.add("link", candidate);
                return tree;
            }
        }

        return tree;
    };

}
