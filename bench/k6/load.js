// k6 load scenario for the Spring bench.
//
// Two scenarios run in series (default), each ramping virtual users:
//   * miss  - GET /mojang/user/{rand-username} ........... cache-miss path
//   * hit   - GET /mojang/user/CraftedFury/cached ........ cache-hit path
//
// Tweak knobs from the CLI without editing this file:
//
//   k6 run load.js \
//     -e BASE_URL=http://127.0.0.1:8080 \
//     -e SCENARIOS=miss,hit \
//     -e MAX_VUS=2000 \
//     -e RAMP_DURATION=30s \
//     -e PLATEAU_DURATION=60s
//
// Thresholds intentionally generous so the run completes even when the
// library is saturated. The interesting signal is the latency histogram and
// the iteration rate at each VU level, not pass/fail.

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000', 10);
const RAMP_DURATION = __ENV.RAMP_DURATION || '30s';
const PLATEAU_DURATION = __ENV.PLATEAU_DURATION || '60s';
const SCENARIOS = (__ENV.SCENARIOS || 'miss,hit').split(',').map(s => s.trim());

const stages = [
    { duration: RAMP_DURATION, target: Math.round(MAX_VUS * 0.05) }, // warm-up
    { duration: RAMP_DURATION, target: Math.round(MAX_VUS * 0.25) },
    { duration: RAMP_DURATION, target: Math.round(MAX_VUS * 0.50) },
    { duration: PLATEAU_DURATION, target: MAX_VUS },
    { duration: '5s', target: 0 }
];

const scenarios = {};
if (SCENARIOS.includes('miss')) {
    scenarios.miss = {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: stages,
        gracefulRampDown: '5s',
        exec: 'missScenario',
        tags: { scenario: 'miss' }
    };
}
if (SCENARIOS.includes('hit')) {
    scenarios.hit = {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: stages,
        gracefulRampDown: '5s',
        exec: 'hitScenario',
        tags: { scenario: 'hit' },
        startTime: SCENARIOS.includes('miss') ? sumStageDurations(stages) : '0s'
    };
}

export const options = {
    scenarios: scenarios,
    discardResponseBodies: false,
    thresholds: {
        'http_req_failed{scenario:miss}': ['rate<0.05'],
        'http_req_failed{scenario:hit}': ['rate<0.01'],
        'http_req_duration{scenario:miss}': ['p(95)<2000', 'p(99)<5000'],
        'http_req_duration{scenario:hit}': ['p(95)<200', 'p(99)<1000']
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)']
};

const errorsByScenario = {
    miss: new Counter('errors_miss'),
    hit: new Counter('errors_hit')
};
const latencyByScenario = {
    miss: new Trend('latency_miss_ms'),
    hit: new Trend('latency_hit_ms')
};

export function missScenario() {
    // Random username per iteration so the response cache never hits.
    const username = 'bench_' + Math.random().toString(36).slice(2, 14);
    const res = http.get(`${BASE_URL}/mojang/user/${username}`, {
        tags: { scenario: 'miss' }
    });
    record('miss', res);
}

export function hitScenario() {
    // Fixed username so CachingFeignClient short-circuits after the first call.
    const res = http.get(`${BASE_URL}/mojang/user/CraftedFury/cached`, {
        tags: { scenario: 'hit' }
    });
    record('hit', res);
}

function record(name, res) {
    latencyByScenario[name].add(res.timings.duration);
    const ok = check(res, {
        [`${name} status 200`]: r => r.status === 200,
        [`${name} body parses`]: r => {
            try {
                const body = r.json();
                return body && typeof body.id === 'string' && typeof body.name === 'string';
            } catch (e) {
                return false;
            }
        }
    });
    if (!ok) errorsByScenario[name].add(1);
}

function sumStageDurations(stages) {
    // Convert each stage duration string (e.g. "30s", "1m") into a number of
    // seconds, then sum, then re-emit as a string k6 understands.
    let total = 0;
    for (const stage of stages) total += parseDuration(stage.duration);
    return total + 's';
}

function parseDuration(text) {
    const match = /^(\d+)(ms|s|m|h)$/.exec(text);
    if (!match) throw new Error(`Bad duration: ${text}`);
    const value = parseInt(match[1], 10);
    switch (match[2]) {
        case 'ms': return Math.ceil(value / 1000);
        case 's':  return value;
        case 'm':  return value * 60;
        case 'h':  return value * 3600;
    }
    throw new Error(`Bad unit: ${match[2]}`);
}
