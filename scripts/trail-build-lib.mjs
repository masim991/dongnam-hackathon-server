// 산책로 벡터 빌드의 핵심 로직(네트워크 없음). CLI 는 scripts/build-trails.mjs, 배포 전 점검은 scripts/check-trails.mjs.
// 원칙: 새 출력은 public 밖(.wrangler/trail-build/)에서 만들고 검증한 뒤에만 교체한다. 실패하면 기존 출력을 그대로 둔다.
import { createHash } from "node:crypto";
import { mkdir, readdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";

export const GENERATOR = "trail-build/2";
export const BBOX = { s: 34.6, w: 127.6, n: 36.0, e: 129.5 }; // fetch-trails.mjs 와 같은 값이어야 한다
export const STEP = 0.1;
export const TOL_M = 4;      // 선 단순화 허용 오차(m)
export const MIN_LEN_M = 20; // 이보다 짧은 조각은 버림
export const LIMITS = { maxChunkBytes: 3_000_000, maxTotalBytes: 60_000_000, maxFiles: 2000 };
const LAT0 = 35.2, KLA = 111320, KLO = 111320 * Math.cos((LAT0 * Math.PI) / 180);
const REG = { 부산광역시: "부산", 울산광역시: "울산", 경상남도: "경남" };

export class BuildError extends Error {
  constructor(code, message, detail = {}) { super(message); this.code = code; this.detail = detail; }
}

export const sha256 = (data) => createHash("sha256").update(data).digest("hex");
export const cellName = (s, w) => `${s.toFixed(1)}_${w.toFixed(1)}`;

// ---- 경계·기대 격자 ----
export function parseBoundaries(text) {
  const b = JSON.parse(text.replace(/^﻿/, ""));
  if (!b || typeof b.regions !== "object" || !Object.keys(b.regions).length) throw new BuildError("bad_boundaries", "경계 파일에 regions 가 없습니다");
  return b;
}
const inRing = (lon, lat, ring) => {
  let c = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i], [xj, yj] = ring[j];
    if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) c = !c;
  }
  return c;
};
export const regionOf = (boundaries, lon, lat) => {
  for (const [name, rings] of Object.entries(boundaries.regions)) if (rings.some((r) => inRing(lon, lat, r))) return REG[name] ?? name;
  return null;
};

// fetch-trails.mjs 의 neededCells() 와 같은 규칙: 행정경계와 겹치는 0.1° 칸만 필요하다.
export function expectedCells(boundaries) {
  const all = [];
  for (let s = BBOX.s; s < BBOX.n - 1e-9; s += STEP) for (let w = BBOX.w; w < BBOX.e - 1e-9; w += STEP) all.push([+s.toFixed(1), +w.toFixed(1)]);
  const rings = Object.values(boundaries.regions).flat();
  return all.filter(([s, w]) => {
    const n = s + STEP, e = w + STEP;
    if ([[w, s], [e, s], [w, n], [e, n], [(w + e) / 2, (s + n) / 2]].some(([x, y]) => rings.some((r) => inRing(x, y, r)))) return true;
    return rings.some((r) => r.some(([x, y]) => x >= w && x <= e && y >= s && y <= n));
  }).map(([s, w]) => cellName(s, w));
}

// ---- 격자 캐시 검증 ----
export function validateCell(cell, name) {
  const bad = (why) => { throw new BuildError("bad_cell", `${name}: ${why}`); };
  if (!cell || typeof cell !== "object" || !Array.isArray(cell.ways)) bad("ways 배열이 없습니다");
  if (cellName(Number(cell.s), Number(cell.w)) !== name) bad("파일 이름과 s/w 가 다릅니다");
  for (const w of cell.ways) {
    if (!Number.isFinite(w?.id) || !w.tags || typeof w.tags !== "object") bad("way id/tags 가 잘못되었습니다");
    if (!Array.isArray(w.geometry) || w.geometry.length < 2) bad(`way ${w.id} 좌표가 2개 미만입니다`);
    for (const p of w.geometry) if (!Array.isArray(p) || !Number.isFinite(p[0]) || !Number.isFinite(p[1]) || p[0] < 100 || p[0] > 140 || p[1] < 25 || p[1] > 45) bad(`way ${w.id} 좌표가 범위를 벗어났습니다`);
  }
  return cell;
}

