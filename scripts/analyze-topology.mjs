// 해운대 코스와 산책로 집합의 위상 문제를 찾아 보고한다.
// 실행: node scripts/analyze-topology.mjs   → 콘솔 요약 + db/generated/topology-report.json
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { root, query } from "./db-lib.mjs";
import { analyze } from "./topology-lib.mjs";

const rd = (f) => JSON.parse(readFileSync(path.join(root, f), "utf8").replace(/^﻿/, ""));
// 태그(junction 등)는 원본 OSM 캐시에서 보강한다.
const tagsById = new Map();
for (const f of ["data/raw/osm_haeundae_courses.json", "data/raw/osm_haeundae_pathways.json"]) {
  try { for (const e of rd(f).elements) if (e.type === "way") tagsById.set(e.id, e.tags ?? {}); } catch { /* 없으면 건너뜀 */ }
}
try { for (const w of rd("data/raw/osm_haeundae_trails.json").ways) tagsById.set(w.id, w.tags ?? {}); } catch { /* */ }

const courses = query("SELECT code,name,kind FROM course ORDER BY code");
const rows = query("SELECT course_code, osm_way_id, name, highway, geometry_geojson FROM course_way");
const report = { generatedAt: new Date().toISOString(), courses: {} };
for (const c of courses) {
  const ways = rows.filter((r) => r.course_code === c.code).map((r) => ({ id: r.osm_way_id, name: r.name, tags: tagsById.get(r.osm_way_id) ?? { highway: r.highway }, line: JSON.parse(r.geometry_geojson).coordinates }));
  const { issues, stats } = analyze(ways);
  report.courses[c.code] = { name: c.name, kind: c.kind, stats, issues };
  console.log(`${c.code.padEnd(17)} way ${String(ways.length).padStart(2)}  ${JSON.stringify(stats.byType)}`);
}
// 해운대 산책로 집합(D1 haeundae_trail)
const trails = query("SELECT id,name,kind,geometry_geojson FROM haeundae_trail").map((t) => ({ id: t.id, name: t.name, tags: { ...(tagsById.get(t.id) ?? {}), highway: t.kind }, line: JSON.parse(t.geometry_geojson).coordinates }));
const all = analyze(trails);
report.trailSet = { stats: all.stats, issues: all.issues };
console.log(`산책로 집합 ${trails.length}개  ${JSON.stringify(all.stats.byType)}`);
mkdirSync(path.join(root, "db/generated"), { recursive: true });
writeFileSync(path.join(root, "db/generated/topology-report.json"), JSON.stringify(report));
// 지도 표시용: 해운대 산책로 집합(OSM)의 문제 위치. 자동 수정하지 않고 표시만 한다.
mkdirSync(path.join(root, "public/data/haeundae"), { recursive: true });
writeFileSync(path.join(root, "public/data/haeundae/trail-issues.json"), JSON.stringify({
  generatedAt: report.generatedAt, scope: "해운대 산책로 집합(이름 있는 길·산길·코스)", note: "OSM 선형의 위상 문제를 탐지해 표시만 합니다. 끊김은 현장에서 실제로 막혔다는 뜻이 아닐 수 있습니다.",
  counts: all.stats.byType,
  issues: all.issues.filter((i) => ["roundabout_loop", "gap", "crossing_without_node", "t_without_node"].includes(i.type))
    .map((i) => ({ type: i.type, at: [+i.at[0].toFixed(6), +i.at[1].toFixed(6)], meters: i.meters ?? null, ways: i.ways, detail: i.detail })),
}));
