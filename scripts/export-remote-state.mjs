// 원격 D1(dongnam-running)의 구조·행 수·코스 목록을 읽기 전용으로 내보내 로컬 DB와 비교할 수 있게 한다. 쓰기·동기화 없음.
// 실행: node scripts/export-remote-state.mjs [--local]   (--local 이면 로컬 D1을 같은 형식으로 내보냄: local-current.json)
// 출력: .wrangler/db-compare/<remote|local>-current.json, <remote|local>-<표>.ndjson (산책로 주변 4개 표 전체 행, 기본키 순서)
import { mkdirSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import { query, root, mode } from "./db-lib.mjs";

const side = mode === "--local" ? "local" : "remote";
const dir = path.join(root, ".wrangler/db-compare");
mkdirSync(dir, { recursive: true });
const SNAPSHOT = [["haeundae_trail", "id"], ["trail_context_signal", "signal_id"], ["trail_context_crosswalk", "crosswalk_id"], ["trail_context_slope", "segment_id"]];

const schema = query("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' ORDER BY type,name");
const tables = schema.filter((s) => s.type === "table").map((s) => s.name);
const counts = Object.fromEntries(tables.map((t) => [t, query(`SELECT COUNT(*) n FROM ${t}`)[0].n]));
const courseCodes = tables.includes("course") ? query("SELECT code FROM course ORDER BY code").map((r) => r.code) : [];

const hashes = {};
for (const [t, pk] of SNAPSHOT) {
  if (!tables.includes(t)) { hashes[t] = null; continue; }
  const cols = query(`SELECT name FROM pragma_table_info('${t}') ORDER BY cid`).map((c) => c.name);
  const h = createHash("sha256");
  const lines = [];
  for (let off = 0; ; off += 2000) {
    const part = query(`SELECT ${cols.join(",")} FROM ${t} ORDER BY ${pk} LIMIT 2000 OFFSET ${off}`);
    for (const r of part) { const line = JSON.stringify(cols.map((c) => r[c])); h.update(line + "\n"); lines.push(line); }
    if (part.length < 2000) break;
  }
  writeFileSync(path.join(dir, `${side}-${t}.ndjson`), JSON.stringify({ columns: cols, orderBy: pk }) + "\n" + lines.join("\n") + "\n");
  hashes[t] = { rows: lines.length, sha256: h.digest("hex"), columns: cols, orderBy: pk };
}

const out = {
  observedAt: new Date().toISOString(), side, databaseId: "1d725a97-b44e-4b81-b921-0d638bb9d813", databaseName: "dongnam-running",
  schema: schema.map(({ type, name, tbl_name, sql }) => ({ type, name, table: tbl_name, sql })),
  counts, courseCodes, snapshotHashes: hashes,
  note: "읽기 전용 조회 결과. 인증정보·토큰 없음. 산책로 주변 4개 표는 기본키 순서의 전체 행을 .ndjson 으로 함께 저장(공공 지리 데이터).",
};
writeFileSync(path.join(dir, `${side}-current.json`), JSON.stringify(out, null, 2));
console.log(`${side}: 표 ${tables.length}개, 코스 ${courseCodes.length}개(${courseCodes.join(",")})`);
console.log(JSON.stringify(counts));
for (const [t, h] of Object.entries(hashes)) console.log(t, h ? `${h.rows}행 sha256=${h.sha256.slice(0, 16)}…` : "없음");
