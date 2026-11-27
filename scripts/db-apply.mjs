// 스키마·등록부를 D1에 적용한다(여러 번 실행해도 안전). 시드(데이터)는 적재하지 않는다.
// 실행: node scripts/db-apply.mjs [--local]
import { applyFile, query, mode } from "./db-lib.mjs";

applyFile("db/schema.sql");
applyFile("db/registry.sql");
const hasSlope = query("SELECT name FROM sqlite_master WHERE type='table' AND name='haeundae_walking_grade10_20261127'").length > 0;
if (hasSlope) applyFile("db/slope.sql");
console.log(`적용 완료 (${mode}). 경사 영역 등록: ${hasSlope ? "예" : "표 없음, 건너뜀"}`);
