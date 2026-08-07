plugins {
    id("java-library")
    id("me.champeau.jmh") version "0.7.2"
    idea
}

group = "dev.simplified"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven(url = "https://central.sonatype.com/repository/maven-snapshots")
    maven(url = "https://jitpack.io")
}

val springBench: SourceSet by sourceSets.creating {
    // The default convention already wires src/springBench/java and src/springBench/resources;
    // we add the jmh resources directory for keystore reuse and the jmh output so the bench app
    // can call into TestClient (the package-private hook into Client used by both benchmark
    // surfaces).
    resources.srcDir("src/jmh/resources")
    compileClasspath += sourceSets["main"].output + sourceSets["jmh"].output
    runtimeClasspath += output + compileClasspath
}

tasks.named<JavaCompile>("compileSpringBenchJava") {
    // Spring MVC needs parameter names to resolve @PathVariable / @RequestParam
    // without explicit value() attributes. Bench-local; doesn't touch main jar.
    options.compilerArgs.add("-parameters")
}

configurations[springBench.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[springBench.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    // Simplified Libraries
    api("com.github.simplified-dev:collections") { version { strictly("7699a31") } }
    api("com.github.simplified-dev:utils") { version { strictly("036cc09") } }
    api("com.github.simplified-dev:reflection") { version { strictly("33b2f05") } }
    api("com.github.simplified-dev:gson-extras") { version { strictly("6421324") } }

    // JetBrains Annotations
    api(libs.annotations)

    // Logging
    api(libs.log4j2.api)

    // Lombok Annotations
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Serialization
    api(libs.gson)

    // HTTP Client
    api(libs.feign.gson)
    api(libs.feign.hc5)

    // XML Codec Support (XmlDecoder + XmlEncoder)
    api(libs.jackson.dataformat.xml)
    api(libs.rome)

    // Caching
    api(libs.caffeine)

    // JMH benchmarks (only used by the jmh source set)
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    // Spring bench - throwaway Spring Boot app for end-to-end concurrency measurement
    // Lives in src/springBench. Run via :runSpringBench / :runMockMojang Gradle tasks.
    "springBenchImplementation"(libs.spring.boot.web)
    "springBenchCompileOnly"(libs.lombok)
    "springBenchAnnotationProcessor"(libs.lombok)

    // Tests
    testImplementation(libs.hamcrest)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

jmh {
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(5)
    timeOnIteration.set("2s")
    warmup.set("1s")
    // benchmarkMode and timeUnit deliberately not set here so each @Benchmark class can
    // declare its own via @BenchmarkMode / @OutputTimeUnit (e.g. e2e suites that want both
    // AverageTime and Throughput, expressed in microseconds rather than nanoseconds).
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(project.layout.buildDirectory.file("reports/jmh/results.json"))

    val includeProp = providers.gradleProperty("jmhInclude").orNull
    if (includeProp != null) includes.set(listOf(includeProp))
    val forkProp = providers.gradleProperty("jmhFork").orNull
    if (forkProp != null) fork.set(forkProp.toInt())
    val warmupProp = providers.gradleProperty("jmhWarmup").orNull
    if (warmupProp != null) warmupIterations.set(warmupProp.toInt())
    val iterProp = providers.gradleProperty("jmhIter").orNull
    if (iterProp != null) iterations.set(iterProp.toInt())
    val threadsProp = providers.gradleProperty("jmhThreads").orNull
    if (threadsProp != null) threads.set(threadsProp.toInt())
}

idea {
    module {
        testSources.from(sourceSets["jmh"].java.srcDirs)
        testResources.from(sourceSets["jmh"].resources.srcDirs)
        testSources.from(springBench.java.srcDirs)
        testResources.from(springBench.resources.srcDirs)
    }
}

// ===== Spring bench run tasks =====
//
// Two long-running JVMs: the mock upstream HTTPS server and the Spring Boot app under test.
// Each takes -PsysProp.<key>=<value> Gradle properties and forwards them as -D system
// properties so the bench can be reconfigured (pool sizes, thread counts) without rebuilding.

tasks.register<JavaExec>("runMockMojang") {
    group = "spring-bench"
    description = "Starts the mock Mojang HTTPS server on 127.0.0.1:47652. Leave this running while load-testing."
    classpath = springBench.runtimeClasspath
    mainClass.set("dev.simplified.client.springbench.MockMojangApp")
    standardInput = System.`in`
    // Forward -Pmock.* gradle properties as -D system properties.
    project.properties
        .filterKeys { it.startsWith("mock.") }
        .forEach { (k, v) -> systemProperty(k, v.toString()) }
}

tasks.register<JavaExec>("runSpringBench") {
    group = "spring-bench"
    description = "Starts the Spring Boot benchmark app on port 8080. Hit GET /mojang/user/{username}."
    classpath = springBench.runtimeClasspath
    mainClass.set("dev.simplified.client.springbench.SpringBenchApp")
    standardInput = System.`in`
    // Forward -Pclient.* / -Pserver.* / -Pspring.* gradle properties as -D system properties.
    project.properties
        .filterKeys { it.startsWith("client.") || it.startsWith("server.") || it.startsWith("spring.") }
        .forEach { (k, v) -> systemProperty(k, v.toString()) }
}

tasks.register<JavaExec>("runBenchDriver") {
    group = "spring-bench"
    description = "Runs the Java load driver against the Spring bench. Pass -Pdriver.<key>=<value> for CLI flags."
    classpath = springBench.runtimeClasspath
    mainClass.set("dev.simplified.client.springbench.BenchLoadDriver")
    // Translate -Pdriver.url=foo -> --url foo, -Pdriver.vus=500 -> --vus 500, etc.
    doFirst {
        val cliArgs = mutableListOf<String>()
        project.properties
            .filterKeys { it.startsWith("driver.") }
            .forEach { (k, v) ->
                cliArgs.add("--" + k.removePrefix("driver."))
                cliArgs.add(v.toString())
            }
        args = cliArgs
    }
}
