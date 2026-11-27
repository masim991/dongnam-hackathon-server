// 배포된 API의 실제 응답을 줄여서 docs/samples/ 에 저장한다(다른 AI·앱 개발자가 형식을 확인하는 용도).
// 실행: node scripts/export-api-samples.mjs [기본주소]
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const BASE = (process.argv[2] ?? "https://dongnam-hackathon.magabus.workers.dev").replace(/\/$/, "");
const out = path.join(root, "docs/samples");
mkdirSync(out, { recursive: true });
const get = async (p) => { const r = await fetch(BASE + p); if (!r.ok) throw new Error(p + " " + r.status); return r.json(); };
const save = (name, obj) => { writeFileSync(path.join(out, name), JSON.stringify(obj, null, 2) + "\n"); console.log("저장:", name); };
const take = (arr, n) => (Array.isArray(arr) ? arr.slice(0, n) : arr);

save("health.json", await get("/api/health"));
const courses = await get("/api/courses");
save("courses.json", { courses: courses.courses.slice(0, 3), _note: `전체 ${courses.courses.length}개 중 3개만 표시` });
const g = await get("/api/courses/HD-BEACHPROM/geojson");
save("course-geojson.HD-BEACHPROM.json", { ...g, features: take(g.features, 6), _note: `전체 ${g.features.length}개 요소 중 6개만 표시` });
const x = await get("/api/crosswalks?bbox=129.155,35.155,129.165,35.162");
save("crosswalks.bbox.json", { ...x, features: take(x.features, 5), _note: `해당 영역 ${x.features.length}개 중 5개만 표시` });
const s = await get("/api/signals?bbox=129.155,35.155,129.175,35.168");
save("signals.bbox.json", { ...s, features: take(s.features, 5), _note: `해당 영역 ${s.features.length}개 중 5개만 표시` });
const w = await get("/api/weather?scope=haeundae");
save("weather.haeundae.json", { ...w, areas: take(w.areas, 2), _note: `전체 ${w.areas.length}개 구역 중 2개만 표시` });
