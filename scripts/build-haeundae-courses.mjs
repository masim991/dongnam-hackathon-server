// 해운대 시범 코스와 부산 신호 횡단보도를 매칭해 D1 시드 SQL을 만든다.
// 실행: node scripts/build-haeundae-courses.mjs
// 입력: data/raw/busan_crosswalk_20250423.csv (CP949), data/raw/osm_haeundae_courses.json (없으면 Overpass에서 받음)
// 출력: db/generated/seed.sql, db/generated/report.json (Git 제외)
import { readFile, writeFile, mkdir, access } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { SCOPE_GU, readHaeundaeSignals, clusterCrosswalks, linkSignals, buildChecks } from "./hd-data.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const CSV_PATH = path.join(root, "data/raw/busan_crosswalk_20250423.csv");
const OSM_PATH = path.join(root, "data/raw/osm_haeundae_courses.json");
const OSM_CROSS_PATH = path.join(root, "data/raw/osm_haeundae_crossings.json"); // 횡단보도·신호기 노드(OSM)
const OSM_IDS_PATH = path.join(root, "data/raw/osm_haeundae_pathways.json"); // wayIds로 지정한 경로(OSM)
const OUT_DIR = path.join(root, "db/generated");

const BUFFER_M = 20; // 코스에서 이 거리 안의 횡단보도를 코스 위 신호로 본다
const MERGE_M = 12; // 이 거리 안의 점은 같은 건널목으로 합쳐 센다
const DATASET_ID = "busan-crosswalk-20250423";
const OSM_DATASET_ID = "osm-haeundae-crossings";

const COURSES = [
  { code: "HD-MOONTAN", name: "달맞이 문탠로드", kind: "trail", match: (t) => /문[탠텐]로드/.test(t.name ?? "") },
  { code: "HD-SUYEONG", name: "수영강 자전거길(해운대 구간)", kind: "riverside", match: (t) => t.name === "수영강 자전거길" },
  { code: "HD-GALMAET", name: "갈맷길(해운대 구간)", kind: "trail", match: (t) => t.name === "갈맷길" },
  { code: "HD-SONGJEONG", name: "송정 해안(송정옛길·동암해안길)", kind: "trail", match: (t) => /^(송정옛길|동암해안길)/.test(t.name ?? "") },
  { code: "HD-BEACHROAD", name: "해운대해변로(차도 기준 근사)", kind: "road_proxy", match: (t) => t.name === "해운대해변로" },
  { code: "HD-DALMAJI", name: "달맞이길(차도 기준 근사)", kind: "road_proxy", match: (t) => t.name === "달맞이길" && !!t.highway,
    note: "달맞이길 차도(도로 중심선) 기준 근사. 문탠로드는 이 길 옆 숲길이라 러너 경로가 아님. 신호 없는 횡단은 OSM 기여자 입력 기준 참고용." },
];

// 이름 대신 OSM way ID로 고르는 코스를 여기에 추가한다: { code, name, kind, wayIds: [...] }
// 예) 해운대 해변 산책로, 동백섬 순환길은 OSM 추출 결과 확인 후 ID를 채운다.
COURSES.push(...(await readExtraCourses()));

async function readExtraCourses() {
  try { return JSON.parse(await readFile(path.join(root, "scripts/extra-courses.json"), "utf8")); } catch { return []; }
}

// data/curated/*.geojson 의 LineString 한 개를 way처럼 다룬다(id -1: OSM way가 아닌 직접 이은 경로).
async function readCurated(rel) {
  const f = JSON.parse((await readFile(path.join(root, rel), "utf8")).replace(/^﻿/, ""));
  const coords = f.geometry.coordinates;
  return { type: "way", id: -1, tags: { name: f.properties?.name ?? "curated", highway: "footway" }, geometry: coords.map(([lon, lat]) => ({ lon, lat })) };
}

const exists = (p) => access(p).then(() => true, () => false);

// 코스 종류별 OSM 횡단 노드 분류
function crossingKind(tags) {
  if (tags.highway === "traffic_signals") return "signal_junction"; // 교차로 신호기 노드
  if (tags.crossing === "traffic_signals" || tags["crossing:signals"] === "yes") return "signal";
  if (["uncontrolled", "marked", "unmarked"].includes(tags.crossing)) return tags.crossing;
  return "unknown";
}

