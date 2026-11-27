// wrangler d1 실행 도우미. 기본은 원격(--remote), --local 을 주면 로컬.
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const DB_NAME = "dongnam-running";
export const mode = process.argv.includes("--local") ? "--local" : "--remote";

function wrangler(args) {
  const r = spawnSync("npx", ["wrangler", "d1", "execute", DB_NAME, mode, ...args], {
    cwd: root, shell: true, encoding: "utf8", maxBuffer: 64 * 1024 * 1024,
    env: { ...process.env, CLOUDFLARE_ACCOUNT_ID: process.env.CLOUDFLARE_ACCOUNT_ID ?? "dfe9c40978c7cc88bae59830f95dd3cc" },
  });
  if (r.status !== 0) throw new Error((r.stderr || r.stdout || "wrangler failed").slice(0, 1500));
  return r.stdout;
}

export function query(sql) {
  const out = wrangler(["--json", "--command", `"${sql.replace(/"/g, "'")}"`]);
  return JSON.parse(out.slice(out.indexOf("[")))[0].results;
}

export function applyFile(relPath) {
  wrangler(["--yes", "--file", relPath]);
}