// 기대 칸 목록에 대해 캐시를 읽어 present(유효) / missing(파일 없음) / invalid(깨짐)로 나눈다. 빈 칸(ways=[])은 유효한 수집 결과다.
export async function readCells(cellsDir, expected) {
  const present = [], missing = [], invalid = [], inputs = [];
  for (const name of expected) {
    let buf;
    try { buf = await readFile(path.join(cellsDir, name + ".json")); } catch { missing.push(name); continue; }
    try {
      present.push(validateCell(JSON.parse(buf.toString("utf8")), name));
      inputs.push(`${name}:${sha256(buf)}`);
    } catch (e) { invalid.push({ cell: name, reason: String(e.message).slice(0, 200) }); }
  }
  return { present, missing, invalid, inputsSha256: sha256(inputs.join("\n")) };
}

// ---- 기하 처리(선 단순화·길이) ----
const xy = ([lon, lat]) => [(lon - 129) * KLO, (lat - LAT0) * KLA];
const lenM = (g) => g.slice(1).reduce((s, p, i) => { const a = xy(g[i]), b = xy(p); return s + Math.hypot(b[0] - a[0], b[1] - a[1]); }, 0);
export function simplify(g, tol) {
  if (g.length <= 2) return g;
  const P = g.map(xy), keep = new Uint8Array(g.length); keep[0] = keep[g.length - 1] = 1;
  const stack = [[0, g.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop(); let dmax = 0, idx = -1;
    const [ax, ay] = P[a], [bx, by] = P[b], dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
    for (let i = a + 1; i < b; i++) {
      const t = l2 ? Math.max(0, Math.min(1, ((P[i][0] - ax) * dx + (P[i][1] - ay) * dy) / l2)) : 0;
      const d = Math.hypot(P[i][0] - ax - t * dx, P[i][1] - ay - t * dy);
      if (d > dmax) { dmax = d; idx = i; }
    }
    if (dmax > tol) { keep[idx] = 1; stack.push([a, idx], [idx, b]); }
  }
  return g.filter((_, i) => keep[i]);
}
export function kindOf(t) {
  return ({ steps: "steps", track: "track", pedestrian: "pedestrian", cycleway: "cycleway", footway: "footway" })[t.highway] ?? "path";
}

// 칸 목록 → 0.1° 조각. 같은 입력이면 항상 같은 결과(정렬 고정)여야 version 이 안정적이다.
export function processCells(cells, boundaries) {
  const sorted = [...cells].sort((a, b) => (cellName(a.s, a.w) < cellName(b.s, b.w) ? -1 : 1));
  const seen = new Set(), chunks = new Map();
  const stat = { cells: sorted.length, waysRaw: 0, dup: 0, outside: 0, tooShort: 0, kept: 0, points: 0, lengthM: 0, byRegion: {} };
  for (const c of sorted) for (const w of c.ways) {
    stat.waysRaw++;
    if (seen.has(w.id)) { stat.dup++; continue; }
    seen.add(w.id);
    const mid = w.geometry[Math.floor(w.geometry.length / 2)];
    const region = regionOf(boundaries, mid[0], mid[1]) ?? regionOf(boundaries, w.geometry[0][0], w.geometry[0][1]);
    if (!region) { stat.outside++; continue; }
    const g = simplify(w.geometry, TOL_M), len = lenM(g);
    if (len < MIN_LEN_M) { stat.tooShort++; continue; }
    // 조각 기준: 선의 대표점(중간 꼭짓점)이 속한 0.1° 칸
    const key = `${(Math.floor(mid[1] * 10) / 10).toFixed(1)}_${(Math.floor(mid[0] * 10) / 10).toFixed(1)}`;
    const item = { i: w.id, k: kindOf(w.tags), r: region, l: Math.round(len), g: g.map(([lo, la]) => [+lo.toFixed(5), +la.toFixed(5)]) };
    if (w.tags.name) item.n = w.tags.name;
    if (w.tags.surface) item.s = w.tags.surface;
    (chunks.get(key) ?? chunks.set(key, []).get(key)).push(item);
    stat.kept++; stat.points += item.g.length; stat.lengthM += len; stat.byRegion[region] = (stat.byRegion[region] ?? 0) + 1;
  }
  return { chunks, stat };
}

// ---- manifest ----
export function chunkVersion(chunks) {
  return sha256(chunks.map((c) => `${c.cell}:${c.sha256}`).join("\n"));
}
export function buildManifest({ chunkBodies, stat, coverage, inputsSha256, source = "OpenStreetMap contributors (ODbL), Overpass API", now = new Date() }) {
  const chunks = [...chunkBodies.entries()].sort().map(([cell, body]) => ({ cell, trails: JSON.parse(body).trails.length, bytes: Buffer.byteLength(body), sha256: sha256(body) }));
  return {
    generator: GENERATOR, version: chunkVersion(chunks), generatedAt: now.toISOString(), cellSizeDeg: STEP,
    partial: coverage.missing.length + coverage.invalid.length > 0,
    coverage: { expected: coverage.expected, present: coverage.present, missing: coverage.missing, invalid: coverage.invalid, ratio: +(coverage.present / coverage.expected).toFixed(4) },
    inputsSha256,
    source, attribution: "© OpenStreetMap contributors",
    filter: "highway=path|footway|pedestrian|steps|track|bridleway, footway≠sidewalk|crossing|traffic_island, access≠private|no. 부산·울산·경남 행정경계(admin_level=4) 안만 포함",
    processing: { simplifyToleranceM: TOL_M, minLengthM: MIN_LEN_M, chunkRule: "선 중간 꼭짓점이 속한 0.1° 칸" },
    stats: { ...stat, lengthKm: Math.round(stat.lengthM / 1000), cellsFetched: coverage.present },
    limits: ["OSM에 입력된 길만 포함(현실의 모든 산책로를 보증하지 않음)", "이름 없는 길이 대부분이며 종류 분류는 태그 기반의 대략값", "partial=true 이면 coverage.missing 의 칸은 수집되지 않아 빠져 있음"],
    chunks,
  };
}

const CELL_RE = /^\d{2}\.\d_\d{3}\.\d$/;
const HASH_RE = /^[0-9a-f]{64}$/;
const validCoord = (p) => Array.isArray(p) && p.length === 2 && Number.isFinite(p[0]) && Number.isFinite(p[1]) && p[0] >= 100 && p[0] <= 140 && p[1] >= 25 && p[1] <= 45;
const KINDS = new Set(["steps", "track", "pedestrian", "cycleway", "footway", "path"]);
const validTrail = (t) => t && typeof t === "object" && Number.isFinite(t.i) && KINDS.has(t.k) && typeof t.r === "string" && Number.isFinite(t.l) && t.l >= 0
  && Array.isArray(t.g) && t.g.length >= 2 && t.g.every(validCoord);

// ---- 출력 검증(빌드 직후와 배포 전 점검이 같은 함수를 쓴다) ----
// mode: "deploy"(기본, 부분 산출물 거부) | "allow-partial"(시험용 허용)
export async function checkOutput(dir, { mode = "deploy", limits = LIMITS } = {}) {
  const errors = [];
  const fail = (code, message) => errors.push({ code, message });
  let manifest;
  try { manifest = JSON.parse(await readFile(path.join(dir, "index.json"), "utf8")); } catch (e) { return { ok: false, errors: [{ code: "no_manifest", message: "index.json 이 없거나 읽을 수 없습니다" }] }; }
  if (!Array.isArray(manifest.chunks) || !manifest.coverage) return { ok: false, errors: [{ code: "bad_manifest", message: "chunks/coverage 가 없습니다" }] };
  const partial = manifest.partial === true;
  const missing = manifest.coverage.missing ?? [], invalid = manifest.coverage.invalid ?? [];
  if (partial !== (missing.length + invalid.length > 0)) fail("partial_mismatch", "partial 표시와 missing/invalid 목록이 맞지 않습니다");
  if (manifest.coverage.present + missing.length + invalid.length !== manifest.coverage.expected) fail("coverage_mismatch", "coverage 숫자가 맞지 않습니다");
  if (partial && mode !== "allow-partial") fail("partial_rejected", `부분 산출물입니다(누락 ${missing.length}, 깨짐 ${invalid.length}). 배포하려면 전체 수집 후 다시 빌드하세요`);
  if (!manifest.chunks.length) fail("empty", "조각이 없습니다");
  if (manifest.chunks.length > limits.maxFiles) fail("too_many_files", `파일 수 ${manifest.chunks.length} > ${limits.maxFiles}`);
  if (manifest.version !== chunkVersion(manifest.chunks)) fail("version_mismatch", "version 이 조각 해시와 맞지 않습니다");
  // 구조 검증: 경로로 쓰이는 cell 값과 숫자·해시 형식이 틀리면 파일을 열기 전에 멈춘다(경로 이탈 방지).
  const posInt = (v) => Number.isInteger(v) && v > 0, nonNeg = (v) => Number.isInteger(v) && v >= 0;
  const cov = manifest.coverage;
  if (!posInt(cov.expected) || !posInt(cov.present) || !Array.isArray(cov.missing) || !Array.isArray(cov.invalid)
    || !cov.missing.every((m) => typeof m === "string" && CELL_RE.test(m)) || !cov.invalid.every((m) => m && typeof m.cell === "string" && CELL_RE.test(m.cell))) {
    return { ok: false, errors: [{ code: "bad_coverage", message: "coverage 의 expected/present 는 양의 정수, missing/invalid 는 칸 이름 목록이어야 합니다" }] };
  }
  const cellsSeen = new Set();
  for (const c of manifest.chunks) {
    if (!c || typeof c.cell !== "string" || !CELL_RE.test(c.cell)) return { ok: false, errors: [{ code: "bad_chunk_cell", message: "조각 이름 형식이 잘못되었습니다" }] };
    if (cellsSeen.has(c.cell)) return { ok: false, errors: [{ code: "dup_chunk_cell", message: `조각 ${c.cell} 이 중복되었습니다` }] };
    cellsSeen.add(c.cell);
    if (!HASH_RE.test(String(c.sha256)) || !nonNeg(c.bytes) || !nonNeg(c.trails)) return { ok: false, errors: [{ code: "bad_chunk_entry", message: `${c.cell} 의 sha256/bytes/trails 형식이 잘못되었습니다` }] };
  }
  if (!HASH_RE.test(String(manifest.version)) || !HASH_RE.test(String(manifest.inputsSha256))) fail("bad_hash_format", "version/inputsSha256 는 소문자 64자리 hex 여야 합니다");
  let total = 0;
  const listed = new Set(["index.json"]);
  for (const c of manifest.chunks) {
    listed.add(c.cell + ".json");
    let buf;
    try { buf = await readFile(path.join(dir, c.cell + ".json")); } catch { fail("chunk_missing", `${c.cell}.json 이 없습니다`); continue; }
    total += buf.length;
    if (buf.length !== c.bytes) fail("chunk_size", `${c.cell} 크기 불일치`);
    if (buf.length > limits.maxChunkBytes) fail("chunk_too_big", `${c.cell} ${buf.length}B > ${limits.maxChunkBytes}`);
    if (sha256(buf) !== c.sha256) fail("chunk_hash", `${c.cell} 해시 불일치`);
    try {
      const j = JSON.parse(buf.toString("utf8"));
      if (j.cell !== c.cell || !Array.isArray(j.trails) || j.trails.length !== c.trails) fail("chunk_content", `${c.cell} 내용이 manifest 와 다릅니다`);
      else { const bad = j.trails.findIndex((t) => !validTrail(t)); if (bad >= 0) fail("chunk_trail", `${c.cell} 의 ${bad}번째 산책로 형식·좌표가 잘못되었습니다`); }
    } catch { fail("chunk_parse", `${c.cell} JSON 이 깨졌습니다`); }
  }
  if (total > limits.maxTotalBytes) fail("total_too_big", `총 ${total}B > ${limits.maxTotalBytes}`);
  // 배포 에셋에 남으면 안 되는 잔여물(임시·백업 폴더, manifest 에 없는 파일)
  for (const ent of await readdir(dir, { withFileTypes: true })) {
    if (ent.isDirectory()) fail("residue_dir", `하위 폴더 ${ent.name} 가 있습니다`);
    else if (!listed.has(ent.name)) fail("unlisted_file", `manifest 에 없는 파일 ${ent.name}`);
  }
  return { ok: errors.length === 0, errors, version: manifest.version, partial, files: manifest.chunks.length, totalBytes: total, coverage: { expected: manifest.coverage.expected, present: manifest.coverage.present, missing: missing.length, invalid: invalid.length } };
}

// ---- 동시 빌드 락(mkdir 은 원자적) ----
const alive = (pid) => { try { process.kill(pid, 0); return true; } catch (e) { return e.code === "EPERM"; } };
export async function acquireLock(workDir) {
  await mkdir(workDir, { recursive: true });
  const lock = path.join(workDir, "lock");
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      await mkdir(lock);
      await writeFile(path.join(lock, "pid"), String(process.pid));
      return async () => { await rm(lock, { recursive: true, force: true }); };
    } catch (e) {
      if (e.code !== "EEXIST") throw e;
      const pid = Number(await readFile(path.join(lock, "pid"), "utf8").catch(() => ""));
      // pid 파일이 아직 없으면 다른 빌드가 락을 막 만든 중일 수 있으므로 지우지 않고 거부한다. 확인된 죽은 pid 의 락만 교체한다.
      if (!Number.isInteger(pid) || pid <= 0) throw new BuildError("locked", `락(${lock})에 pid 가 없어 다른 빌드가 시작 중일 수 있습니다. 실행 중인 빌드가 없다면 그 폴더를 직접 지우세요`);
      if (alive(pid)) throw new BuildError("locked", `다른 빌드(pid ${pid})가 실행 중입니다`);
      await rm(lock, { recursive: true, force: true }); // 죽은 프로세스의 낡은 락
    }
  }
  throw new BuildError("locked", "락을 얻지 못했습니다");
}

