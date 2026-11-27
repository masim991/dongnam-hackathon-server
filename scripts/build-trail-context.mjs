// 해운대 산책로 주변(선분 기준 500m 이내)의 신호제어기·횡단보도·경사 구간만 추려 저장 대상으로 만든다.
// 규칙(문서 docs/trail-context.md 와 동일):
//  - 산책로 = 큐레이션 코스(D1 course_way 원본: scripts/extra-courses.json·data/curated) + OSM 해운대구의 '이름 있는 길' + 이름 없는 path/track/bridleway(산길). 이름 없는 footway·pedestrian·steps(단지 내 보행로 등)는 제외.
//  - 거리 = 점(신호제어기·횡단보도) 또는 경사 구간의 양 끝점·중점 3점 각각에서 산책로 선분까지의 최소 거리. 500m 이내면 구간 전체를 보존한다(절단하지 않음).
//  - 경사 원본은 다른 세션이 적재한 외부 표(읽기만 함).
// 실행: node scripts/build-trail-context.mjs
// 출력: public/data/haeundae/trail-context.json, db/pending/20261128_trail_context.sql(쿼터 초기화 후 db:apply --pending)
import { readFile, writeFile, mkdir, access } from "node:fs/promises";
import path from "node:path";
import { root, query } from "./db-lib.mjs";
import { readHaeundaeSignals, clusterCrosswalks } from "./hd-data.mjs";
import { toXY } from "./lib-geo.mjs";

