# Known open

Open items in `client`. Each stays here until it is closed or accepted.

> #### `no-cache` and `must-revalidate` are not enforced
> `Response.CachedImpl.isFresh` compares the entry's age with its freshness lifetime and reads no
> other directive, and `mustRevalidate`, which answers for `must-revalidate`, `proxy-revalidate` and
> `no-cache`, has no caller. So a response carrying `no-cache` beside a `max-age` is replayed as fresh
> without the revalidation `no-cache` requires, on the Feign path and through `UrlFetcher` alike. A
> stale entry carrying `must-revalidate` is still served in place of a 5xx within its
> `stale-if-error` window, which `must-revalidate` forbids. No origin a workspace consumer calls is
> known to send either directive beside a freshness lifetime.
>
> - Affected: `src/main/java/dev/simplified/client/response/Response.java:745` - `CachedImpl.isFresh`,
>   `:793` - `CachedImpl.mustRevalidate`; `src/main/java/dev/simplified/client/cache/CachingFeignClient.java:148`,
>   `:168` - `serveFromCache`; `src/main/java/dev/simplified/client/fetch/UrlFetcher.java:248`, `:302` - `fetch`
> - Type: **BUG**
> - Status: **OPEN**

> #### An undecodable 2xx is cached and fails every replay
> `InternalResponseDecoder` offers a buffered response to `ResponseCache.store` before its body is
> decoded, and decoding is deferred to the first `getBody()`. A 2xx whose body does not decode is
> therefore stored, and every replay within its `max-age` decodes the same bytes again and throws
> `ApiDecodeException`, so one malformed answer keeps failing until the entry expires rather than
> once.
>
> - Affected: `src/main/java/dev/simplified/client/decoder/InternalResponseDecoder.java:163` - `decode`
> - Type: **RISK**
> - Status: **OPEN**

> #### A fresh cache hit is counted against the client-side rate limit
> `InternalRequestInterceptor` is a Feign request interceptor, so it runs before
> `CachingFeignClient` decides whether the cache answers. It checks the route's bucket and tracks
> the request whether or not the request then leaves the process, so a replay spends a slot of the
> client's own budget and can be refused with `RateLimitException` although nothing would be sent.
> `UrlFetcher` looks up its cache before it checks and tracks the bucket, so only the Feign path
> does this.
>
> - Affected: `src/main/java/dev/simplified/client/interceptor/InternalRequestInterceptor.java:85-88` - `apply`
> - Type: **GAP**
> - Status: **OPEN**

> #### A stale entry is almost never held, so a 304 is practically unreachable
> `ResponseCacheExpiry` ends a bucket's lifetime at the longest freshness lifetime plus
> `stale-if-error` window among its variants, counted from the write. An entry outlives its
> freshness only by that window, or by the age it arrived with, so a stale entry is almost never
> there to revalidate, and a response carrying a validator but no freshness is expired as it is
> written. GitHub sends `max-age=60` with no `stale-if-error`, so its entries expire at staleness
> and no conditional request, and no 304, is ever made to it.
>
> - Affected: `src/main/java/dev/simplified/client/cache/ResponseCacheExpiry.java:56` - `expireAfterCreate`
> - Type: **GAP**
> - Status: **OPEN**

> #### `invalidate(url)` has no in-flight guard
> `invalidateAll` records when it emptied the cache, and `store` and `updateOn304` refuse an answer
> to a request sent before that. `invalidate(url)`, which runs after a successful unsafe-method
> exchange, records nothing, so a GET to the same URL in flight across the PUT or POST that
> invalidated it can store its answer from before the mutation for its `max-age`.
>
> - Affected: `src/main/java/dev/simplified/client/cache/ResponseCache.java:408` - `invalidate`
> - Type: **RISK**
> - Status: **OPEN**

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

> #### Variants stored under different `Vary` sets can both match one request
> `lookup` returns the first variant whose fingerprint matches, and variants of one URL are held in a
> map with no order. When an origin changes the headers its `Vary` names for a URL, variants stored
> under the old and the new set can both match a request, and which one answers is unspecified
> rather than the most recent by `Date`, as RFC 7234 section 4.1 asks.
>
> - Affected: `src/main/java/dev/simplified/client/cache/ResponseCache.java:283` - `lookup`
> - Type: **GAP**
> - Status: **OPEN**

> #### A 304 carrying a different `Vary` strands its variant
> `updateOn304` refreshes a variant under the fingerprint it was stored with and overlays the 304's
> headers, a new `Vary` included. `lookup` fingerprints a request by the refreshed entry's `Vary`,
> which no longer produces the key the variant is held under, so no request matches it until its
> bucket expires. It only causes misses.
>
> - Affected: `src/main/java/dev/simplified/client/cache/ResponseCache.java:469` - `updateOn304`,
>   `:655` - `mergeHeaders`
> - Type: **GAP**
> - Status: **OPEN**

> #### A 304 without `Date` ages the refreshed entry from the stored `Date`
> `mergeHeaders` keeps the stored `Date` unless the 304 sends one, and `currentAge` takes the
> apparent age as the refreshed round trip's completion minus that `Date`. A 304 with no `Date`
> therefore counts the entry's age from the original response, so an entry revalidated after its
> first `max-age` is stale again as soon as it is refreshed. It needs an origin that breaks RFC 7232
> section 4.1; every origin probed sends `Date`.
>
> - Affected: `src/main/java/dev/simplified/client/cache/ResponseCache.java:655` - `mergeHeaders`;
>   `src/main/java/dev/simplified/client/response/Response.java:726` - `CachedImpl.currentAge`
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
>   `src/main/java/dev/simplified/client/fetch/UrlFetcher.java:246` - `fetch`;
>   `src/main/java/dev/simplified/client/fetch/UrlFetcherConfig.java:245` - `withSharedCache`
> - Type: **RISK**
> - Status: **OPEN**
