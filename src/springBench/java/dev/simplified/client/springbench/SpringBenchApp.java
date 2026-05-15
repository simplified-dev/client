package dev.simplified.client.springbench;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.request.Timings;
import dev.simplified.gson.GsonSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Spring Boot entry point for the throughput bench. Wires a singleton
 * {@link Client} of {@link BenchMojangContract} pointing at the
 * {@link MockMojangServer} on {@code 127.0.0.1:47652}.
 *
 * <p>Three pool/timeout knobs are exposed as {@code -D} system properties so the
 * bench can be re-run without rebuilding:</p>
 * <ul>
 *   <li>{@code client.maxConnections} - HC5 pool {@code maxTotal}.
 *       Default: {@code 200} (matches {@link Timings#createDefault()}).</li>
 *   <li>{@code client.maxConnectionsPerRoute} - HC5 pool
 *       {@code defaultMaxPerRoute}. Default: {@code 50}.</li>
 *   <li>{@code client.connectTimeoutMs} - pool-acquire / TCP-connect timeout
 *       in ms. Default: {@code 5000}.</li>
 * </ul>
 *
 * <p>Tomcat thread pool and virtual-thread toggles live in
 * {@code application.yml} but are also overridable via the standard
 * Spring {@code -Dserver.tomcat.threads.max=...} and
 * {@code -Dspring.threads.virtual.enabled=...} properties.</p>
 */
@SpringBootApplication
public class SpringBenchApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringBenchApp.class, args);
    }

    /**
     * Trust-all {@link SSLContext} so the bench accepts the
     * {@link MockMojangServer}'s self-signed certificate. Bench-only - never
     * use this configuration in production.
     *
     * @return the trust-all SSL context
     * @throws Exception if TLS context construction fails
     */
    @Bean
    public SSLContext benchSslContext() throws Exception {
        TrustManager[] trustAll = {
            new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());
        return context;
    }

    /**
     * Bench-configurable {@link Timings}. Pool sizing, connect timeout, and
     * cache ceiling are pulled from {@code -D} system properties; everything
     * else matches {@link Timings#createDefault()}.
     *
     * @return the timings instance applied to the client below
     */
    @Bean
    public Timings benchTimings() {
        Timings base = Timings.createDefault();
        int maxConnections = intProp("client.maxConnections", base.maxConnections());
        int maxConnectionsPerRoute = intProp("client.maxConnectionsPerRoute", base.maxConnectionsPerRoute());
        long connectTimeoutMs = longProp("client.connectTimeoutMs", base.connectTimeout());

        Timings configured = new Timings(
            base.connectionTimeToLive(),
            base.connectionIdleTimeout(),
            base.connectionKeepAlive(),
            connectTimeoutMs,
            base.socketTimeout(),
            maxConnections,
            maxConnectionsPerRoute,
            base.maxCacheBytes(),
            base.cacheSafetyFallback()
        );

        System.out.println("[spring-bench] Pool: maxTotal=" + configured.maxConnections()
            + " maxPerRoute=" + configured.maxConnectionsPerRoute()
            + " connectTimeoutMs=" + configured.connectTimeout());
        return configured;
    }

    /**
     * Singleton {@link Client} wired to the mock server. Constructed once at
     * startup; the underlying HC5 pool serves every concurrent Spring request.
     *
     * @param sslContext the bench trust-all SSL context
     * @param timings the configured pool / timeout bundle
     * @return the client wrapping {@link BenchMojangContract}
     */
    @Bean
    public Client<BenchMojangContract> benchClient(SSLContext sslContext, Timings timings) {
        return Client.create(
            ClientConfig.builder(BenchMojangContract.class, GsonSettings.builder().build())
                .withSslContext(sslContext)
                .withTimings(timings)
                .build()
        );
    }

    /**
     * Convenience bean exposing the contract proxy directly so the controller
     * can inject the typed contract instead of the wrapping client.
     *
     * @param client the singleton client
     * @return the contract proxy
     */
    @Bean
    public BenchMojangContract benchContract(Client<BenchMojangContract> client) {
        return client.getContract();
    }

    private static int intProp(String key, int fallback) {
        String value = System.getProperty(key);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static long longProp(String key, long fallback) {
        String value = System.getProperty(key);
        return value == null ? fallback : Long.parseLong(value);
    }

}