const exists = (p) => stat(p).then(() => true, () => false);

// CLI 옵션 검사: 모르는 옵션·위치 인자는 거부한다(--dryrun 같은 오타로 실제 출력이 교체되는 것을 막는다).
export function parseCliArgs(argv, { flags = [], values = [] }) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (flags.includes(a)) out[a] = true;
    else if (values.includes(a)) {
      if (i + 1 >= argv.length || argv[i + 1].startsWith("--")) throw new BuildError("bad_args", `${a} 에는 값이 필요합니다`);
      out[a] = argv[++i];
    } else throw new BuildError("bad_args", `알 수 없는 옵션 또는 인자: ${a}. 허용: ${[...flags, ...values].join(" ")}`);
  }
  return out;
}

// ---- 빌드 본체 ----
// opts: { cellsDir, outDir, workDir, boundariesPath, allowPartial, dryRun, limits, now }
export async function runBuild(opts) {
  const { cellsDir, outDir, workDir, boundariesPath, allowPartial = false, dryRun = false, limits = LIMITS, now = new Date(), ops = {}, source } = opts;
  const mv = ops.rename ?? rename, check = ops.check ?? checkOutput; // 시험에서 실패를 주입하기 위한 최소 훅
  let keepRunDir = false;
  const rel = path.relative(path.resolve(outDir), path.resolve(workDir));
  if (!rel.startsWith("..") && !path.isAbsolute(rel)) throw new BuildError("bad_workdir", "작업 폴더는 출력 폴더 밖이어야 합니다(에셋으로 올라갈 수 있음)");
  const release = await acquireLock(workDir);
  const runDir = path.join(workDir, `run-${Date.now()}-${process.pid}`);
  try {
    const boundaries = parseBoundaries(await readFile(boundariesPath, "utf8").catch(() => { throw new BuildError("no_boundaries", "경계 파일을 읽을 수 없습니다"); }));
    const expected = expectedCells(boundaries);
    const { present, missing, invalid, inputsSha256 } = await readCells(cellsDir, expected);
    if (!present.length) throw new BuildError("no_cells", "유효한 격자 캐시가 없습니다. 먼저 node scripts/fetch-trails.mjs");
    const coverage = { expected: expected.length, present: present.length, missing, invalid };
    if ((missing.length || invalid.length) && !allowPartial) {
      throw new BuildError("partial", `커버리지 ${present.length}/${expected.length}. 부분 빌드는 --allow-partial 이 필요합니다`, { expected: expected.length, present: present.length, missingCount: missing.length, invalidCount: invalid.length, missing: missing.slice(0, 30), invalid: invalid.slice(0, 10) });
    }
    const { chunks, stat } = processCells(present, boundaries);
    if (!stat.kept) throw new BuildError("empty", "남은 산책로가 0개입니다. 입력이나 경계를 확인하세요");
    const bodies = new Map([...chunks.entries()].map(([k, items]) => [k, JSON.stringify({ cell: k, trails: items })]));
    const manifest = buildManifest({ chunkBodies: bodies, stat, coverage, inputsSha256, source, now });

    const staging = path.join(runDir, "staging");
    await mkdir(staging, { recursive: true });
    for (const [k, body] of bodies) await writeFile(path.join(staging, k + ".json"), body);
    await writeFile(path.join(staging, "index.json"), JSON.stringify(manifest));
    const mode = allowPartial ? "allow-partial" : "deploy";
    const staged = await checkOutput(staging, { mode, limits });
    if (!staged.ok) throw new BuildError("staging_check", "새 출력 검증 실패(기존 출력은 그대로)", { errors: staged.errors.slice(0, 10) });

    const summary = { ok: true, dryRun, partial: manifest.partial, version: manifest.version, coverage: { expected: expected.length, present: present.length, missing: missing.length, invalid: invalid.length }, files: staged.files, totalBytes: staged.totalBytes, kept: stat.kept, lengthKm: manifest.stats.lengthKm };
    if (dryRun) return summary;

    // 같은 디스크 안에서 이름만 바꿔 교체. 실패하면 백업으로 복원한다.
    const backup = path.join(runDir, "backup");
    await mkdir(path.dirname(path.resolve(outDir)), { recursive: true });
    const hadOld = await exists(outDir);
    if (hadOld) await mv(outDir, backup);
    try {
      await mv(staging, outDir);
      const after = await check(outDir, { mode, limits });
      if (!after.ok) throw new BuildError("post_swap_check", "교체 후 검증 실패", { errors: after.errors.slice(0, 10) });
    } catch (e) {
      try {
        await rm(outDir, { recursive: true, force: true });
        if (hadOld) await mv(backup, outDir);
      } catch (restoreError) {
        // 복원까지 실패하면 backup 이 유일한 이전 출력이다. runDir 을 지우지 않고 복구 경로를 알린다.
        keepRunDir = true;
        throw new BuildError("restore_failed", `복원 실패: 이전 출력이 ${backup} 에 남아 있습니다. 직접 ${outDir} 로 옮겨 복구하세요`, { backup, outDir, cause: String(e.message), restoreCause: String(restoreError.message) });
      }
      throw e instanceof BuildError ? e : new BuildError("swap_failed", String(e.message));
    }
    return summary;
  } finally {
    if (!keepRunDir) await rm(runDir, { recursive: true, force: true }).catch(() => {});
    await release();
  }
}
