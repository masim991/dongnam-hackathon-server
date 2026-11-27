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