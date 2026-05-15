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
    maven(url = "https://jitpack.io")
}

dependencies {
    // Simplified Libraries
    api("com.github.simplified-dev:collections") { version { strictly("a5f41e0") } }
    api("com.github.simplified-dev:utils") { version { strictly("5c6c96a") } }
    api("com.github.simplified-dev:reflection") { version { strictly("ed2e17c") } }
    api("com.github.simplified-dev:gson-extras") { version { strictly("71aeadd") } }

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
    }
}
