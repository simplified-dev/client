package dev.simplified.client.springbench;

/**
 * Standalone entry point that starts the {@link MockMojangServer} and parks the
 * main thread until {@code Enter} is pressed (or the JVM receives SIGTERM via the
 * shutdown hook).
 *
 * <p>Launch via {@code ./gradlew :runMockMojang} from one terminal; leave it
 * running while the Spring bench in another terminal handles load.</p>
 */
public final class MockMojangApp {

    private MockMojangApp() { }

    public static void main(String[] args) throws Exception {
        MockMojangServer server = new MockMojangServer();
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "mock-mojang-shutdown"));

        System.out.println("Mock Mojang server listening on https://127.0.0.1:" + MockMojangServer.PORT);
        System.out.println("  Cache-miss path: GET /minecraft/profile/lookup/name/{username}");
        System.out.println("  Cache-hit path:  GET /minecraft/profile/lookup/name-cached/{username}");
        System.out.println("Press Ctrl+C to stop.");

        // Park indefinitely. The shutdown hook handles graceful stop on Ctrl+C / SIGTERM.
        Thread.currentThread().join();
    }

}
