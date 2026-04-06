plugins {
    id("java-library")
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
}
