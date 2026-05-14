package dev.simplified.client.benchmark.e2e.support;

import java.nio.charset.StandardCharsets;

/**
 * Pre-computed canned byte[] response payloads keyed by request path. Instantiated once per
 * benchmark trial via {@link org.openjdk.jmh.annotations.Setup} so allocation cost stays out
 * of the measured region.
 */
public final class FixtureBodies {

    public static final byte[] SMALL_JSON = small().getBytes(StandardCharsets.UTF_8);
    public static final byte[] LARGE_JSON = large().getBytes(StandardCharsets.UTF_8);
    public static final byte[] ERROR_JSON = error().getBytes(StandardCharsets.UTF_8);
    public static final byte[] STREAM_BODY = streamBody().getBytes(StandardCharsets.UTF_8);
    public static final byte[] TYPE_A_JSON = "{\"id\":\"a-001\",\"value\":42}".getBytes(StandardCharsets.UTF_8);
    public static final byte[] TYPE_B_JSON = "{\"name\":\"beta\",\"timestamp\":1700000000}".getBytes(StandardCharsets.UTF_8);
    public static final byte[] TYPE_C_JSON = "{\"label\":\"gamma\",\"score\":3.14}".getBytes(StandardCharsets.UTF_8);
    public static final byte[] TYPE_D_JSON = "{\"tag\":\"delta\",\"enabled\":true}".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EMPTY = new byte[0];

    private FixtureBodies() { }

    private static String small() {
        return "{\"id\":\"f3a8\",\"name\":\"Widget\",\"qty\":7,\"active\":true,\"tags\":[\"alpha\",\"beta\"]}";
    }

    private static String large() {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("{\"records\":[");
        for (int i = 0; i < 400; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"")
              .append(String.format("%08x", i))
              .append("\",\"value\":")
              .append(i)
              .append(",\"label\":\"record-")
              .append(i)
              .append("\",\"enabled\":")
              .append((i & 1) == 0)
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String error() {
        return "{\"error\":\"Internal Server Error\",\"code\":503,\"message\":\"backend timeout\"}";
    }

    private static String streamBody() {
        StringBuilder sb = new StringBuilder(2048);
        for (int i = 0; i < 64; i++)
            sb.append("streaming-chunk-").append(i).append('\n');
        return sb.toString();
    }

}
