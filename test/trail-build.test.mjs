// 산책로 벡터 빌드·점검 테스트. 모두 임시 폴더에서 실행하며 실제 data/·public/ 은 건드리지 않는다.
import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { rename as fsRename } from "node:fs/promises";
import { BuildError, checkOutput, chunkVersion, expectedCells, parseBoundaries, parseCliArgs, runBuild, sha256 } from "../scripts/trail-build-lib.mjs";

const RING = [[129.0, 35.0], [129.2, 35.0], [129.2, 35.2], [129.0, 35.2], [129.0, 35.0]];
const BOUNDARIES = JSON.stringify({ regions: { 부산광역시: [RING] } });
const TRAIL_CELL = "35.1_129.1";
const TRAIL = { id: 1001, tags: { highway: "footway", name: "시험길" }, geometry: [[129.12, 35.12], [129.1225, 35.12], [129.125, 35.12]] };
const OUTSIDE = { id: 1002, tags: { highway: "path" }, geometry: [[130.5, 35.5], [130.502, 35.5], [130.504, 35.5]] };

async function setup(t) {
  const tmp = await mkdtemp(path.join(os.tmpdir(), "trail-build-"));
  t.after(() => rm(tmp, { recursive: true, force: true }));
  const env = { tmp, cellsDir: path.join(tmp, "cells"), outDir: path.join(tmp, "public/data/trails"), workDir: path.join(tmp, ".wrangler/trail-build"), boundariesPath: path.join(tmp, "b.json") };
  await mkdir(env.cellsDir, { recursive: true });
  await writeFile(env.boundariesPath, "﻿" + BOUNDARIES); // BOM 이 있어도 읽어야 한다
  env.expected = expectedCells(parseBoundaries(BOUNDARIES));
  assert.ok(env.expected.includes(TRAIL_CELL));
  return env;
}
async function writeCells(env, { skip = [], corrupt = [] } = {}) {
  for (const name of env.expected) {
    if (skip.includes(name)) continue;
    const [s, w] = name.split("_").map(Number);
    const ways = name === TRAIL_CELL ? [TRAIL, OUTSIDE] : [];
    await writeFile(path.join(env.cellsDir, name + ".json"), corrupt.includes(name) ? "{broken" : JSON.stringify({ s, w, step: 0.1, ways }));
  }
}
const opts = (env, extra = {}) => ({ cellsDir: env.cellsDir, outDir: env.outDir, workDir: env.workDir, boundariesPath: env.boundariesPath, ...extra });
const rejectsWith = async (p, code) => assert.rejects(p, (e) => e instanceof BuildError && e.code === code, "expected " + code);

test("full coverage builds, drops outside ways, writes a deployable manifest, and leaves no work files", async (t) => {
  const env = await setup(t);
  await writeCells(env);
  const s = await runBuild(opts(env));
  assert.equal(s.partial, false);
  assert.equal(s.kept, 1);
  assert.deepEqual(s.coverage, { expected: env.expected.length, present: env.expected.length, missing: 0, invalid: 0 });
  const check = await checkOutput(env.outDir);
  assert.equal(check.ok, true, JSON.stringify(check.errors));
  assert.deepEqual((await readdir(env.outDir)).sort(), [TRAIL_CELL + ".json", "index.json"]);
  assert.deepEqual(await readdir(env.workDir), []); // 락·임시·백업이 모두 정리됨
});

test("partial coverage is refused by default and the existing output is untouched", async (t) => {
  const env = await setup(t);
  await writeCells(env);
  await runBuild(opts(env));
  const before = await readFile(path.join(env.outDir, "index.json"), "utf8");
  await rm(path.join(env.cellsDir, env.expected[0] + ".json"));
  await rejectsWith(runBuild(opts(env)), "partial");
  assert.equal(await readFile(path.join(env.outDir, "index.json"), "utf8"), before);
});