const RADIUS_M = 500;
const OSM_TRAILS = path.join(root, "data/raw/osm_haeundae_trails.json");
const exists = (p) => access(p).then(() => true, () => false);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function overpass(q) {
  for (let a = 0; a < 6; a++) {
    try {
      const r = await fetch(["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"][a % 2], { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded", "user-agent": "dongnam-running-trails/1.0" }, body: "data=" + encodeURIComponent(q) });
      if (r.ok) return await r.json();
    } catch { /* 재시도 */ }
    await sleep(8000 * (a + 1));
  }
  throw new Error("overpass failed");
}

// 1) 해운대구 OSM 길
if (!(await exists(OSM_TRAILS))) {
  const j = await overpass(`[out:json][timeout:180];area["boundary"="administrative"]["name"="해운대구"]->.a;way(area.a)["highway"~"^(path|footway|pedestrian|steps|track|bridleway)$"]["footway"!~"^(sidewalk|crossing|traffic_island)$"]["access"!~"^(private|no)$"];out tags geom;`);
  await mkdir(path.dirname(OSM_TRAILS), { recursive: true });
  await writeFile(OSM_TRAILS, JSON.stringify({ fetchedAt: new Date().toISOString(), ways: j.elements.filter((e) => e.type === "way").map((e) => ({ id: e.id, tags: e.tags ?? {}, geometry: e.geometry.map((g) => [+g.lon.toFixed(6), +g.lat.toFixed(6)]) })) }));
}
const osm = JSON.parse(await readFile(OSM_TRAILS, "utf8"));
const isMountain = (t) => ["path", "track", "bridleway"].includes(t.highway);
const trails = [];
for (const w of osm.ways) {
  if (w.geometry.length < 2) continue;
  if (w.tags.name || isMountain(w.tags)) trails.push({ id: w.id, name: w.tags.name ?? null, kind: w.tags.highway, source: "osm", line: w.geometry });
}
// 큐레이션 코스(동백섬 폐곡선 등)와 코스 경로(OSM way ID 기반)를 추가
const curated = JSON.parse((await readFile(path.join(root, "data/curated/dongbaek_loop.geojson"), "utf8")).replace(/^﻿/, ""));
trails.push({ id: -1, name: "동백섬 순환 산책로(큐레이션)", kind: "curated", source: "curated", line: curated.geometry.coordinates });
const courseWays = query("SELECT course_code, osm_way_id, name, geometry_geojson FROM course_way WHERE osm_way_id <> -1");
const have = new Set(trails.map((t) => t.id));
for (const c of courseWays) if (!have.has(c.osm_way_id)) { trails.push({ id: c.osm_way_id, name: c.name, kind: "course", source: "course:" + c.course_code, line: JSON.parse(c.geometry_geojson).coordinates }); have.add(c.osm_way_id); }
console.log("산책로(해운대):", trails.length, "개 (OSM 전체 길", osm.ways.length, "중 이름 있음·산길만 + 코스)");

// 2) 거리 계산용 선분(미터 평면) + 길별 bbox
const T = trails.map((t) => {
  const P = t.line.map(toXY);
  const xs = P.map((p) => p[0]), ys = P.map((p) => p[1]);
  return { t, P, box: [Math.min(...xs) - RADIUS_M, Math.min(...ys) - RADIUS_M, Math.max(...xs) + RADIUS_M, Math.max(...ys) + RADIUS_M] };
});
function nearestTrail(p) {
  let best = { d: Infinity, id: null };
  for (const { t, P, box } of T) {
    if (p[0] < box[0] || p[0] > box[2] || p[1] < box[1] || p[1] > box[3]) continue;
    for (let i = 0; i < P.length - 1; i++) {
      const dx = P[i + 1][0] - P[i][0], dy = P[i + 1][1] - P[i][1], l2 = dx * dx + dy * dy;
      const u = l2 ? Math.max(0, Math.min(1, ((p[0] - P[i][0]) * dx + (p[1] - P[i][1]) * dy) / l2)) : 0;
      const d = Math.hypot(p[0] - P[i][0] - u * dx, p[1] - P[i][1] - u * dy);
      if (d < best.d) best = { d, id: t.id };
    }
  }
  return best;
}

// 3) 대상 데이터
const signals = readHaeundaeSignals(path.join(root, "data/raw/busan_signal_ctrl_20250826.csv")).filter((s) => s.is_signal);
const xtext = new TextDecoder("euc-kr").decode(await readFile(path.join(root, "data/raw/busan_crosswalk_20250423.csv"))).replace(/^﻿/, "").trim().split(/\r?\n/);
const xh = xtext[0].split(",");
const parseLine = (l) => { const o = []; let f = "", q = false; for (let i = 0; i < l.length; i++) { const c = l[i]; if (q) { if (c === '"') { if (l[i + 1] === '"') { f += '"'; i++; } else q = false; } else f += c; } else if (c === '"') q = true; else if (c === ",") { o.push(f); f = ""; } else f += c; } o.push(f); return o; };
const crosswalks = xtext.slice(1).map((l) => Object.fromEntries(parseLine(l).map((v, i) => [xh[i], v]))).filter((r) => r["행정구역(구)"] === "해운대구")
  .map((r) => ({ id: +r["순번"], name: r["교차로"], lon: +r["경도"], lat: +r["위도"] })).filter((r) => Number.isFinite(r.lon) && Number.isFinite(r.lat));
const clusters = new Map(clusterCrosswalks(crosswalks).map((c) => [c.crosswalk_id, c]));
const slopeRows = [];
for (let off = 0; ; off += 5000) { const part = query(`SELECT segment_id,start_lat,start_lon,end_lat,end_lon,grade_pct,length_m FROM haeundae_walking_grade10_20261127 ORDER BY segment_id LIMIT 5000 OFFSET ${off}`); slopeRows.push(...part); if (part.length < 5000) break; }

// 4) 500m 이내만 남김
const keepSignals = [], keepCross = [], keepSlope = [];
for (const s of signals) { const n = nearestTrail(toXY([s.lon, s.lat])); if (n.d <= RADIUS_M) keepSignals.push({ id: s.sid ?? null, mgmt: s.mgmt_no, place: s.place, kind: s.control_kind_norm, lon: s.lon, lat: s.lat, d: Math.round(n.d), trail: n.id }); }
for (const c of crosswalks) { const n = nearestTrail(toXY([c.lon, c.lat])); if (n.d <= RADIUS_M) keepCross.push({ id: c.id, name: c.name, lon: c.lon, lat: c.lat, d: Math.round(n.d), trail: n.id, primary: clusters.get(c.id).is_primary }); }
for (const r of slopeRows) {
  const pts = [[r.start_lon, r.start_lat], [r.end_lon, r.end_lat], [(r.start_lon + r.end_lon) / 2, (r.start_lat + r.end_lat) / 2]].map(toXY);
  const ns = pts.map(nearestTrail), best = ns.reduce((a, b) => (b.d < a.d ? b : a));
  if (best.d <= RADIUS_M) keepSlope.push({ id: r.segment_id, a: [r.start_lon, r.start_lat], b: [r.end_lon, r.end_lat], grade: r.grade_pct, len: r.length_m, d: Math.round(best.d), trail: best.id });
}
const meta = {
  generatedAt: new Date().toISOString(), radiusM: RADIUS_M, region: "부산광역시 해운대구",
  rule: "산책로 선분까지의 최소 거리가 500m 이내인 항목만 보존. 경사 구간은 양 끝점·중점 3점 중 최소 거리로 판정하고 구간 전체를 보존(절단 없음).",
  trailSet: `큐레이션 코스 + OSM 이름 있는 길 + 이름 없는 path/track/bridleway = ${trails.length}개`,
  counts: { signals: { total: signals.length, kept: keepSignals.length }, crosswalks: { total: crosswalks.length, kept: keepCross.length }, slope: { total: slopeRows.length, kept: keepSlope.length } },
  caveats: ["경사 원본은 30m 해상도 DEM으로 계산된 외부 값이라 오차가 크고 최대값은 이상치일 수 있음", "신호제어기는 교차로 단위이며 데이터기준일자가 2020-12-31", "OSM 길은 입력된 것만 포함"],
};
await mkdir(path.join(root, "public/data/haeundae"), { recursive: true });
await writeFile(path.join(root, "public/data/haeundae/trail-context.json"), JSON.stringify({ ...meta, signals: keepSignals, crosswalks: keepCross, slope: keepSlope }));

// 5) 쿼터 초기화 후 적용할 D1 SQL(대기)
const q = (v) => (v === null || v === undefined ? "NULL" : typeof v === "number" ? String(v) : "'" + String(v).replace(/'/g, "''") + "'");
const sql = ["DELETE FROM trail_context_slope;", "DELETE FROM trail_context_crosswalk;", "DELETE FROM trail_context_signal;", "DELETE FROM haeundae_trail;"];
for (let i = 0; i < trails.length; i += 50) sql.push(`INSERT INTO haeundae_trail (id,name,kind,source,length_m,geometry_geojson) VALUES\n` + trails.slice(i, i + 50).map((t) => `(${[t.id, q(t.name), q(t.kind), q(t.source), Math.round(t.line.slice(1).reduce((s, p, k) => s + Math.hypot(...[0, 1].map((a) => (toXY(p)[a] - toXY(t.line[k])[a]))), 0)), q(JSON.stringify({ type: "LineString", coordinates: t.line }))].join(",")})`).join(",\n") + ";");
const chunk = (arr, f, table, cols) => { for (let i = 0; i < arr.length; i += 100) sql.push(`INSERT INTO ${table} (${cols}) VALUES ` + arr.slice(i, i + 100).map(f).join(",") + ";"); };
chunk(keepSignals, (s) => `(${[s.id, q(s.mgmt), q(s.place), q(s.kind), s.lon, s.lat, s.d, s.trail].join(",")})`, "trail_context_signal", "signal_id,mgmt_no,place,kind,lon,lat,distance_m,nearest_trail_id");
chunk(keepCross, (c) => `(${[c.id, q(c.name), c.lon, c.lat, c.d, c.trail, c.primary].join(",")})`, "trail_context_crosswalk", "crosswalk_id,name,lon,lat,distance_m,nearest_trail_id,is_primary");
chunk(keepSlope, (r) => `(${[r.id, r.a[0], r.a[1], r.b[0], r.b[1], r.grade, r.len, r.d, r.trail].join(",")})`, "trail_context_slope", "segment_id,start_lon,start_lat,end_lon,end_lat,grade_pct,length_m,distance_m,nearest_trail_id");
await mkdir(path.join(root, "db/pending"), { recursive: true });
await writeFile(path.join(root, "db/pending/20261128_trail_context.sql"), sql.join("\n") + "\n");
console.log(JSON.stringify(meta.counts), "→ public/data/haeundae/trail-context.json, db/pending/20261128_trail_context.sql");
