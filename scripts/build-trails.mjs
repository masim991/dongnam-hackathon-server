// 캐시된 OSM 격자(data/raw/trails_cells)를 합쳐 부울경 경계로 자르고, 단순화해 지도용 벡터 조각(public/data/trails/)으로 만든다.
// 실행: node scripts/build-trails.mjs [--allow-partial] [--dry-run] [--cells 폴더] [--source "출처 문구"]
//   기본: 기대 격자 100% 수집됐을 때만 만든다. 일부만 모인 상태는 --allow-partial 로 명시해야 하며 manifest 에 partial:true 와 누락 목록이 남는다.
//   --dry-run: 임시 폴더에서 만들고 검증만 한 뒤 public 은 건드리지 않는다(캐시는 읽기만).
// 새 출력은 .wrangler/trail-build/ 에서 만들어 검증한 뒤 폴더를 교체한다. 실패하면 기존 public/data/trails 는 그대로다.
// 배포 전 점검: node scripts/check-trails.mjs
import path from "node:path";
import { fileURLToPath } from "node:url";
import { BuildError, parseCliArgs, runBuild } from "./trail-build-lib.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
try {
  const parsed = parseCliArgs(process.argv.slice(2), { flags: ["--allow-partial", "--dry-run"], values: ["--cells", "--source"] }); // 모르는 옵션은 거부(오타로 실제 교체 방지)
  const args = new Set(Object.keys(parsed));
  const summary = await runBuild({
    cellsDir: parsed["--cells"] ? path.resolve(parsed["--cells"]) : path.join(root, "data/raw/trails_cells"),
    source: parsed["--source"],
    outDir: path.join(root, "public/data/trails"),
    workDir: path.join(root, ".wrangler/trail-build"),
    boundariesPath: path.join(root, "data/raw/region_boundaries.json"),
    allowPartial: args.has("--allow-partial"),
    dryRun: args.has("--dry-run"),
  });
  console.log(JSON.stringify(summary));
} catch (e) {
  console.error(JSON.stringify({ ok: false, code: e instanceof BuildError ? e.code : "unexpected", message: e.message, ...(e.detail ?? {}) }));
  process.exit(e instanceof BuildError ? 2 : 1);
}
