# Spring throughput bench

End-to-end measurement of how many concurrent Spring requests the client library
can dispatch when wired into a Spring Boot 4.0.5 application running on Java 21
virtual threads. Designed to answer three questions:

1. **What is the realistic concurrency ceiling** of `Client` + Spring MVC + HC5
   under cache-miss and cache-hit traffic?
2. **Where is the binding constraint** - Tomcat workers, HC5 pool size, decoder
   throughput, GC, or upstream?
3. **Are the `Timings.maxConnections` / `maxConnectionsPerRoute` defaults sensible**,
   or should they be raised / removed entirely?

## Topology

```
+---------------+        +---------------------+        +---------------------+
|  k6 (load     | -----> |  Spring Boot app    | -----> |  MockMojangServer   |
|  generator)   |  HTTP  |  :8080              |  HTTPS |  :47652             |
+---------------+        |  - MojangController |        |  /minecraft/profile |
                         |  - BenchClient (HC5)|        |  /lookup/name/...   |
                         +---------------------+        +---------------------+
```

Three processes, three terminals. The mock starts first so the Spring app's
DNS-prewarm and TLS-handshake-prewarm can succeed at construction; k6 starts
last and ramps virtual users until the latency knee.

## Prerequisites

- **Java 21** (already pinned in `client/build.gradle.kts`)
- **k6** - single binary on Windows, install via `winget install k6 --source winget`
  (or download from <https://k6.io/docs/get-started/installation/>)

## Running

```bash
# Terminal 1 - mock upstream
./gradlew :client:runMockMojang

# Terminal 2 - Spring app under test
./gradlew :client:runSpringBench

# Terminal 3 - load generator
cd client/bench/k6
k6 run load.js
```

The k6 run takes ~3 minutes per scenario at default settings (`miss` then `hit`).
Latency percentiles per scenario print in the summary.

## Sweeping pool size

The whole point of the bench is to find where the binding constraint lives.
Re-run the Spring app with different `-P` overrides between k6 runs:

```bash
# Current Timings.createDefault() - the conservative baseline
./gradlew :client:runSpringBench -Pclient.maxConnections=200 -Pclient.maxConnectionsPerRoute=50

# Mid-tier - what a reasonable production default might look like
./gradlew :client:runSpringBench -Pclient.maxConnections=500 -Pclient.maxConnectionsPerRoute=200

# Aggressive - test whether the lib has headroom beyond the current cap
./gradlew :client:runSpringBench -Pclient.maxConnections=2000 -Pclient.maxConnectionsPerRoute=1000

# Spring thread-pool size (Tomcat worker concurrency, separate from HC5 pool)
./gradlew :client:runSpringBench -Pserver.tomcat.threads.max=2000

# Toggle Loom off to measure the carrier-pinning penalty
./gradlew :client:runSpringBench -Pspring.threads.virtual.enabled=false
```

If throughput plateaus well below the configured pool cap, the bottleneck is
elsewhere (CPU, GC, Feign reflection). If it scales linearly with the cap,
the pool is the binding constraint.

## k6 knobs

Set via `-e` on the `k6 run` command:

| Env var | Default | Purpose |
|---|---|---|
| `BASE_URL` | `http://127.0.0.1:8080` | Spring app URL |
| `SCENARIOS` | `miss,hit` | Comma-separated subset of `miss` / `hit` |
| `MAX_VUS` | `2000` | Peak virtual users at plateau |
| `RAMP_DURATION` | `30s` | Per-ramp-stage duration |
| `PLATEAU_DURATION` | `60s` | Time held at peak VUs |

```bash
# Quick smoke - cache-hit only, low VU count, 30s total
k6 run load.js -e SCENARIOS=hit -e MAX_VUS=200 -e RAMP_DURATION=5s -e PLATEAU_DURATION=15s

# High-fan-out cache-miss test
k6 run load.js -e SCENARIOS=miss -e MAX_VUS=5000 -e PLATEAU_DURATION=120s
```

## Reading the results

Headline metrics after each run:

- `iteration_duration` - end-to-end including k6 overhead. Subtract ~0.5ms.
- `http_req_duration` - HTTP request latency. The number you actually want.
- `latency_miss_ms{scenario:miss}` / `latency_hit_ms{scenario:hit}` - custom
  trends, easier to filter when running both scenarios in one go.
- `http_reqs` - total request count; divide by run duration for throughput.
- `errors_miss` / `errors_hit` - failed status or schema-violating responses.

The knee in latency-vs-VUs is the practical concurrency ceiling. Below the
knee, more VUs = more throughput; above the knee, queueing dominates.

## What the bench is and isn't

**It measures:** the library's behaviour on the loopback path. Spring controller
overhead, HC5 pool dynamics, TLS, JSON decode, response cache short-circuit.

**It does not measure:** real-world WAN latency, Mojang's rate limit, geographic
distribution, DNS resolution variance. Those belong in a staging-environment
load test against the real upstream, not a microbenchmark.