test("--allow-partial writes partial:true with the missing list, and the deploy check rejects it", async (t) => {
  const env = await setup(t);
  const skip = [env.expected[0], env.expected[1]];
  await writeCells(env, { skip });
  const s = await runBuild(opts(env, { allowPartial: true }));
  assert.equal(s.partial, true);
  const manifest = JSON.parse(await readFile(path.join(env.outDir, "index.json"), "utf8"));
  assert.equal(manifest.partial, true);
  assert.deepEqual([...manifest.coverage.missing].sort(), [...skip].sort());
  const deploy = await checkOutput(env.outDir);
  assert.equal(deploy.ok, false);
  assert.ok(deploy.errors.some((e) => e.code === "partial_rejected"));
  assert.equal((await checkOutput(env.outDir, { mode: "allow-partial" })).ok, true);
});

test("a corrupt cache file counts as invalid, not as covered", async (t) => {
  const env = await setup(t);
  await writeCells(env, { corrupt: [env.expected[0]] });
  await rejectsWith(runBuild(opts(env)), "partial");
  const s = await runBuild(opts(env, { allowPartial: true }));
  assert.equal(s.coverage.invalid, 1);
});

test("failures never delete the previous output (no cells, empty result, size gate)", async (t) => {
  const env = await setup(t);
  await writeCells(env);
  await runBuild(opts(env));
  const before = await readFile(path.join(env.outDir, "index.json"), "utf8");
  await rejectsWith(runBuild(opts(env, { limits: { maxChunkBytes: 10, maxTotalBytes: 1e9, maxFiles: 100 } })), "staging_check");
  await rm(env.cellsDir, { recursive: true });
  await mkdir(env.cellsDir);
  await rejectsWith(runBuild(opts(env)), "no_cells");
  await writeCells(env);
  for (const name of env.expected) { // 모든 칸이 비어 있으면 결과 0개 → 거부
    const [s, w] = name.split("_").map(Number);
    await writeFile(path.join(env.cellsDir, name + ".json"), JSON.stringify({ s, w, step: 0.1, ways: [] }));
  }
  await rejectsWith(runBuild(opts(env)), "empty");
  assert.equal(await readFile(path.join(env.outDir, "index.json"), "utf8"), before);
  assert.deepEqual(await readdir(env.workDir), []);
});

test("same input gives the same version; changed input changes it", async (t) => {
  const env = await setup(t);
  await writeCells(env);
  const a = await runBuild(opts(env));
  const b = await runBuild(opts(env, { now: new Date("2030-01-01") }));
  assert.equal(a.version, b.version);
  const [s, w] = TRAIL_CELL.split("_").map(Number);
  await writeFile(path.join(env.cellsDir, TRAIL_CELL + ".json"), JSON.stringify({ s, w, step: 0.1, ways: [{ ...TRAIL, geometry: [[129.12, 35.12], [129.125, 35.121], [129.13, 35.12]] }] }));
  assert.notEqual((await runBuild(opts(env))).version, a.version);
});

test("dry-run validates without creating the public output", async (t) => {
  const env = await setup(t);
  await writeCells(env);
  const s = await runBuild(opts(env, { dryRun: true }));
  assert.equal(s.dryRun, true);
  await assert.rejects(readdir(env.outDir));
  assert.deepEqual(await readdir(env.workDir), []);
});

test("check detects tampering, unlisted files, residue folders and a missing manifest", async (t) => {
  const env = await setup(t);
  await writeCells(env);
  await runBuild(opts(env));
  const chunk = path.join(env.outDir, TRAIL_CELL + ".json");
  const orig = await readFile(chunk, "utf8");
  await writeFile(chunk, orig.replace("시험길", "바뀐길"));
  assert.ok((await checkOutput(env.outDir)).errors.some((e) => e.code === "chunk_hash" || e.code === "chunk_size"));
  await writeFile(chunk, orig);
  assert.equal((await checkOutput(env.outDir)).ok, true);
  await writeFile(path.join(env.outDir, "stray.json"), "{}");
  assert.ok((await checkOutput(env.outDir)).errors.some((e) => e.code === "unlisted_file"));
  await rm(path.join(env.outDir, "stray.json"));
  await mkdir(path.join(env.outDir, ".staging"));
  assert.ok((await checkOutput(env.outDir)).errors.some((e) => e.code === "residue_dir"));
  await rm(path.join(env.outDir, ".staging"), { recursive: true });
  await rm(path.join(env.outDir, "index.json"));
  assert.equal((await checkOutput(env.outDir)).errors[0].code, "no_manifest");
});

