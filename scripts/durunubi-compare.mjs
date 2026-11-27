// 두루누비(해파랑길) GPX와 우리 해운대 코스를 비교한다.
// 준비: data.go.kr 에서 '한국관광공사_두루누비 정보 서비스_GW' 활용신청 후, 프로젝트 루트 .dev.vars 에
//       DURUNUBI_SERVICE_KEY=발급키  한 줄을 넣는다(.dev.vars는 Git 제외).
// 실행: node scripts/durunubi-compare.mjs [--save] [--local]   (--save: 결과를 D1 course_reference_compare 에 적재)
//       node scripts/durunubi-compare.mjs --selftest          (키 없이 비교 함수만 시험)
// 주의: 오퍼레이션 경로(routeList/courseList)와 응답 필드(gpxpath 등)는 공식 명세서를 아직 열람하지 못해 가정한 값이다.
//       실패하면 응답 키 목록을 출력하므로 그에 맞춰 FIELD 상수를 고친다.
import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { query, applyFile, root } from "./db-lib.mjs";
import { compareLines, lineLengthM, parseGpx } from "./lib-geo.mjs";

const BASE = "https://apis.data.go.kr/B551011/Durunubi";
const FIELD = { gpx: "gpxpath", courseName: "crsKorNm", routeIdx: "routeIdx", routeName: "themeNm", distance: "crsDstnc", sigun: "sigun" };
const OUR_CODES = ["HD-BEACHPROM", "HD-BEACHPROM-EXT", "HD-DONGBAEK", "HD-BEACHROAD", "HD-MOONTAN"];
const TOL_M = 30;
const MAX_GPX_BYTES = 5 * 1024 * 1024;

async function readKey() {
  if (process.env.DURUNUBI_SERVICE_KEY) return process.env.DURUNUBI_SERVICE_KEY.trim();
  try {
    const m = /^DURUNUBI_SERVICE_KEY=(.+)$/m.exec(await readFile(path.join(root, ".dev.vars"), "utf8"));
    return m ? m[1].trim() : null;
  } catch { return null; }
}

async function call(op, key, params) {
  const k = key.includes("%") ? key : encodeURIComponent(key);
  const qs = new URLSearchParams({ MobileOS: "ETC", MobileApp: "dongnam-running", _type: "json", numOfRows: "100", pageNo: "1", ...params });
  const res = await fetch(`${BASE}/${op}?serviceKey=${k}&${qs}`);
  const text = await res.text();
  if (!res.ok) throw new Error(`${op} HTTP ${res.status}: ${text.slice(0, 200).replace(k, "***")}`);
  let json;
  try { json = JSON.parse(text); } catch { throw new Error(`${op} 응답이 JSON이 아님: ${text.slice(0, 200).replace(k, "***")}`); }
  const items = json?.response?.body?.items?.item ?? [];
  return Array.isArray(items) ? items : [items];
}

async function fetchGpx(url) {
  const u = new URL(url);
  if (u.protocol !== "https:" && u.protocol !== "http:") throw new Error("허용하지 않는 GPX 주소");
  const res = await fetch(u.href.replace(/^http:/, "https:"));
  if (!res.ok) throw new Error("GPX HTTP " + res.status);
  const buf = await res.arrayBuffer();
  if (buf.byteLength > MAX_GPX_BYTES) throw new Error("GPX가 너무 큼");
  return new TextDecoder().decode(buf);
}

async function ourCourses() {
  const out = {};
  for (const code of OUR_CODES) {
    const rows = query(`SELECT geometry_geojson FROM course_way WHERE course_code='${code}'`);
    out[code] = rows.map((r) => JSON.parse(r.geometry_geojson).coordinates);
  }
  return out;
}

// 우리 코스(여러 way) 각각을 기준 선과 비교해 가중 평균
function compareCourse(ways, ref) {
  let total = 0, within = 0, maxDev = 0;
  for (const w of ways) {
    const c = compareLines(w, ref, TOL_M);
    total += c.samples; within += c.shareWithinTol * c.samples; maxDev = Math.max(maxDev, c.maxDeviationM);
  }
  return { share: total ? within / total : 0, maxDev };
}

