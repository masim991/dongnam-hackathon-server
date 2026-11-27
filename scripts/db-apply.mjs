// 기본은 읽기 전용 계획. 쓰기는 명시적 --local/--remote --apply, 데이터는 --pending일 때만 적용.
import { applyFile, query, mode, root } from "./db-lib.mjs";
import { parseApplyOptions, readMigrationFiles, planMigrations, executeMigrationPlan } from './db-migration-lib.mjs';
import { mkdtempSync, writeFileSync, rmSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { writeFile, mkdir } from "node:fs/promises";
import path from "node:path";

// 마이그레이션(한 번만): schema_migration 에 기록하며 번호순으로 적용한다. 새 DB에서는 schema.sql 이 표를 만든다.
try {
  const options = parseApplyOptions(process.argv.slice(2));
  const exists = query("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_migration'").length > 0;
  const hasHash = exists && query('PRAGMA table_info(schema_migration)').some((r) => r.name === 'sha256');
  const records = exists ? query(`SELECT id, ${hasHash ? 'sha256' : 'NULL AS sha256'} FROM schema_migration`) : [];
  const plan = planMigrations(readMigrationFiles(root, options.pending), records);
  const summary = { mode, dryRun: options.dryRun, baselineOnly: options.baselineLegacy,
    files: plan.map(({ sql, ...file }) => file),
    remainingFiles: plan.filter((f) => f.status === 'pending').length,
    maxFiles: Number.isFinite(options.maxFiles) ? options.maxFiles : null,
    note: '바이트 수는 행 수나 쓰기 한도가 아닙니다. 삭제·인덱스 갱신도 쓰기에 포함됩니다. --max-files는 대기 데이터 파일 수를 제한합니다.' };
  if (options.dryRun) { console.log(JSON.stringify(summary)); process.exit(0); }
  if (plan.some((f) => f.status === 'unverified') && !options.baselineLegacy) {
    throw new Error('기존 이력에 SHA256이 없습니다. 백업·내용 확인 후 --baseline-legacy --apply로 기준을 등록하세요.');
  }
  query("CREATE TABLE IF NOT EXISTS schema_migration (id TEXT PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')), sha256 TEXT)");
  if (exists && !hasHash) query('ALTER TABLE schema_migration ADD COLUMN sha256 TEXT');
  const applySql = (sql) => {
    const dir = mkdtempSync(path.join(tmpdir(), 'dongnam-d1-'));
    const file = path.join(dir, 'batch.sql');
    try { writeFileSync(file, sql); applyFile(file); }
    finally { rmSync(file, { force: true }); rmdirSync(dir); }
  };
  const migrations = plan.filter((f) => !f.id.startsWith('pending:'));
  const migrationResult = await executeMigrationPlan(options.baselineLegacy ? plan : migrations, { applySql, baselineLegacy: options.baselineLegacy });
  if (options.baselineLegacy) { console.log(JSON.stringify({ ...summary, ...migrationResult })); process.exit(0); }
  applyFile("db/schema.sql");
  applyFile("db/registry.sql");
  const hasSlope = query("SELECT name FROM sqlite_master WHERE type='table' AND name='haeundae_walking_grade10_20261127'").length > 0;
  if (hasSlope) applyFile("db/slope.sql");

  // 다른 세션이 적재하는 경사 표(이름에 walking_grade 포함)는 자동으로 '경사·외부 소유·읽기 전용'으로 등록한다.
  // 그 외 이름의 미등록 표는 일부러 등록하지 않아 db:check 가 실패로 알려 준다.
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
  }
  // 대기 데이터는 파일 단위로 제한·재개한다. 파일 간 묶음 전체의 원자성을 보장하지는 않는다.
  const pendingResult = await executeMigrationPlan(plan.filter((f) => f.id.startsWith('pending:')), { applySql, maxFiles: options.maxFiles });
  const applied = [...migrationResult.applied, ...pendingResult.applied];
  console.log(JSON.stringify({ ...summary, applied, remainingFiles: summary.remainingFiles - applied.length, slopeRegistered: hasSlope }));
} catch (error) {
  console.error(JSON.stringify({ ok: false, message: error.message }));
  process.exitCode = 1;
}