test("lock: a live holder blocks a second build, a dead holder's lock is replaced", async (t) => {
  const env = await setup(t);
  await writeCells(env);
  await mkdir(path.join(env.workDir, "lock"), { recursive: true });
  await writeFile(path.join(env.workDir, "lock", "pid"), String(process.pid));
  await rejectsWith(runBuild(opts(env)), "locked");
  await writeFile(path.join(env.workDir, "lock", "pid"), "2147483646");
  assert.equal((await runBuild(opts(env))).partial, false);
});

test("work folder must be outside the output folder (it would be uploaded as an asset)", async (t) => {
  const env = await setup(t);
  await writeCells(env);
  await rejectsWith(runBuild(opts(env, { workDir: path.join(env.outDir, ".work") })), "bad_workdir");
});

// ---- 교차검토 후 추가: 복원 실패·락 경쟁·CLI 오타·manifest/조각 심층 검증 ----
async function builtEnv(t) {
  const env = await setup(t);
  await writeCells(env);
  await runBuild(opts(env));
  return env;
}
const OLD_MARK = (env) => readFile(path.join(env.outDir, "index.json"), "utf8");
// 교체 단계의 rename 만 실패시키는 주입기: failOn 에 든 목적지 이름("trails"=출력, "backup"=복원)에서 던진다.
const flakyRename = (env, failOn) => async (from, to) => {
  if (to === env.outDir && from.endsWith("staging") && failOn.includes("swap")) throw new Error("injected swap failure");
  if (to === env.outDir && from.endsWith("backup") && failOn.includes("restore")) throw new Error("injected restore failure");
  return fsRename(from, to);
};

test("swap failure restores the previous output and cleans the work folder", async (t) => {
  const env = await builtEnv(t);
  const before = await OLD_MARK(env);
  await rejectsWith(runBuild(opts(env, { ops: { rename: flakyRename(env, ["swap"]) } })), "swap_failed");
  assert.equal(await OLD_MARK(env), before);
  assert.deepEqual(await readdir(env.workDir), []);
});

test("post-swap check failure restores the previous output", async (t) => {
  const env = await builtEnv(t);
  const before = await OLD_MARK(env);
  const check = async (dir, o) => (dir === env.outDir ? { ok: false, errors: [{ code: "injected", message: "x" }] } : checkOutput(dir, o));
  await rejectsWith(runBuild(opts(env, { ops: { check } })), "post_swap_check");
  assert.equal(await OLD_MARK(env), before);
  assert.deepEqual(await readdir(env.workDir), []);
});

test("when restore also fails, the backup and run folder are kept and the recovery path is reported", async (t) => {
  const env = await builtEnv(t);
  const before = await OLD_MARK(env);
  let error;
  await runBuild(opts(env, { ops: { rename: flakyRename(env, ["swap", "restore"]) } })).catch((e) => { error = e; });
  assert.ok(error instanceof BuildError && error.code === "restore_failed");
  assert.ok(error.detail.backup && error.detail.outDir === env.outDir);
  assert.equal(await readFile(path.join(error.detail.backup, "index.json"), "utf8"), before); // 유일한 이전 출력이 보존됨
  assert.ok(error.message.includes(error.detail.backup));
  assert.ok(!(await readdir(env.workDir)).includes("lock")); // 락은 풀린다
});

test("lock: a lock folder without a pid is refused, not treated as stale", async (t) => {
  const env = await setup(t);
  await writeCells(env);
  await mkdir(path.join(env.workDir, "lock"), { recursive: true }); // 다른 빌드가 mkdir 직후 pid 쓰기 전인 상태
  await rejectsWith(runBuild(opts(env)), "locked");
  await writeFile(path.join(env.workDir, "lock", "pid"), "not-a-number");
  await rejectsWith(runBuild(opts(env)), "locked");
  await assert.rejects(readdir(env.outDir)); // 아무것도 만들지 않음
});

