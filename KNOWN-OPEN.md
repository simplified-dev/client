# Known open

Open items in `client`. Each stays here until it is closed or accepted.

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
