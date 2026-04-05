# Client

Declarative HTTP client library built on OpenFeign with rate limiting, response decoding, and route discovery.

## Package Structure

- `dev.simplified.client` - `Client` (main entry point, Feign builder)
- `dev.simplified.client.decoder` - `ClientErrorDecoder`, `InternalErrorDecoder`, `InternalResponseDecoder`
- `dev.simplified.client.exception` - `ApiException`, `ApiDecodeException`, `RateLimitException`, `RetryableApiException`
- `dev.simplified.client.factory` - `TimedPlainConnectionSocketFactory`, `TimedSecureConnectionSocketFactory`
- `dev.simplified.client.interceptor` - `InternalRequestInterceptor`, `InternalResponseInterceptor`
- `dev.simplified.client.ratelimit` - `RateLimitManager`, `RateLimit`, `RateLimitBucket`, `RateLimitConfig`
- `dev.simplified.client.request` - `Endpoint`, `ReactiveEndpoint`, `Request`, `Timings`, `HttpMethod`
- `dev.simplified.client.request.expander` - parameter expanders
- `dev.simplified.client.response` - `Response`, `HttpStatus`, `HttpState`, `CloudflareCacheStatus`, `NetworkDetails`, `RetryAfterParser`
- `dev.simplified.client.route` - `RouteDiscovery`, `DynamicRoute`, `DynamicRouteProvider`, `Route`

## Key Classes

- `Client` - Feign-based HTTP client builder with rate limiting and interceptor support
- `RateLimitManager` - bucket-based rate limit tracking per endpoint
- `Response` - wraps HTTP response with status, headers, Cloudflare cache info
- `Endpoint` / `ReactiveEndpoint` - interfaces for defining API routes

## Dependencies

- Simplified-Dev: `collections`, `utils`, `reflection`
- External: OpenFeign 13.11, Gson 2.11.0, Log4j2 2.25.3, Lombok, JetBrains Annotations

## Build

```bash
./gradlew build
./gradlew test
```

## Config

- Java 21
- Gradle 9.4.1 (wrapper)
- Group: `dev.simplified`, artifact: `client`, version: `1.0.0`
- No tests currently
