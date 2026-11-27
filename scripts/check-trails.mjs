// 배포 전 산책로 벡터 점검(읽기 전용). 실행: node scripts/check-trails.mjs [--dir 경로] [--allow-partial]
// 기본은 배포용 점검이라 부분 산출물(partial:true)을 거부한다. --allow-partial 은 시험용이며 배포 허용이 아니다. 모르는 옵션은 거부한다.
import path from "node:path";
import { fileURLToPath } from "node:url";
import { BuildError, checkOutput, parseCliArgs } from "./trail-build-lib.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
let parsed;
try { parsed = parseCliArgs(process.argv.slice(2), { flags: ["--allow-partial"], values: ["--dir"] }); } catch (e) {
  console.error(JSON.stringify({ ok: false, code: e instanceof BuildError ? e.code : "unexpected", message: e.message }));
  process.exit(2);
}
const dir = parsed["--dir"] ? path.resolve(parsed["--dir"]) : path.join(root, "public/data/trails");
const mode = parsed["--allow-partial"] ? "allow-partial" : "deploy";
const result = await checkOutput(dir, { mode }).catch((e) => ({ ok: false, errors: [{ code: "unreadable", message: String(e.message) }] }));
console.log(JSON.stringify({ dir: path.relative(root, dir) || ".", mode, deployAllowed: result.ok && mode === "deploy", ...result }));
process.exit(result.ok ? 0 : 1);
