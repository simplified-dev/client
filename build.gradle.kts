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
    api("com.github.simplified-dev:collections:master-SNAPSHOT")
    api("com.github.simplified-dev:utils:master-SNAPSHOT")
    api("com.github.simplified-dev:reflection:master-SNAPSHOT")

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
    api(libs.feign.httpclient)

    // XML Codec Support (XmlDecoder + XmlEncoder)
    api(libs.jackson.dataformat.xml)
    api(libs.rome)

    // Caching
    api(libs.caffeine)

    // JMH benchmarks (only used by the jmh source set)
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

jmh {
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(5)
    timeOnIteration.set("2s")
    warmup.set("1s")
    benchmarkMode.set(listOf("avgt"))
    timeUnit.set("ns")
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(project.layout.buildDirectory.file("reports/jmh/results.json"))
}

idea {
    module {
        testSources.from(sourceSets["jmh"].java.srcDirs)
        testResources.from(sourceSets["jmh"].resources.srcDirs)
    }
}
