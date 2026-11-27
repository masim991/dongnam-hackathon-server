import assert from "node:assert/strict";
import { test } from "node:test";
import worker from "../src/index.ts";

test("health endpoint returns a non-cached success response", async () => {
  const response = await worker.fetch(new Request("https://example.test/api/health"));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.deepEqual(await response.json(), { ok: true, service: "dongnam-hackathon" });
});

test("unknown routes return 404", async () => {
  const response = await worker.fetch(new Request("https://example.test/missing"));
  assert.equal(response.status, 404);
});

test("health endpoint rejects writes and handles HEAD without a body", async () => {
  const write = await worker.fetch(new Request("https://example.test/api/health", { method: "POST" }));
  assert.equal(write.status, 405);
  assert.equal(write.headers.get("Allow"), "GET, HEAD");
  const head = await worker.fetch(new Request("https://example.test/api/health", { method: "HEAD" }));
  assert.equal(head.status, 200);
  assert.equal(await head.text(), "");
});

// --- 코스·횡단보도 읽기 API (D1은 가짜 객체로 대체) ---
function fakeDb(rowsBySql: (sql: string) => Record<string, unknown>[]) {
  return {
    prepare(sql: string) {
      const all = async () => ({ results: rowsBySql(sql) });
      return { all, bind: () => ({ all }) };
    },
  };
}

test("course API returns 503 without a database and rejects writes", async () => {
  const noDb = await worker.fetch(new Request("https://example.test/api/courses"));
  assert.equal(noDb.status, 503);
  const write = await worker.fetch(new Request("https://example.test/api/courses", { method: "POST" }));
  assert.equal(write.status, 405);
});

test("course list joins crossing summaries", async () => {
  const DB = fakeDb((sql) => sql.includes("FROM course_crossing_summary")
    ? [{ course_code: "HD-X", source: "osm", kind: "uncontrolled", count: 2 }]
    : [{ code: "HD-X", name: "시험 코스" }]);
  const response = await worker.fetch(new Request("https://example.test/api/courses"), { DB });
  assert.equal(response.status, 200);
  const body = await response.json() as { courses: { code: string; crossings: unknown[] }[] };
  assert.equal(body.courses[0].code, "HD-X");
  assert.deepEqual(body.courses[0].crossings, [{ source: "osm", kind: "uncontrolled", count: 2 }]);
});

test("course geojson validates the code and returns 404 for an unknown course", async () => {
  const DB = fakeDb(() => []);
  const bad = await worker.fetch(new Request("https://example.test/api/courses/bad code/geojson"), { DB });
  assert.equal(bad.status, 400);
  const missing = await worker.fetch(new Request("https://example.test/api/courses/HD-NONE/geojson"), { DB });
  assert.equal(missing.status, 404);
});

test("crosswalk bbox is validated before querying", async () => {
  const DB = fakeDb(() => []);
  const tooWide = await worker.fetch(new Request("https://example.test/api/crosswalks?bbox=129,35,130,36"), { DB });
  assert.equal(tooWide.status, 400);
  const ok = await worker.fetch(new Request("https://example.test/api/crosswalks?bbox=129.15,35.15,129.18,35.17"), { DB });
  assert.equal(ok.status, 200);
  assert.deepEqual(await ok.json(), { type: "FeatureCollection", features: [] });
});
test("signals API validates bbox, is read-only, and returns points", async () => {
  const DB = fakeDb((sql) => sql.includes("FROM busan_signal") ? [{ id: 1, mgmt_no: "9077", sigungu: "해운대구", place: "벡스코역앞", road_type: "교차로", lon: 129.1353, lat: 35.1714 }] : []);
  const noBox = await worker.fetch(new Request("https://example.test/api/signals"), { DB });
  assert.equal(noBox.status, 400);
  const write = await worker.fetch(new Request("https://example.test/api/signals?bbox=129.13,35.17,129.14,35.18", { method: "POST" }), { DB });
  assert.equal(write.status, 405);
  const ok = await worker.fetch(new Request("https://example.test/api/signals?bbox=129.13,35.17,129.14,35.18"), { DB });
  assert.equal(ok.status, 200);
  const body = await ok.json() as { features: { properties: { sigungu: string } }[] };
  assert.equal(body.features[0].properties.sigungu, "해운대구");
});

// --- 러닝 날씨 판정(네트워크 없이 규칙만 시험) ---
import { rate, windDirLabel, summarize } from "../src/weather.ts";

const calm = { temp: 12, feelsLike: 12, humidity: 50, windSpeed: 2, windGust: 4, windDir: 270, precip: 0, pm25: 8, pm10: 20 };

test("calm cool dry clean-air day is rated good", () => {
  const r = rate(calm);
  assert.equal(r.verdict, "good");
  assert.ok(r.score >= 90);
  assert.deepEqual(r.reasons, []);
});

test("strong wind, rain, heat, and dust push the rating down with reasons", () => {
  assert.equal(rate({ ...calm, windSpeed: 12 }).verdict, "bad");
  assert.equal(rate({ ...calm, precip: 3 }).verdict, "bad");
  assert.equal(rate({ ...calm, feelsLike: 34, temp: 30 }).verdict, "bad");
  assert.equal(rate({ ...calm, pm25: 80 }).verdict, "bad");
  const mid = rate({ ...calm, windSpeed: 8, humidity: 85 });
  assert.equal(mid.verdict, "fair");
  assert.ok(mid.reasons.some((x) => x.includes("바람")));
});

