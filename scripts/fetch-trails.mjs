// 부울경 일대 산책로·산길(OSM)을 0.1° 격자(bbox)로 나눠 받아 data/raw/trails_cells/ 에 캐시한다. 중단 후 다시 실행하면 이어서 받는다.
// 실행: node scripts/fetch-trails.mjs [--limit N]   (N: 이번 실행에서 받을 격자 수 상한)
import { readFile, writeFile, mkdir, access } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const DIR = path.join(root, "data/raw/trails_cells");
const URLS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"];
export const BBOX = { s: 34.6, w: 127.6, n: 36.0, e: 129.5 }; // 부울경을 덮는 사각형(행정경계 밖은 area 필터로 제외)
export const STEP = 0.1;
const HIGHWAYS = "path|footway|pedestrian|steps|track|bridleway";
const limit = Number(process.argv[process.argv.indexOf("--limit") + 1]) || Infinity;
const exists = (p) => access(p).then(() => true, () => false);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function overpass(query) {
  for (let a = 0; a < 6; a++) {
    try {
      const res = await fetch(URLS[a % URLS.length], {
        method: "POST", headers: { "content-type": "application/x-www-form-urlencoded", "user-agent": "dongnam-running-trails/1.0" },
        body: "data=" + encodeURIComponent(query),
      });
      if (res.ok) return await res.json();
      if (res.status === 429 || res.status >= 500) await sleep(8000 * (a + 1)); // 서버 부하 시 길게 기다림
    } catch { await sleep(5000 * (a + 1)); }
  }
  return null;
}

// 행정구역 필터는 서버 부하가 커서 쓰지 않고 bbox 로만 받는다. 부울경 밖(전남·경북 등)은 build-trails.mjs 가 경계 다각형으로 잘라낸다.
const cellQuery = (s, w, n, e) => `[out:json][timeout:120];
way["highway"~"^(${HIGHWAYS})$"]["footway"!~"^(sidewalk|crossing|traffic_island)$"]["access"!~"^(private|no)$"](${s},${w},${n},${e});
out tags geom;`;
await mkdir(DIR, { recursive: true });

// 행정경계 다각형과 겹치는 칸만 받는다(바다·타 시도 칸 생략). 경계 파일이 없으면 모든 칸.
async function neededCells() {
  const all = [];
  for (let s = BBOX.s; s < BBOX.n - 1e-9; s += STEP) for (let w = BBOX.w; w < BBOX.e - 1e-9; w += STEP) all.push([+s.toFixed(1), +w.toFixed(1)]);
  const bfile = path.join(root, "data/raw/region_boundaries.json");
  if (!(await exists(bfile))) return all;
  const rings = Object.values(JSON.parse((await readFile(bfile, "utf8")).replace(/^\uFEFF/, "")).regions).flat();
  const inRing = (lon, lat, ring) => { let c = false; for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) { const [xi, yi] = ring[i], [xj, yj] = ring[j]; if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) c = !c; } return c; };
  return all.filter(([s, w]) => {
    const n = s + STEP, e = w + STEP;
    if ([[w, s], [e, s], [w, n], [e, n], [(w + e) / 2, (s + n) / 2]].some(([x, y]) => rings.some((r) => inRing(x, y, r)))) return true;
    return rings.some((r) => r.some(([x, y]) => x >= w && x <= e && y >= s && y <= n)); // 경계선이 칸을 지나는 경우
  });
}

const cells = await neededCells();
let done = 0, fetched = 0, failed = [];
const queue = [];
for (const [s, w] of cells) {
  const file = path.join(DIR, `${s.toFixed(1)}_${w.toFixed(1)}.json`);
  if (await exists(file)) { done++; continue; }
  queue.push([s, w, file]);
}
console.log(`필요한 칸 ${cells.length}개, 이미 받음 ${done}개, 남음 ${queue.length}개`);
async function worker(id) {
  while (queue.length && fetched < limit) {
    const [s, w, file] = queue.shift();
    const json = await overpass(cellQuery(s, w, +(s + STEP).toFixed(1), +(w + STEP).toFixed(1)));
    if (!json) { failed.push(`${s}_${w}`); console.log("실패:", s, w); continue; }
    await writeFile(file, JSON.stringify({ s, w, step: STEP, ways: json.elements.filter((e) => e.type === "way").map((e) => ({ id: e.id, tags: e.tags ?? {}, geometry: e.geometry.map((g) => [+g.lon.toFixed(6), +g.lat.toFixed(6)]) })) }));
    fetched++; done++;
    if (fetched % 5 === 0) console.log(`진행 ${done}/${cells.length} (이번 실행 ${fetched}개 수신, ${new Date().toTimeString().slice(0, 8)})`);
    await sleep(1500);
  }
}
await Promise.all([worker(1), worker(2)]); // 공개 서버 예의상 동시 2건까지
console.log(`완료: 캐시 ${done}/${cells.length}, 실패 ${failed.length}`, failed.slice(0, 20).join(","));