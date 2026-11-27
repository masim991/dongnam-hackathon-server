// D1이 서로 엉키지 않았는지 점검한다. 실패 항목이 있으면 종료 코드 1.
// 실행: node scripts/db-check.mjs [--local]
import { query, mode } from "./db-lib.mjs";

const results = [];
const check = (name, ok, detail = "") => results.push({ name, ok, detail });
const one = (sql) => Object.values(query(sql)[0] ?? {})[0];

// 1) 모든 표가 등록부에 있어야 한다(등록 안 된 표 = 출처·소유 불명)
const unregistered = query(
  "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' AND name NOT IN (SELECT table_name FROM data_domain)",
).map((r) => r.name);
check("모든 표가 data_domain에 등록됨", unregistered.length === 0, unregistered.join(", "));

// 2) 등록부에 있는데 실제로 없는 표
const missing = query("SELECT table_name FROM data_domain WHERE table_name NOT IN (SELECT name FROM sqlite_master WHERE type='table')").map((r) => r.table_name);
check("등록된 표가 모두 존재함", missing.length === 0, missing.join(", "));

// 3) 신호 영역 무결성
check("busan_crosswalk 행 있음", one("SELECT COUNT(*) FROM busan_crosswalk") > 0);
check("course_crosswalk가 없는 횡단보도를 가리키지 않음",
  one("SELECT COUNT(*) FROM course_crosswalk cc LEFT JOIN busan_crosswalk c ON c.id=cc.crosswalk_id WHERE c.id IS NULL") === 0);
check("course_osm_crossing이 없는 OSM 노드를 가리키지 않음",
  one("SELECT COUNT(*) FROM course_osm_crossing co LEFT JOIN osm_crossing o ON o.osm_id=co.osm_id WHERE o.osm_id IS NULL") === 0);
check("course_way·집계가 없는 코스를 가리키지 않음",
  one("SELECT (SELECT COUNT(*) FROM course_way w LEFT JOIN course c ON c.code=w.course_code WHERE c.code IS NULL) + (SELECT COUNT(*) FROM course_crossing_summary s LEFT JOIN course c ON c.code=s.course_code WHERE c.code IS NULL)") === 0);
check("모든 코스에 경로가 있음", one("SELECT COUNT(*) FROM course c WHERE NOT EXISTS (SELECT 1 FROM course_way w WHERE w.course_code=c.code)") === 0);

check("course_entrance가 없는 코스를 가리키지 않음", one("SELECT COUNT(*) FROM course_entrance e LEFT JOIN course c ON c.code=e.course_code WHERE c.code IS NULL") === 0);

// 4) 경사 영역은 신호 영역과 섞이지 않았는지(외부 표는 읽기만)
const slopeExists = query("SELECT name FROM sqlite_master WHERE name='haeundae_walking_grade10_20261127'").length > 0;
if (slopeExists) {
  check("경사 표 출처가 src_dataset에 등록됨", one("SELECT COUNT(*) FROM src_dataset WHERE id='slope-haeundae-grade10-20261127'") === 1);
  check("v_slope_grade10 뷰가 있음", query("SELECT name FROM sqlite_master WHERE type='view' AND name='v_slope_grade10'").length === 1);
}
check("src_dataset에 신호 영역 출처 2건", one("SELECT COUNT(*) FROM src_dataset WHERE id IN ('busan-crosswalk-20250423','osm-haeundae-crossings')") === 2);

let failed = 0;
for (const r of results) { if (!r.ok) failed++; console.log(`${r.ok ? "통과" : "실패"}  ${r.name}${r.ok || !r.detail ? "" : "  → " + r.detail}`); }
console.log(`\n${mode} · ${results.length - failed}/${results.length} 통과`);
process.exit(failed ? 1 : 0);
