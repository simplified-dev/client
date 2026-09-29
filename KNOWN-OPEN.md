# Known open

Open items in `client`. Each stays here until it is closed or accepted.

> #### Configured credentials sit on the `feign.Request`
> `Client` adds its configured static and dynamic headers - an `Authorization` or `API-Key` among
> them - to each request Feign builds, so the cache fingerprints the values the origin receives.
> Those values are now part of the `feign.Request`, and its `toString()`, a Feign `HEADERS` logger
> and `ErrorContext`'s record `toString()` would print them. Nothing in the workspace logs any of
> these today.
>
> - Affected: `src/main/java/dev/simplified/client/Client.java:432` - `ConfiguredHeadersTarget`
> - Type: **RISK**
> - Status: **OPEN**

> #### Internal headers go out on the wire, and transport timings never reach a Feign response
> `ApacheClientFactory`'s request interceptor stamps `X-Internal-Request-Start` and the DNS, TCP and
> TLS markers on the outbound request, and `InternalRequestInterceptor` adds
> `X-Internal-Request-Sequence`, so every origin receives them. On the Feign path the response is
> rebuilt around Feign's own request, which carries none of the transport's markers, so a Feign
> response's `NetworkDetails` reports no DNS, TCP or TLS timing and no TLS protocol or cipher.
>
> - Affected: `src/main/java/dev/simplified/client/factory/ApacheClientFactory.java:142-150` - `configure`;
>   `src/main/java/dev/simplified/client/interceptor/InternalRequestInterceptor.java:91` - `apply`
> - Type: **GAP**
> - Status: **OPEN**

> #### Fetchers sharing one cache with different static queries share entries
> `ApacheClientFactory` appends a client's static query parameters below the cache, after the URL
> key a lookup or store uses has been formed, so the key carries none of them. Two `UrlFetcher`s
> handed one cache through `withSharedCache`, configured with different static queries, answer each
> other's requests from the cache. No workspace consumer configures `withQuery` or
> `withSharedCache`.
>
> - Affected: `src/main/java/dev/simplified/client/factory/ApacheClientFactory.java:152` - `configure`;
>   `src/main/java/dev/simplified/client/fetch/UrlFetcher.java:311` - `fetch`;
>   `src/main/java/dev/simplified/client/fetch/UrlFetcherConfig.java:248` - `withSharedCache`
> - Type: **RISK**
> - Status: **OPEN**
