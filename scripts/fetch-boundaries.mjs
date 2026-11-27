// 부산·울산·경남 행정경계를 OSM에서 받아 외곽선(outer ring) 다각형으로 data/raw/region_boundaries.json 에 저장한다.
// 실행: node scripts/fetch-boundaries.mjs
import { writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const URLS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function overpass(q) {
  for (let a = 0; a < 6; a++) {
    try {
      const r = await fetch(URLS[a % 2], { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded", "user-agent": "dongnam-running-trails/1.0" }, body: "data=" + encodeURIComponent(q) });
      if (r.ok) return await r.json();
    } catch { /* 재시도 */ }
    await sleep(6000 * (a + 1));
  }
  throw new Error("overpass failed");
}

// way 조각들을 끝점이 맞닿는 대로 이어 닫힌 고리로 만든다.
function rings(ways) {
  const key = (p) => p.lon.toFixed(7) + "," + p.lat.toFixed(7);
  const segs = ways.map((w) => w.slice());
  const out = [];
  while (segs.length) {
    let ring = segs.pop();
    let grew = true;
    while (grew && key(ring[0]) !== key(ring.at(-1))) {
      grew = false;
      for (let i = 0; i < segs.length; i++) {
        const s = segs[i];
        if (key(s[0]) === key(ring.at(-1))) ring = ring.concat(s.slice(1));
        else if (key(s.at(-1)) === key(ring.at(-1))) ring = ring.concat(s.slice(0, -1).reverse());
        else if (key(s.at(-1)) === key(ring[0])) ring = s.concat(ring.slice(1));
        else if (key(s[0]) === key(ring[0])) ring = s.slice(1).reverse().concat(ring);
        else continue;
        segs.splice(i, 1); grew = true; break;
      }
    }
    out.push(ring);
  }
  return out;
}

const result = {};
for (const name of ["부산광역시", "울산광역시", "경상남도"]) {
  const j = await overpass(`[out:json][timeout:120];relation["boundary"="administrative"]["admin_level"="4"]["name"="${name}"];out geom;`);
  const rel = j.elements.find((e) => e.type === "relation");
  if (!rel) throw new Error("경계를 찾지 못함: " + name);
  const outer = rel.members.filter((m) => m.type === "way" && m.role === "outer" && m.geometry).map((m) => m.geometry);
  const rs = rings(outer).filter((r) => r.length > 3);
  result[name] = rs.map((r) => r.map((p) => [+p.lon.toFixed(6), +p.lat.toFixed(6)]));
  console.log(name, "외곽 고리", rs.length, "점", rs.reduce((s, r) => s + r.length, 0));
  await sleep(2000);
}
await mkdir(path.join(root, "data/raw"), { recursive: true });
await writeFile(path.join(root, "data/raw/region_boundaries.json"), JSON.stringify({ fetchedAt: new Date().toISOString(), source: "OpenStreetMap contributors (ODbL), admin_level=4", regions: result }));
console.log("저장 완료");
