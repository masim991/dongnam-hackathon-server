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
