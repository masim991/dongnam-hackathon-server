// 캐시된 OSM 격자(data/raw/trails_cells)를 합쳐 부울경 경계로 자르고, 단순화해 지도용 벡터 조각(public/data/trails/)으로 만든다.
// 실행: node scripts/build-trails.mjs
// 출력: public/data/trails/<위도>_<경도>.json (0.1° 조각), public/data/trails/index.json (목록·수집 정보·한계)
import { readFile, readdir, writeFile, mkdir, rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const CELLS = path.join(root, "data/raw/trails_cells");
const OUT = path.join(root, "public/data/trails");
const TOL_M = 4;          // 선 단순화 허용 오차(m)
const MIN_LEN_M = 20;     // 이보다 짧은 조각은 버림
const LAT0 = 35.2, KLA = 111320, KLO = 111320 * Math.cos((LAT0 * Math.PI) / 180);

const boundaries = JSON.parse((await readFile(path.join(root, "data/raw/region_boundaries.json"), "utf8")).replace(/^﻿/, ""));
const REG = { 부산광역시: "부산", 울산광역시: "울산", 경상남도: "경남" };
const inRing = (lon, lat, ring) => {
  let c = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i], [xj, yj] = ring[j];
    if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) c = !c;
  }
  return c;
};
const regionOf = (lon, lat) => {
  for (const [name, rings] of Object.entries(boundaries.regions)) if (rings.some((r) => inRing(lon, lat, r))) return REG[name];
  return null;
};

const xy = ([lon, lat]) => [(lon - 129) * KLO, (lat - LAT0) * KLA];
const lenM = (g) => g.slice(1).reduce((s, p, i) => { const a = xy(g[i]), b = xy(p); return s + Math.hypot(b[0] - a[0], b[1] - a[1]); }, 0);

// Douglas–Peucker(미터 평면)
function simplify(g, tol) {
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

// 종류: 이름·태그로 대략 분류(검증된 분류가 아니라 지도 표시용)
function kindOf(t) {
  if (t.highway === "steps") return "steps";
  if (t.highway === "track") return "track";
  if (t.highway === "pedestrian") return "pedestrian";
  if (t.highway === "cycleway") return "cycleway";
  if (t.highway === "footway") return "footway";
  return "path";
}

const files = (await readdir(CELLS).catch(() => [])).filter((f) => f.endsWith(".json"));
if (!files.length) { console.error("캐시된 격자가 없습니다. 먼저 node scripts/fetch-trails.mjs"); process.exit(2); }
const seen = new Set(), chunks = new Map();
const stat = { cells: files.length, waysRaw: 0, dup: 0, outside: 0, tooShort: 0, kept: 0, points: 0, lengthM: 0, byRegion: {} };
for (const f of files) {
  const c = JSON.parse(await readFile(path.join(CELLS, f), "utf8"));
  for (const w of c.ways) {
    stat.waysRaw++;
    if (seen.has(w.id)) { stat.dup++; continue; }
    seen.add(w.id);
    const mid = w.geometry[Math.floor(w.geometry.length / 2)];
    const region = regionOf(mid[0], mid[1]) ?? regionOf(w.geometry[0][0], w.geometry[0][1]);
    if (!region) { stat.outside++; continue; }
    const g = simplify(w.geometry, TOL_M), len = lenM(g);
    if (len < MIN_LEN_M) { stat.tooShort++; continue; }
    // 조각 기준: 선의 대표점이 속한 0.1° 칸. 칸을 걸치는 긴 선은 대표점 칸에만 담긴다(지도는 이웃 칸도 함께 불러 이어 그림).
    const cs = Math.floor(mid[1] * 10) / 10, cw = Math.floor(mid[0] * 10) / 10;
    const key = `${cs.toFixed(1)}_${cw.toFixed(1)}`;
    const item = { i: w.id, k: kindOf(w.tags), r: region, l: Math.round(len), g: g.map(([lo, la]) => [+lo.toFixed(5), +la.toFixed(5)]) };
    if (w.tags.name) item.n = w.tags.name;
    if (w.tags.surface) item.s = w.tags.surface;
    (chunks.get(key) ?? chunks.set(key, []).get(key)).push(item);
    stat.kept++; stat.points += item.g.length; stat.lengthM += len; stat.byRegion[region] = (stat.byRegion[region] ?? 0) + 1;
  }
}
await rm(OUT, { recursive: true, force: true });
await mkdir(OUT, { recursive: true });
const index = [];
for (const [key, items] of [...chunks.entries()].sort()) {
  const body = JSON.stringify({ cell: key, trails: items });
  await writeFile(path.join(OUT, key + ".json"), body);
  index.push({ cell: key, trails: items.length, bytes: Buffer.byteLength(body) });
}
const [firstS] = files[0].split("_");
await writeFile(path.join(OUT, "index.json"), JSON.stringify({
  generatedAt: new Date().toISOString(), cellSizeDeg: 0.1,
  source: "OpenStreetMap contributors (ODbL), Overpass API", attribution: "© OpenStreetMap contributors",
  filter: "highway=path|footway|pedestrian|steps|track|bridleway, footway≠sidewalk|crossing|traffic_island, access≠private|no. 부산·울산·경남 행정경계(admin_level=4) 안만 포함",
  processing: { simplifyToleranceM: TOL_M, minLengthM: MIN_LEN_M, chunkRule: "선 중간 꼭짓점이 속한 0.1° 칸" },
  stats: { ...stat, lengthKm: Math.round(stat.lengthM / 1000), cellsFetched: files.length },
  limits: ["OSM에 입력된 길만 포함(현실의 모든 산책로 보증 아님)", "이름 없는 길이 대부분이며 종류 분류는 태그 기반의 대략값", "수집에 실패한 칸이 있으면 누락(scripts/fetch-trails.mjs 재실행으로 보충)"],
  chunks: index,
}));
console.log(JSON.stringify({ ...stat, lengthKm: Math.round(stat.lengthM / 1000), chunks: index.length, totalMB: +(index.reduce((s, c) => s + c.bytes, 0) / 1e6).toFixed(1) }, null, 2));
