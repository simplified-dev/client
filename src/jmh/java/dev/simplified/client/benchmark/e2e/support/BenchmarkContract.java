package dev.simplified.client.benchmark.e2e.support;

import dev.simplified.client.request.Contract;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.Route;
import feign.RequestLine;

import java.io.InputStream;
import java.util.Map;

/**
 * Feign contract used by every {@code LibraryHotPathBenchmark} / {@code LoopbackThroughputBenchmark}
 * scenario. Declares one method per measured codepath so that route resolution, decoder selection,
 * and method-keyed metadata lookups all exercise their realistic shape under JMH.
 */
@Route("benchmark.local")
public interface BenchmarkContract extends Contract {

    @RequestLine("GET /small")
    Response<Map<String, Object>> smallJson();

    @RequestLine("GET /large")
    Response<Map<String, Object>> largeJson();

    @RequestLine("GET /cacheable")
    Response<Map<String, Object>> cacheable();

    @RequestLine("GET /error")
    Response<Map<String, Object>> error();

    @RequestLine("GET /rate-limited")
    Response<Map<String, Object>> rateLimited();

    @RequestLine("GET /stream")
    Response<InputStream> streaming();

    @RequestLine("POST /resource")
    Response<Map<String, Object>> mutateResource();

    @RequestLine("GET /type-a")
    Response<TypeA> typeA();

    @RequestLine("GET /type-b")
    Response<TypeB> typeB();

    @RequestLine("GET /type-c")
    Response<TypeC> typeC();

    @RequestLine("GET /type-d")
    Response<TypeD> typeD();

    record TypeA(String id, int value) { }

    record TypeB(String name, long timestamp) { }

    record TypeC(String label, double score) { }

    record TypeD(String tag, boolean enabled) { }

}
