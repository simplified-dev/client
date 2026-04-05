package dev.simplified.client.request.expander;

import feign.Param;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A Feign {@link Param.Expander} that converts a {@code String[]} into a comma-separated
 * list of double-quoted elements suitable for inclusion in a query parameter or URI template.
 * <p>
 * When the supplied value is a {@code String[]}, each element is wrapped in double quotes
 * and joined with commas. For example, the array {@code ["a", "b", "c"]} is expanded to
 * {@code "a","b","c"}. If the value is not a {@code String[]}, it is converted to its
 * string representation via {@link String#valueOf(Object)}.
 * <p>
 * This expander is intended for use with the {@link Param#expander()} attribute on
 * Feign endpoint method parameters:
 * <pre>{@code
 * @RequestLine("GET /items?ids={ids}")
 * List<Item> getItems(@Param(value = "ids", expander = StringArrayQuoteExpander.class) String[] ids);
 * }</pre>
 *
 * @see Param.Expander
 */
public final class StringArrayQuoteExpander implements Param.Expander {

    /** {@inheritDoc} */
    @Override
    public String expand(@NotNull Object value) {
        if (String[].class.isAssignableFrom(value.getClass())) {
            return Arrays.stream((String[]) value)
                .map(str -> String.format("\"%s\"", str))
                .collect(Collectors.joining(","));
        }

        return String.valueOf(value);
    }

}