test("CLI options: unknown flags, typos, stray arguments and missing values are rejected", () => {
  const spec = { flags: ["--allow-partial", "--dry-run"], values: ["--dir"] };
  assert.deepEqual(parseCliArgs(["--dry-run", "--dir", "x"], spec), { "--dry-run": true, "--dir": "x" });
  for (const bad of [["--dryrun"], ["dry-run"], ["--dry-run", "extra"], ["--dir"], ["--dir", "--dry-run"], ["--allow-partials"]]) {
    assert.throws(() => parseCliArgs(bad, spec), (e) => e instanceof BuildError && e.code === "bad_args", bad.join(" "));
  }
});

// 조각을 바꾸고 manifest(해시·크기·version)까지 맞춰 다시 써서, 해시만 맞으면 통과하던 옛 검증의 구멍을 시험한다.
async function rewriteChunk(env, mutate) {
  const mp = path.join(env.outDir, "index.json"), cp = path.join(env.outDir, TRAIL_CELL + ".json");
  const manifest = JSON.parse(await readFile(mp, "utf8")), chunk = JSON.parse(await readFile(cp, "utf8"));
  mutate(chunk);
  const body = JSON.stringify(chunk);
  await writeFile(cp, body);
  const entry = manifest.chunks.find((c) => c.cell === TRAIL_CELL);
  Object.assign(entry, { bytes: Buffer.byteLength(body), sha256: sha256(body), trails: chunk.trails.length });
  manifest.version = chunkVersion(manifest.chunks);
  await writeFile(mp, JSON.stringify(manifest));
}
async function rewriteManifest(env, mutate) {
  const mp = path.join(env.outDir, "index.json"), manifest = JSON.parse(await readFile(mp, "utf8"));
  mutate(manifest);
  await writeFile(mp, JSON.stringify(manifest));
}
const codes = async (env) => (await checkOutput(env.outDir)).errors.map((e) => e.code);

test("check rejects chunks whose coordinates or fields are broken even when hashes were recomputed", async (t) => {
  for (const [name, mutate] of [
    ["NaN-like coordinate", (c) => { c.trails[0].g[1] = [null, 35.1]; }],
    ["out of range", (c) => { c.trails[0].g[0] = [0, 0]; }],
    ["single point", (c) => { c.trails[0].g = [[129.1, 35.1]]; }],
    ["unknown kind", (c) => { c.trails[0].k = "highway"; }],
    ["missing id", (c) => { delete c.trails[0].i; }],
  ]) {
    const env = await builtEnv(t);
    assert.equal((await checkOutput(env.outDir)).ok, true);
    await rewriteChunk(env, mutate);
    assert.ok((await codes(env)).includes("chunk_trail"), name);
  }
});

test("check validates manifest structure: cell names, duplicates, coverage integers and hash formats", async (t) => {
  const cases = [
    ["path traversal cell", (m) => { m.chunks[0].cell = "../../x"; }, ["bad_chunk_cell"]],
    ["duplicate cell", (m) => { m.chunks.push({ ...m.chunks[0] }); }, ["dup_chunk_cell"]],
    ["non-integer expected", (m) => { m.coverage.expected = 1.5; }, ["bad_coverage"]],
    ["zero present", (m) => { m.coverage.present = 0; }, ["bad_coverage"]],
    ["bad missing entry", (m) => { m.coverage.missing = ["../x"]; m.partial = true; }, ["bad_coverage"]],
    ["uppercase sha", (m) => { m.chunks[0].sha256 = m.chunks[0].sha256.toUpperCase(); }, ["bad_chunk_entry"]],
    ["short version", (m) => { m.version = "abc"; }, ["bad_hash_format"]],
    ["negative bytes", (m) => { m.chunks[0].bytes = -1; }, ["bad_chunk_entry"]],
  ];
  for (const [name, mutate, want] of cases) {
    const env = await builtEnv(t);
    await rewriteManifest(env, mutate);
    const got = await codes(env);
    assert.ok(want.every((c) => got.includes(c)), `${name}: ${got.join(",")}`);
  }
});