// 스키마·등록부를 D1에 적용한다(여러 번 실행해도 안전). 시드(데이터)는 적재하지 않는다.
// 실행: node scripts/db-apply.mjs [--local]
import { applyFile, query, mode } from "./db-lib.mjs";

// 마이그레이션(한 번만): schema_migration 에 기록하며 번호순으로 적용한다. 새 DB에서는 schema.sql 이 표를 만든다.
import { readdirSync } from "node:fs";
import { root as repoRoot } from "./db-lib.mjs";
import pathMod from "node:path";
query("CREATE TABLE IF NOT EXISTS schema_migration (id TEXT PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')))");
const done = new Set(query("SELECT id FROM schema_migration").map((r) => r.id));
for (const f of readdirSync(pathMod.join(repoRoot, "db/migrations")).filter((n) => n.endsWith(".sql")).sort()) {
  if (done.has(f)) continue;
  applyFile("db/migrations/" + f);
  query(`INSERT INTO schema_migration (id) VALUES ('${f}')`);
  console.log("마이그레이션 적용:", f);
}
applyFile("db/schema.sql");
applyFile("db/registry.sql");
const hasSlope = query("SELECT name FROM sqlite_master WHERE type='table' AND name='haeundae_walking_grade10_20261127'").length > 0;
if (hasSlope) applyFile("db/slope.sql");

// 다른 세션이 적재하는 경사 표(이름에 walking_grade 포함)는 자동으로 '경사·외부 소유·읽기 전용'으로 등록한다.
// 그 외 이름의 미등록 표는 일부러 등록하지 않아 db:check 가 실패로 알려 준다.
import { writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { root } from "./db-lib.mjs";
const auto = query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%walking_grade%' AND name NOT IN (SELECT table_name FROM data_domain)").map((r) => r.name);
if (auto.length) {
  const q = (s) => "'" + s.replace(/'/g, "''") + "'";
  const sql = [
    `INSERT OR REPLACE INTO src_dataset (id,name,provider,url,as_of,license,row_count,note) VALUES ('slope-external','경사 10% 이상 보행 구간(외부 적재)','다른 세션이 OpenStreetMap + Copernicus DEM GLO-30으로 가공',NULL,NULL,'OSM ODbL; Copernicus 이용조건 확인 필요',NULL,'읽기 전용. 적재 중일 수 있고 오차가 큼. 신호등 표와 조인하지 않음.');`,
    ...auto.map((n) => `INSERT OR REPLACE INTO data_domain (table_name,domain,owner,source_id,note) VALUES (${q(n)},'slope','external','slope-external','자동 등록(이름에 walking_grade 포함). 읽기 전용, 이름 변경·삭제 금지.');`),
    ...(auto.includes("busan_walking_grade10") ? ["CREATE VIEW IF NOT EXISTS v_slope_busan_grade10 AS SELECT segment_id,district,start_lat,start_lon,end_lat,end_lon,grade_pct,length_m FROM busan_walking_grade10;"] : []),
  ].join("\n");
  await mkdir(path.join(root, "db/generated"), { recursive: true });
  await writeFile(path.join(root, "db/generated/slope_auto.sql"), sql + "\n");
  applyFile("db/generated/slope_auto.sql");
  console.log("경사 외부 표 자동 등록:", auto.join(", "));
}
// 쿼터 초기화 후 적용할 대기 SQL: node scripts/db-apply.mjs --pending  (db/pending/*.sql 을 번호순으로, 한 번만)
if (process.argv.includes("--pending")) {
  const pendDir = pathMod.join(repoRoot, "db/pending");
  const files = readdirSync(pendDir).filter((n) => n.endsWith(".sql")).sort();
  const applied = new Set(query("SELECT id FROM schema_migration").map((r) => r.id));
  for (const f of files) {
    const id = "pending:" + f;
    if (applied.has(id)) continue;
    applyFile("db/pending/" + f);
    query(`INSERT INTO schema_migration (id) VALUES ('${id}')`);
    console.log("대기 SQL 적용:", f);
  }
}
console.log(`적용 완료 (${mode}). 경사 영역 등록: ${hasSlope ? "예" : "표 없음, 건너뜀"}`);