if (process.argv.includes("--selftest")) {
  const ours = [[129.160, 35.159], [129.165, 35.160], [129.170, 35.160]];
  const shifted = ours.map(([lo, la]) => [lo, la + 0.00005]); // 약 5.6m 북쪽
  const far = ours.map(([lo, la]) => [lo, la + 0.0006]); // 약 67m 북쪽
  const a = compareLines(ours, shifted, 30), b = compareLines(ours, far, 30);
  const gpx = `<gpx><trk><trkseg><trkpt lat="35.159" lon="129.160"><ele>1</ele></trkpt><trkpt lon="129.170" lat="35.160"/></trkseg></trk></gpx>`;
  const pts = parseGpx(gpx);
  const ok = a.shareWithinTol === 1 && a.maxDeviationM < 8 && b.shareWithinTol === 0 && b.maxDeviationM > 60 && pts.length === 2 && pts[1][0] === 129.17 && Math.abs(lineLengthM(ours) - 920) < 40;
  console.log(JSON.stringify({ near: a, far: b, gpxPoints: pts.length, ok }));
  process.exit(ok ? 0 : 1);
}

const key = await readKey();
if (!key) {
  console.log(`DURUNUBI_SERVICE_KEY가 없습니다.
1) https://www.data.go.kr/data/15101974/openapi.do 에서 '활용신청'(개발계정은 자동승인)
2) 발급된 일반 인증키를 프로젝트 루트 .dev.vars 에  DURUNUBI_SERVICE_KEY=키  로 저장(Git 제외 파일)
3) node scripts/durunubi-compare.mjs --save`);
  process.exit(2);
}

const routes = await call("routeList", key, {});
console.log("길 목록 키:", routes[0] ? Object.keys(routes[0]).join(", ") : "(없음)");
const route = routes.find((r) => String(r[FIELD.routeName] ?? "").includes("해파랑"));
if (!route) { console.log("해파랑길을 찾지 못함. 길 이름:", routes.map((r) => r[FIELD.routeName]).join(" | ")); process.exit(3); }

const courses = await call("courseList", key, { routeIdx: String(route[FIELD.routeIdx]) });
console.log("코스 목록 키:", courses[0] ? Object.keys(courses[0]).join(", ") : "(없음)");
const nearby = courses.filter((c) => /해운대|부산|오륙도|미포|동백/.test(`${c[FIELD.courseName]} ${c[FIELD.sigun] ?? ""}`));
console.log("해운대·부산 후보 코스:", nearby.map((c) => `${c[FIELD.courseName]}(${c[FIELD.distance] ?? "?"}km)`).join(", ") || "(없음)");

const ours = await ourCourses();
const rows = [];
for (const c of nearby) {
  if (!c[FIELD.gpx]) { console.log("GPX 필드 없음:", c[FIELD.courseName]); continue; }
  let ref;
  try { ref = parseGpx(await fetchGpx(c[FIELD.gpx])); } catch (e) { console.log("GPX 실패:", c[FIELD.courseName], e.message); continue; }
  if (ref.length < 2) continue;
  for (const [code, ways] of Object.entries(ours)) {
    const { share, maxDev } = compareCourse(ways, ref);
    rows.push({ code, ref: c[FIELD.courseName], refLenM: Math.round(lineLengthM(ref)), ourLenM: Math.round(ways.reduce((s, w) => s + lineLengthM(w), 0)), share: +share.toFixed(3), maxDevM: Math.round(maxDev) });
  }
}
console.table(rows);
await mkdir(path.join(root, "db/generated"), { recursive: true });
await writeFile(path.join(root, "db/generated/durunubi-compare.json"), JSON.stringify(rows, null, 2));

if (process.argv.includes("--save") && rows.length) {
  const esc = (s) => String(s).replace(/'/g, "''");
  const sql = rows.map((r) => `INSERT INTO course_reference_compare (course_code,ref_source,ref_name,ref_length_m,course_length_m,share_within_${TOL_M}m,max_dev_m,compared_at) VALUES ('${r.code}','durunubi','${esc(r.ref)}',${r.refLenM},${r.ourLenM},${r.share},${r.maxDevM},'${new Date().toISOString()}');`).join("\n");
  await writeFile(path.join(root, "db/generated/durunubi-compare.sql"), "DELETE FROM course_reference_compare WHERE ref_source='durunubi';\n" + sql + "\n");
  applyFile("db/generated/durunubi-compare.sql");
  console.log("D1 course_reference_compare 에 저장:", rows.length, "행");
}
