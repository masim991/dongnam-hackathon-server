// 보호장치 점검: 공개 API가 읽기 전용이고 입력 검증이 되는지, 영역 분리·시크릿 보호가 유지되는지 확인한다.
// 실행: node scripts/protect-check.mjs [기본주소]   (기본: 배포 주소)
//       node scripts/protect-check.mjs --local-seed   (로컬 D1에서 시드가 외부 표를 건드리지 않는지 추가 시험)
import { readFileSync, readdirSync, statSync } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { root, query } from "./db-lib.mjs";

const BASE = (process.argv.find((a) => a.startsWith("http")) ?? "https://dongnam-hackathon.magabus.workers.dev").replace(/\/$/, "");
const results = [];
const check = (name, ok, detail = "") => results.push({ name, ok, detail });
const get = (p, init) => fetch(BASE + p, { redirect: "manual", ...init });

// 1) 쓰기 방법은 모두 405 + Allow
for (const route of ["/api/courses", "/api/courses/HD-BEACHPROM/geojson", "/api/crosswalks?bbox=129.15,35.15,129.17,35.17", "/api/signals?bbox=129.15,35.15,129.17,35.17", "/api/weather?scope=haeundae"]) {
  for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
    const r = await get(route, { method, body: method === "DELETE" ? undefined : "{}" });
    if (route.includes("/signals") && r.status === 404) continue; // 아직 배포 전이면 건너뜀
    check(`${method} ${route.split("?")[0]} → 405`, r.status === 405 && /GET/.test(r.headers.get("allow") ?? ""), `status ${r.status}`);
  }
}

// 2) 입력 검증: 잘못된 값은 400/404이고 5xx가 없어야 한다
const bad = [
  ["/api/crosswalks", [400]], ["/api/crosswalks?bbox=a,b,c,d", [400]], ["/api/crosswalks?bbox=129.2,35.2,129.1,35.1", [400]],
  ["/api/crosswalks?bbox=124,33,132,38", [400]], ["/api/crosswalks?bbox=129.1,35.1,129.2,35.2", [400]],
  ["/api/crosswalks?bbox=129.1;DROP%20TABLE%20busan_crosswalk,35.1,129.12,35.12", [400]],
  ["/api/courses/HD-X'%20OR%201=1--/geojson", [400]], ["/api/courses/..%2f..%2fetc/geojson", [400, 404]],
  ["/api/courses/hd-beachprom/geojson", [400]], ["/api/courses/NOPE-XX/geojson", [404]],
  ["/api/slope", [404]], ["/api/weather?scope=..%2f..", [400]], ["/api/sqlite_master", [404]], ["/api/courses/HD-BEACHPROM/geojson/extra", [404]],
];
for (const [route, ok] of bad) {
  const r = await get(route);
  check(`GET ${route} → ${ok.join("/")}`, ok.includes(r.status), `status ${r.status}`);
}

// 3) 정상 응답: 형식·상한·캐시
const list = await (await get("/api/courses")).json();
check("코스 목록이 JSON이고 1개 이상", Array.isArray(list.courses) && list.courses.length > 0);
const big = await get("/api/crosswalks?bbox=129.14,35.15,129.189,35.199");
const bigJson = await big.json();
check("crosswalks 응답 상한(1500) 이하", big.status === 400 || (bigJson.features?.length ?? 0) <= 1500, `features ${bigJson.features?.length}`);
const okBox = await get("/api/crosswalks?bbox=129.155,35.155,129.175,35.168");
check("정상 bbox는 200·JSON·캐시 헤더", okBox.status === 200 && /json/.test(okBox.headers.get("content-type") ?? "") && /max-age=\d+/.test(okBox.headers.get("cache-control") ?? ""));
const err = await get("/api/crosswalks");
check("오류 응답은 no-store", /no-store/.test(err.headers.get("cache-control") ?? ""));
const health = await get("/api/health");
check("health 정상", health.status === 200);

// 4) 응답·소스에 시크릿·쓰기 쿼리가 없는지
const bodies = JSON.stringify(list) + JSON.stringify(await (await get("/api/courses/HD-BEACHPROM/geojson")).json());
check("응답에 키·토큰 문자열 없음", !/serviceKey|api[_-]?key|token|secret|password/i.test(bodies));
const srcDir = path.join(root, "src");
const srcText = readdirSync(srcDir).filter((f) => f.endsWith(".ts")).map((f) => readFileSync(path.join(srcDir, f), "utf8")).join("\n");
check("Worker 소스에 쓰기 SQL(INSERT/UPDATE/DELETE/DROP/ALTER) 없음", !/\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE)\b/.test(srcText.replace(/\/\/.*$/gm, "")));
check("Worker 소스가 경사 표를 직접 읽지 않음", !/haeundae_walking_grade10|v_slope_grade10/.test(srcText));

// 5) 저장소 위생: 추적 파일에 비밀 파일·패턴 없음
const git = (args) => spawnSync("git", args, { cwd: root, encoding: "utf8", env: { ...process.env, PATH: "C:\\Program Files\\Git\\cmd;" + process.env.PATH } }).stdout ?? "";
const tracked = git(["ls-files"]).split("\n").filter(Boolean);
check("추적 파일에 .dev.vars/.env/키 파일 없음", !tracked.some((f) => /(^|\/)(\.dev\.vars|\.env(\..*)?|.*\.pem|.*\.key)$/.test(f) && !/\.example$/.test(f)));
const secretRe = /serviceKey=[A-Za-z0-9%]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,}|BEGIN (RSA |EC )?PRIVATE KEY|DURUNUBI_SERVICE_KEY=[A-Za-z0-9%+\/]{16,}/;
const leaks = tracked.filter((f) => { try { const p = path.join(root, f); return statSync(p).size < 2_000_000 && secretRe.test(readFileSync(p, "utf8")); } catch { return false; } });
check("추적 파일에 시크릿 패턴 없음", leaks.length === 0, leaks.join(", "));
check("개인 이메일이 문서에 없음", !tracked.filter((f) => f.endsWith(".md")).some((f) => /@naver\.com|@gmail\.com/.test(readFileSync(path.join(root, f), "utf8"))));
const ignoredOk = ["db/generated/seed.sql", ".dev.vars", ".wrangler/x", "node_modules/x"].every((f) => git(["check-ignore", f]).trim() === f);
check(".dev.vars·생성 시드·node_modules가 Git 제외", ignoredOk);

// 6) D1 영역 분리(원격 읽기 전용 질의)
try {
  const unreg = query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' AND name NOT IN (SELECT table_name FROM data_domain)");
  check("D1 모든 표가 data_domain에 등록됨", unreg.length === 0, unreg.map((r) => r.name).join(", "));
  const ext = query("SELECT table_name FROM data_domain WHERE owner='external'");
  check("외부 소유 표는 이 저장소 시드 삭제 목록에 없음", ext.every((r) => !readFileSync(path.join(root, "scripts/build-haeundae-courses.mjs"), "utf8").includes("DELETE FROM " + r.table_name)));
} catch (e) { check("D1 질의", false, String(e.message).slice(0, 120)); }

let failed = 0;
for (const r of results) { if (!r.ok) failed++; console.log(`${r.ok ? "통과" : "실패"}  ${r.name}${r.ok || !r.detail ? "" : "  → " + r.detail}`); }
console.log(`\n${BASE} · ${results.length - failed}/${results.length} 통과`);
process.exit(failed ? 1 : 0);