const OVERPASS_URLS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"];
async function overpass(query) {
  let lastErr;
  for (let attempt = 0; attempt < 4; attempt++) {
    const url = OVERPASS_URLS[attempt % OVERPASS_URLS.length];
    try {
      const res = await fetch(url, {
        method: "POST", headers: { "content-type": "application/x-www-form-urlencoded", "user-agent": "course-research/1.0" },
        body: "data=" + encodeURIComponent(query),
      });
      if (res.ok) return await res.json();
      lastErr = new Error("Overpass " + res.status + " " + url);
    } catch (e) { lastErr = e; }
    await new Promise((r) => setTimeout(r, 3000 * (attempt + 1)));
  }
  throw lastErr;
}
async function loadCrossings() {
  if (await exists(OSM_CROSS_PATH)) return JSON.parse(await readFile(OSM_CROSS_PATH, "utf8"));
  const json = await overpass(`[out:json][timeout:120];area["boundary"="administrative"]["name"="해운대구"]->.a;(node(area.a)["highway"="crossing"];node(area.a)["highway"="traffic_signals"];);out tags center;`);
  await mkdir(path.dirname(OSM_CROSS_PATH), { recursive: true });
  await writeFile(OSM_CROSS_PATH, JSON.stringify(json));
  return json;
}

async function loadPathways(ids) {
  if (ids.length === 0) return { elements: [] };
  if (await exists(OSM_IDS_PATH)) {
    const cached = JSON.parse(await readFile(OSM_IDS_PATH, "utf8"));
    const have = new Set(cached.elements.map((e) => e.id));
    if (ids.every((i) => have.has(i))) return cached;
  }
  const json = await overpass(`[out:json][timeout:90];way(id:${ids.join(",")});out tags geom;`);
  await mkdir(path.dirname(OSM_IDS_PATH), { recursive: true });
  await writeFile(OSM_IDS_PATH, JSON.stringify(json));
  return json;
}

function parseCsv(text) {
  const rows = [];
  let row = [], field = "", inQ = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQ) {
      if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else inQ = false; }
      else field += c;
    } else if (c === '"') inQ = true;
    else if (c === ",") { row.push(field); field = ""; }
    else if (c === "\n") { row.push(field); rows.push(row); row = []; field = ""; }
    else if (c !== "\r") field += c;
  }
  if (field !== "" || row.length) { row.push(field); rows.push(row); }
  return rows;
}

async function loadCrosswalks() {
  const buf = await readFile(CSV_PATH);
  const rows = parseCsv(new TextDecoder("euc-kr").decode(buf).replace(/^﻿/, ""));
  const head = rows[0];
  const idx = Object.fromEntries(head.map((h, i) => [h.trim(), i]));
  const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : null; };
  const out = [];
  for (const r of rows.slice(1)) {
    if (r.length < head.length) continue;
    const lat = num(r[idx["위도"]]), lon = num(r[idx["경도"]]);
    if (lat === null || lon === null) continue;
    out.push({
      id: parseInt(r[idx["순번"]], 10), gu: r[idx["행정구역(구)"]], dong: r[idx["행정구역(동)"]], ri: r[idx["행정구역(리)"]],
      jibun: r[idx["지번"]], name: r[idx["교차로"]], signal: r[idx["신호등존재유무"]],
      width: num(r[idx["가로길이"]]), length: num(r[idx["세로길이"]]), area: num(r[idx["면적"]]), lon, lat,
    });
  }
  return { all: out, parsed: rows.length - 1 };
}

