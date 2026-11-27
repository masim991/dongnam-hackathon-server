// 다른 세션이 적재하는 외부 경사 표를 지우거나 바꾸기 전에 읽기 전용으로 CSV 스냅샷을 만든다(복구용).
// 실행: node scripts/snapshot-external.mjs   → db/generated/snapshots/<표이름>_<날짜>.csv (Git 제외)
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { query, root } from "./db-lib.mjs";

const dir = path.join(root, "db/generated/snapshots");
mkdirSync(dir, { recursive: true });
const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, "");
const tables = query("SELECT table_name FROM data_domain WHERE owner='external'").map((r) => r.table_name);
const esc = (v) => (v === null || v === undefined ? "" : /[",\n]/.test(String(v)) ? '"' + String(v).replace(/"/g, '""') + '"' : String(v));
for (const t of tables) {
  if (!/^[a-z0-9_]+$/i.test(t)) { console.log("건너뜀(이름 이상):", t); continue; }
  const rows = [];
  for (let off = 0; ; off += 5000) {
    const part = query(`SELECT * FROM ${t} ORDER BY rowid LIMIT 5000 OFFSET ${off}`);
    rows.push(...part);
    if (part.length < 5000) break;
  }
  if (!rows.length) { console.log(t, "0행 — 스냅샷 생략"); continue; }
  const head = Object.keys(rows[0]);
  writeFileSync(path.join(dir, `${t}_${stamp}.csv`), [head.join(","), ...rows.map((r) => head.map((h) => esc(r[h])).join(","))].join("\n") + "\n");
  console.log(t, rows.length, "행 저장");
}
