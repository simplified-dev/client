# Client

HTTP client library built on [OpenFeign](https://github.com/OpenFeign/feign) for declarative REST API consumption. Features automatic response/error decoding, bucket-based rate limiting, request/response interceptors, route discovery, Cloudflare cache status parsing, retry-after handling, and timed connection socket factories.

> [!IMPORTANT]
> This library is under active development. APIs may change between releases until a stable `1.0.0` is published.

## Table of Contents

- [Features](#features)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Gradle (Kotlin DSL)](#gradle-kotlin-dsl)
  - [Gradle (Groovy DSL)](#gradle-groovy-dsl)
- [Usage](#usage)
- [Architecture](#architecture)
  - [Package Overview](#package-overview)
  - [Project Structure](#project-structure)
- [Dependencies](#dependencies)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Declarative HTTP clients** - Define REST endpoints as annotated Java interfaces using OpenFeign
- **Automatic decoding** - Response and error bodies are decoded via Gson with configurable decoders
- **Rate limiting** - Bucket-based throttling with configurable rate limit policies per endpoint
- **Retry-after handling** - Automatic parsing and respect of `Retry-After` headers
- **Request/response interceptors** - Pluggable interceptor pipeline for headers, logging, and transforms
- **Route discovery** - Dynamic route resolution with provider-based discovery
- **Cloudflare integration** - Parses `CF-Cache-Status` headers for cache hit/miss tracking
- **Timed socket factories** - Connection-level timing for plain and TLS sockets
- **Reactive endpoints** - Support for reactive-style endpoint definitions

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [Java](https://adoptium.net/) | **21+** | Required (LTS recommended) |
| [Gradle](https://gradle.org/) | **9.4+** | Or use the included `gradlew` wrapper |
| [Git](https://git-scm.com/) | 2.x+ | For cloning the repository |

### Installation

Published via [JitPack](https://jitpack.io/#simplified-dev/client). Add the JitPack repository and dependency to your build file.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.simplified-dev:client:master-SNAPSHOT")
}
```

### Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.simplified-dev:client:master-SNAPSHOT'
}
```

> [!TIP]
> Replace `master-SNAPSHOT` with a specific commit hash or tag for reproducible builds.

## Usage

Create a Feign-based HTTP client with built-in rate limiting and response decoding:

```java
import dev.simplified.client.Client;
import dev.simplified.client.ratelimit.RateLimitConfig;

// Build a client targeting your API
Client client = Client.builder()
    .baseUrl("https://api.example.com")
    .rateLimitConfig(RateLimitConfig.builder()
        .maxRequests(100)
        .perSeconds(60)
        .build())
    .build();
```

Define endpoints as annotated interfaces:

```java
import dev.simplified.client.request.Endpoint;
import dev.simplified.client.request.HttpMethod;

public interface UserApi extends Endpoint {

    @RequestLine("GET /users/{id}")
    Response<User> getUser(@Param("id") String id);
}
```

## Architecture

### Package Overview

| Package | Description |
|---------|-------------|
| `dev.simplified.client` | Core `Client` builder and configuration |
| `dev.simplified.client.decoder` | Response and error decoders (`ClientErrorDecoder`, `InternalErrorDecoder`, `InternalResponseDecoder`) |
| `dev.simplified.client.exception` | Exception hierarchy (`ApiException`, `ApiDecodeException`, `RateLimitException`, `RetryableApiException`) |
| `dev.simplified.client.factory` | Timed connection socket factories for plain and TLS connections |
| `dev.simplified.client.interceptor` | Request and response interceptor interfaces |
| `dev.simplified.client.ratelimit` | Rate limit management with bucket-based throttling (`RateLimitManager`, `RateLimit`, `RateLimitBucket`, `RateLimitConfig`) |
| `dev.simplified.client.request` | Endpoint definitions, HTTP methods, request timings, parameter expanders |
| `dev.simplified.client.response` | Response wrappers, HTTP status/state enums, Cloudflare cache status, retry-after parsing |
| `dev.simplified.client.route` | Route discovery and dynamic route resolution |

### Project Structure

```
client/
├── src/main/java/dev/simplified/client/
│   ├── Client.java
│   ├── decoder/
│   │   ├── ClientErrorDecoder.java
│   │   ├── InternalErrorDecoder.java
│   │   └── InternalResponseDecoder.java
│   ├── exception/
│   │   ├── ApiDecodeException.java
│   │   ├── ApiException.java
│   │   ├── RateLimitException.java
│   │   └── RetryableApiException.java
│   ├── factory/
│   │   ├── TimedPlainConnectionSocketFactory.java
│   │   └── TimedSecureConnectionSocketFactory.java
│   ├── interceptor/
│   │   ├── InternalRequestInterceptor.java
│   │   └── InternalResponseInterceptor.java
│   ├── ratelimit/
│   │   ├── RateLimit.java
│   │   ├── RateLimitBucket.java
│   │   ├── RateLimitConfig.java
│   │   └── RateLimitManager.java
│   ├── request/
│   │   ├── Endpoint.java
│   │   ├── HttpMethod.java
│   │   ├── ReactiveEndpoint.java
│   │   ├── Request.java
│   │   ├── Timings.java
│   │   └── expander/
│   ├── response/
│   │   ├── CloudflareCacheStatus.java
│   │   ├── HttpState.java
│   │   ├── HttpStatus.java
│   │   ├── NetworkDetails.java
│   │   ├── Response.java
│   │   └── RetryAfterParser.java
│   └── route/
│       ├── DynamicRoute.java
│       ├── DynamicRouteProvider.java
│       ├── Route.java
│       └── RouteDiscovery.java
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── LICENSE.md
└── lombok.config
```

## Dependencies

| Dependency | Version | Scope |
|------------|---------|-------|
| [OpenFeign (feign-gson)](https://github.com/OpenFeign/feign) | 13.11 | API |
| [OpenFeign (feign-httpclient)](https://github.com/OpenFeign/feign) | 13.11 | API |
| [Gson](https://github.com/google/gson) | 2.11.0 | API |
| [Log4j2](https://logging.apache.org/log4j/) | 2.25.3 | API |
| [JetBrains Annotations](https://github.com/JetBrains/java-annotations) | 26.0.2 | API |
| [Lombok](https://projectlombok.org/) | 1.18.36 | Compile-only |
| [collections](https://github.com/Simplified-Dev/collections) | master-SNAPSHOT | API (Simplified-Dev) |
| [utils](https://github.com/Simplified-Dev/utils) | master-SNAPSHOT | API (Simplified-Dev) |
| [reflection](https://github.com/Simplified-Dev/reflection) | master-SNAPSHOT | API (Simplified-Dev) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style guidelines, and how to submit a pull request.

## License

This project is licensed under the **Apache License 2.0** - see [LICENSE.md](LICENSE.md) for the full text.