test("missing values are ignored instead of failing, and wind direction is labelled", () => {
  // 필수(기온·풍속)만 있고 나머지가 없으면 그 항목만 건너뛰고 판정한다. 필수가 없으면 판정 불가(좋음이 아님).
  const r = rate({ temp: 12, feelsLike: null, humidity: null, windSpeed: 2, windGust: null, windDir: null, precip: null, pm25: null, pm10: null });
  assert.equal(r.score, 100);
  assert.equal(rate({ temp: null, feelsLike: null, humidity: null, windSpeed: null, windGust: null, windDir: null, precip: null, pm25: null, pm10: null }).verdict, "unknown");
  assert.equal(windDirLabel(270), "서풍");
  assert.equal(windDirLabel(null), null);
  const s = summarize([rate(calm), rate({ ...calm, windSpeed: 12 })]);
  assert.equal(s.counts.bad, 1);
});

test("weather API validates scope and does not leak upstream failures", async () => {
  const bad = await worker.fetch(new Request("https://example.test/api/weather?scope=../../x"));
  assert.equal(bad.status, 400);
  const write = await worker.fetch(new Request("https://example.test/api/weather", { method: "POST" }));
  assert.equal(write.status, 405);
});
// --- 공급자 응답이 비거나 일부만 올 때: 좋음으로 오인하지 않는다 ---
import { observationAt } from "../src/weather.ts";

test("missing observations are unknown, never good", () => {
  const r = rate(observationAt(undefined, undefined));
  assert.equal(r.verdict, "unknown");
  assert.equal(r.score, null);
  assert.equal(rate(observationAt({ current: { temperature_2m: 999, wind_speed_10m: 2 } }, undefined)).verdict, "unknown"); // 범위 밖 값은 없는 값으로 취급
  const s = summarize([rate(observationAt(undefined, undefined)), rate(calm), rate({ ...calm, windSpeed: 12 })]);
  assert.equal(s.coverage, "2/3");
  assert.equal(s.counts.unknown, 1);
  assert.equal(summarize([rate(observationAt(undefined, undefined))]).verdict, "unknown");
});

function fakeWeatherFetch(mutate: (wx: Record<string, unknown>[], aq: Record<string, unknown>[]) => void) {
  const now = new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 16); // Asia/Seoul 현지시각 형식
  const hour = now.slice(0, 13) + ":00";
  const times = Array.from({ length: 48 }, (_, i) => new Date(Date.parse(hour + ":00Z") + i * 3600_000).toISOString().slice(0, 16));
  const flat = (v: number) => Array.from({ length: 48 }, () => v);
  const wx = () => ({ current: { time: now, temperature_2m: 12, apparent_temperature: 12, relative_humidity_2m: 50, precipitation: 0, wind_speed_10m: 2, wind_direction_10m: 270, wind_gusts_10m: 4 },
    hourly: { time: times, temperature_2m: flat(12), apparent_temperature: flat(12), relative_humidity_2m: flat(50), precipitation: flat(0), wind_speed_10m: flat(2), wind_direction_10m: flat(270), wind_gusts_10m: flat(4) } });
  const aq = () => ({ current: { time: now, pm10: 20, pm2_5: 8 }, hourly: { time: times, pm10: flat(20), pm2_5: flat(8) } });
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    const count = url.match(/latitude=([^&]+)/)![1].split(",").length;
    const w = Array.from({ length: count }, wx), a = Array.from({ length: count }, aq);
    mutate(w, a);
    return Response.json(url.includes("air-quality") ? a : w);
  };
}

test("weather API: too few provider results fail with 502 instead of misaligning areas", async () => {
  const orig = globalThis.fetch;
  globalThis.fetch = fakeWeatherFetch((w) => { w.pop(); }) as typeof fetch;
  try {
    const res = await worker.fetch(new Request("https://example.test/api/weather?scope=haeundae"));
    assert.equal(res.status, 502);
  } finally { globalThis.fetch = orig; }
});

test("weather API: an empty or stale area is unknown and excluded from the summary and recommendations", async () => {
  const orig = globalThis.fetch;
  globalThis.fetch = fakeWeatherFetch((w) => {
    w[1] = {};                                                              // 빈 응답
    (w[2].current as Record<string, unknown>).time = "2020-01-01T00:00";    // 오래된 관측
  }) as typeof fetch;
  try {
    const res = await worker.fetch(new Request("https://example.test/api/weather?scope=haeundae"));
    assert.equal(res.status, 200);
    const body = await res.json() as { summary: Record<string, { coverage: string }>; areas: { rating: { verdict: string; score: number | null }; bestHours?: unknown[] }[] };
    assert.equal(body.areas[1].rating.verdict, "unknown");
    assert.equal(body.areas[1].rating.score, null);
    assert.equal(body.areas[1].bestHours, undefined);
    assert.equal(body.areas[2].rating.verdict, "unknown");
    assert.equal(body.areas[0].rating.verdict, "good");
    assert.equal(body.summary["해운대"].coverage, "4/6");
  } finally { globalThis.fetch = orig; }
});

test("weather API: all areas unknown is an error, not a good day", async () => {
  const orig = globalThis.fetch;
  globalThis.fetch = fakeWeatherFetch((w) => { for (let i = 0; i < w.length; i++) w[i] = {}; }) as typeof fetch;
  try {
    const res = await worker.fetch(new Request("https://example.test/api/weather?scope=haeundae"));
    assert.equal(res.status, 502);
  } finally { globalThis.fetch = orig; }
});