async function loadOsm() {
  if (await exists(OSM_PATH)) return JSON.parse(await readFile(OSM_PATH, "utf8"));
  const json = await overpass(`[out:json][timeout:90];area["boundary"="administrative"]["name"="해운대구"]->.a;(
    way(area.a)["name"~"문[탠텐]로드|^수영강 자전거길$|^갈맷길$|^송정옛길|^동암해안길"];
    way(area.a)["name"="해운대해변로"]["highway"];
    way(area.a)["name"="달맞이길"]["highway"];
  );out tags geom;`);
  await mkdir(path.dirname(OSM_PATH), { recursive: true });
  await writeFile(OSM_PATH, JSON.stringify(json));
  return json;
}
// 해운대 중심 기준 평면 근사(미터)
const LAT0 = 35.16, KLA = 111320, KLO = 111320 * Math.cos((LAT0 * Math.PI) / 180);
const toXY = (lat, lon) => [(lon - 129.15) * KLO, (lat - LAT0) * KLA];

function segDist(px, py, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
  let t = l2 === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / l2;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

const lengthOf = (xy) => xy.slice(1).reduce((s, p, i) => s + Math.hypot(p[0] - xy[i][0], p[1] - xy[i][1]), 0);
const q = (v) => (v === null || v === undefined || v === "" ? "NULL" : typeof v === "number" ? String(v) : "'" + String(v).replace(/'/g, "''") + "'");

function cluster(points) {
  const cl = [];
  for (const p of points) {
    const c = cl.find((k) => Math.hypot(k.x - p.x, k.y - p.y) <= MERGE_M);
    if (c) c.n++; else cl.push({ x: p.x, y: p.y, n: 1 });
  }
  return cl.length;
}

const { all: allCrosswalks, parsed } = await loadCrosswalks();
const crosswalks = allCrosswalks.filter((c) => c.gu === SCOPE_GU); // 해운대구만 적재(다른 구는 D1에 넣지 않음)
const SIGNAL_DATASET_ID = "busan-signal-ctrl-20250826";
const signals = readHaeundaeSignals(path.join(root, "data/raw/busan_signal_ctrl_20250826.csv")).map((s, i) => ({ ...s, sid: i + 1 }));
const osm = await loadOsm();
const crossingsJson = await loadCrossings();
const pathways = await loadPathways(COURSES.flatMap((c) => c.wayIds ?? []));
const pts = crosswalks.map((c) => { const [x, y] = toXY(c.lat, c.lon); return { ...c, x, y }; });
const osmPts = crossingsJson.elements
  .filter((e) => e.type === "node" && Number.isFinite(e.lat) && Number.isFinite(e.lon))
  .map((e) => { const [x, y] = toXY(e.lat, e.lon); return { id: e.id, lat: e.lat, lon: e.lon, x, y, kind: crossingKind(e.tags ?? {}), name: e.tags?.name ?? null }; });

const clusters = clusterCrosswalks(crosswalks, MERGE_M);
const links = linkSignals(crosswalks, signals.filter((s) => s.is_signal), 100);
const checks = buildChecks({ crosswalks, clusters, signals, links, osmNodes: osmPts.filter((p) => p.kind === "signal_junction").map((p) => [p.x, p.y]) });

const builtAt = new Date().toISOString();
const report = { builtAt, scope: SCOPE_GU, bufferM: BUFFER_M, mergeM: MERGE_M, crosswalkRows: crosswalks.length, csvRows: parsed, uniqueCrossings: clusters.filter((c) => c.is_primary).length, signals: signals.length, checks, courses: [] };
const sql = [];
const emit = (s) => sql.push(s);

emit("DELETE FROM crosswalk_signal_link;\nDELETE FROM crosswalk_cluster;\nDELETE FROM busan_signal;\nDELETE FROM data_check;\nDELETE FROM course_entrance;\nDELETE FROM course_crossing_summary;\nDELETE FROM course_osm_crossing;\nDELETE FROM osm_crossing;\nDELETE FROM course_crosswalk;\nDELETE FROM course_way;\nDELETE FROM course;\nDELETE FROM busan_crosswalk;\nDELETE FROM src_dataset WHERE id IN ('" + DATASET_ID + "', '" + OSM_DATASET_ID + "', '" + SIGNAL_DATASET_ID + "');");
emit(`INSERT INTO src_dataset (id,name,provider,url,as_of,license,row_count,note) VALUES (${[
  q(OSM_DATASET_ID), q("OpenStreetMap 해운대구 횡단보도·신호기 노드"), q("OpenStreetMap contributors (Overpass API)"),
  q("https://www.openstreetmap.org/copyright"), q(new Date().toISOString().slice(0, 10)), q("ODbL (출처표시·동일조건)"), osmPts.length,
  q("지도 기여자가 입력한 값으로 완전성 미보증. kind: signal / signal_junction / uncontrolled / marked / unmarked / unknown."),
].join(",")});`);
for (let i = 0; i < osmPts.length; i += 100) {
  const vals = osmPts.slice(i, i + 100).map((c) => `(${[c.id, q(c.kind), q(c.name), c.lon, c.lat, q(OSM_DATASET_ID)].join(",")})`);
  emit(`INSERT INTO osm_crossing (osm_id,kind,name,lon,lat,dataset_id) VALUES ${vals.join(",")};`);
}
emit(`INSERT INTO src_dataset (id,name,provider,url,as_of,license,row_count,note) VALUES (${[
  q(DATASET_ID), q("부산광역시_신호등 설치 횡단보도 위치정보"), q("부산광역시 교통정보서비스센터"),
  q("https://www.data.go.kr/data/3072152/fileData.do"), q("2025-04-23"), q("이용허락범위 제한 없음"), crosswalks.length,
  q("원본은 부산 전체(3,815행)이며 해운대구 행만 적재. 신호등이 있는 횡단보도만 포함(신호 없는 횡단보도는 없음). 교차로명은 주변 건물명 라벨. 코스 경로는 OpenStreetMap(ODbL) 선형을 사용."),
].join(",")});`);

for (let i = 0; i < crosswalks.length; i += 100) {
  const vals = crosswalks.slice(i, i + 100).map((c) =>
    `(${[c.id, q(c.gu), q(c.dong), q(c.ri), q(c.jibun), q(c.name), q(c.signal), q(c.width), q(c.length), q(c.area), c.lon, c.lat, q(DATASET_ID)].join(",")})`);
  emit(`INSERT INTO busan_crosswalk (id,gu,dong,ri,jibun,intersection_name,signal,width_m,length_m,area_m2,lon,lat,dataset_id) VALUES\n${vals.join(",\n")};`);
}

emit(`INSERT INTO src_dataset (id,name,provider,url,as_of,license,row_count,note) VALUES (${[
  q(SIGNAL_DATASET_ID), q("부산광역시_신호등 현황(SHP) — 교통신호제어기 위치"), q("부산광역시 교통정보서비스센터"),
  q("https://www.data.go.kr/data/15084253/fileData.do"), q("2025-08-26"), q("이용허락범위 제한 없음"), signals.length,
  q("원본은 부산 전체(2,391행)이며 해운대구 행만 적재. 교차로 신호제어기 단위 위치(보행 신호등 개별 위치 아님). 행의 데이터기준일자는 2020-12-31로 오래됨."),
].join(",")});`);
for (let i = 0; i < signals.length; i += 100) {
  const vals = signals.slice(i, i + 100).map((s) => `(${[s.sid, q(s.mgmt_no), q(s.control_kind), q(s.control_kind_norm), q(s.sigungu), q(s.dong), q(s.address), q(s.place), q(s.road), q(s.road_type), s.is_signal, q(s.note), q(s.installed_on), q(s.confirmed_on), s.lon, s.lat, q(SIGNAL_DATASET_ID)].join(",")})`);
  emit(`INSERT INTO busan_signal (id,mgmt_no,control_kind,control_kind_norm,sigungu,dong,address,place,road,road_type,is_signal,note,installed_on,confirmed_on,lon,lat,dataset_id) VALUES\n${vals.join(",\n")};`);
}
for (let i = 0; i < clusters.length; i += 100) {
  emit(`INSERT INTO crosswalk_cluster (crosswalk_id,cluster_id,is_primary) VALUES ${clusters.slice(i, i + 100).map((c) => `(${c.crosswalk_id},${c.cluster_id},${c.is_primary})`).join(",")};`);
}
for (let i = 0; i < links.length; i += 100) {
  emit(`INSERT INTO crosswalk_signal_link (crosswalk_id,signal_id,distance_m,matched) VALUES ${links.slice(i, i + 100).map((l) => `(${l.crosswalk_id},${l.signal_id},${l.distance_m},${l.matched})`).join(",")};`);
}
{
  const at = new Date().toISOString();
  emit(`INSERT INTO data_check (subject,metric,value,note,checked_at) VALUES\n${checks.map((c) => `(${[q(c.subject), q(c.metric), q(c.value), q(c.note), q(at)].join(",")})`).join(",\n")};`);
}

for (const def of COURSES) {
  // 우선순위: 사람이 이어 붙인 확정 경로(curatedGeometry) > OSM way ID 목록(wayIds) > 이름 규칙(match)
  let ways = def.curatedGeometry
    ? [await readCurated(def.curatedGeometry)]
    : def.wayIds
      ? pathways.elements.filter((e) => e.type === "way" && e.geometry?.length > 1 && def.wayIds.includes(e.id))
      : osm.elements.filter((e) => e.type === "way" && e.geometry?.length > 1 && def.match(e.tags ?? {}));
  // way 일부만 쓰는 경우(예: 동백섬 접근로를 잘라냄): clipMinLon[wayId] 이상 경도의 점만 남김
  ways = ways
    .map((w) => {
      const min = def.clipMinLon?.[String(w.id)];
      return min === undefined ? w : { ...w, geometry: w.geometry.filter((g) => g.lon >= min) };
    })
    .filter((w) => w.geometry.length > 1);
  const segs = [];
  let totalLen = 0;
  for (const w of ways) {
    const xy = w.geometry.map((g) => toXY(g.lat, g.lon));
    totalLen += lengthOf(xy);
    for (let i = 0; i < xy.length - 1; i++) segs.push([...xy[i], ...xy[i + 1]]);
  }
  const matched = [];
  let nearest = Infinity; // 매칭 0일 때 얼마나 먼지 보기 위한 최근접 거리(m)
  for (const p of pts) {
    let m = Infinity;
    for (const s of segs) { const d = segDist(p.x, p.y, s[0], s[1], s[2], s[3]); if (d < m) m = d; }
    if (m < nearest) nearest = m;
    if (m <= BUFFER_M) matched.push({ ...p, dist: m });
  }
  const unique = cluster(matched);
  const perKm = totalLen > 0 ? unique / (totalLen / 1000) : null;
  const note = def.note ?? (def.kind === "road_proxy"
    ? "차도(도로 중심선) 기준 근사. 러너가 걷는 산책로가 아니므로 참고용."
    : "OSM 선형 합. 갈래길·겹침이 포함될 수 있어 길이는 근사. 신호 없는 횡단보도는 집계되지 않음.");
  emit(`INSERT INTO course (code,name,region,gu,kind,length_m,way_count,geometry_source,buffer_m,matched_points,unique_crossings,crossings_per_km,note,built_at) VALUES (${[
    q(def.code), q(def.name), q("부산"), q("해운대구"), q(def.kind), Math.round(totalLen), ways.length, q("OpenStreetMap"), BUFFER_M,
    matched.length, unique, perKm === null ? "NULL" : perKm.toFixed(2), q(note), q(builtAt)].join(",")});`);
  for (const w of ways) {
    const coords = w.geometry.map((g) => [+g.lon.toFixed(6), +g.lat.toFixed(6)]);
    const len = Math.round(lengthOf(w.geometry.map((g) => toXY(g.lat, g.lon))));
    emit(`INSERT INTO course_way (course_code,osm_way_id,name,highway,length_m,geometry_geojson) VALUES (${[
      q(def.code), w.id, q(w.tags?.name), q(w.tags?.highway), len, q(JSON.stringify({ type: "LineString", coordinates: coords }))].join(",")});`);
  }
  for (let i = 0; i < matched.length; i += 100) {
    const vals = matched.slice(i, i + 100).map((m) => `(${[q(def.code), m.id, m.dist.toFixed(1)].join(",")})`);
    emit(`INSERT INTO course_crosswalk (course_code,crosswalk_id,distance_m) VALUES ${vals.join(",")};`);
  }
  // OSM 횡단 노드를 종류별로 집계 (공공 신호 횡단보도에 없는 무신호 횡단 후보 포함)
  const osmMatched = [];
  for (const p of osmPts) {
    let m = Infinity;
    for (const s of segs) { const d = segDist(p.x, p.y, s[0], s[1], s[2], s[3]); if (d < m) m = d; }
    if (m <= BUFFER_M) osmMatched.push({ ...p, dist: m });
  }
  for (let i = 0; i < osmMatched.length; i += 100) {
    const vals = osmMatched.slice(i, i + 100).map((m) => `(${[q(def.code), m.id, q(m.kind), m.dist.toFixed(1)].join(",")})`);
    emit(`INSERT INTO course_osm_crossing (course_code,osm_id,kind,distance_m) VALUES ${vals.join(",")};`);
  }
  const osmCounts = osmMatched.reduce((a, m) => ((a[m.kind] = (a[m.kind] ?? 0) + 1), a), {});
  const summaryRows = [["busan_signal_crosswalk", "signal", unique], ...Object.entries(osmCounts).map(([k, n]) => ["osm", k, n])];
  emit(`INSERT INTO course_crossing_summary (course_code,source,kind,count) VALUES ${summaryRows.map((r) => `(${[q(def.code), q(r[0]), q(r[1]), r[2]].join(",")})`).join(",")};`);
  // 출입구: 다른 way와 이어지지 않은 끝점. 가장 가까운 신호 횡단보도(공공)·OSM 횡단 노드까지의 거리
  const entrances = [];
  if (def.kind !== "road_proxy") {
    const nearest = (list, x, y) => list.reduce((b, c) => { const d = Math.hypot(c.x - x, c.y - y); return d < b.d ? { d, c } : b; }, { d: Infinity, c: null });
    for (const w of ways) {
      const first = w.geometry[0], last = w.geometry.at(-1);
      if (first.lat === last.lat && first.lon === last.lon) continue; // 닫힌 순환은 출입구 없음
      for (const g of [first, last]) {
        const [x, y] = toXY(g.lat, g.lon);
        const shared = ways.some((o) => o !== w && o.geometry.some((q2) => { const [qx, qy] = toXY(q2.lat, q2.lon); return Math.hypot(qx - x, qy - y) <= 3; }));
        if (!shared && !entrances.some((e) => Math.hypot(e.x - x, e.y - y) <= 10)) entrances.push({ x, y, lat: g.lat, lon: g.lon });
      }
    }
    entrances.length = Math.min(entrances.length, 12);
    entrances.forEach((e, i) => {
      const s = nearest(pts, e.x, e.y), o = nearest(osmPts, e.x, e.y);
      Object.assign(e, { sig: Math.round(s.d), osm: Math.round(o.d), osmKind: o.c?.kind ?? null });
      emit(`INSERT INTO course_entrance (course_code,seq,lon,lat,nearest_signal_m,nearest_osm_m,nearest_osm_kind) VALUES (${[q(def.code), i + 1, e.lon, e.lat, e.sig, e.osm, q(e.osmKind)].join(",")});`);
    });
  }
  report.courses.push({
    entrances: entrances.map((e) => ({ lat: +e.lat.toFixed(6), lon: +e.lon.toFixed(6), nearestSignalM: e.sig, nearestOsmM: e.osm, nearestOsmKind: e.osmKind })),
    osmCrossings: osmCounts,
    code: def.code, name: def.name, kind: def.kind, ways: ways.length, lengthM: Math.round(totalLen),
    matchedPoints: matched.length, uniqueCrossings: unique, perKm: perKm === null ? null : +perKm.toFixed(2),
    nearestCrosswalkM: Number.isFinite(nearest) ? Math.round(nearest) : null,
    topLabels: Object.entries(matched.reduce((a, m) => ((a[m.name || "(이름 없음)"] = (a[m.name || "(이름 없음)"] ?? 0) + 1), a), {}))
      .sort((a, b) => b[1] - a[1]).slice(0, 5),
  });
}

await mkdir(OUT_DIR, { recursive: true });
await writeFile(path.join(OUT_DIR, "seed.sql"), sql.join("\n") + "\n");
await writeFile(path.join(OUT_DIR, "report.json"), JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
