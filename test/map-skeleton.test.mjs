import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { indexSlopes, matchSlopes } from "../public/map/slope-match.js";
import { CONFIG, THEME, applyTheme } from "../public/map/theme.js";

// Execute the real page module with deterministic DOM/map/network adapters, without WebGL or health records.
const source = readFileSync(new URL("../public/map/app.js", import.meta.url), "utf8")
  .replace(/^import .* from '\.\/theme\.js';\r?\n/m, "")
  .replace(/^import .* from '\.\/slope-match\.js';\r?\n/m, "");
const html = readFileSync(new URL("../public/map/index.html", import.meta.url), "utf8");
const flush = async () => { for (let i = 0; i < 5; i++) await new Promise(setImmediate); };

function element() {
  let text = "";
  return {
    children: [], handlers: {}, attributes: {}, disabled: false, hidden: true, checked: false, value: "", files: [],
    set textContent(value) { text = value; this.children = []; },
    get textContent() { return text; },
    appendChild(child) { this.children.push(child); },
    setAttribute(name, value) { this.attributes[name] = value; },
    addEventListener(name, listener) { this.handlers[name] = listener; },
  };
}

function boot({ bridge, search = "", styleStatus = 200, courses = [], get } = {}) {
  const elements = Object.fromEntries([...html.matchAll(/\bid="([^"]+)"/g)].map(([, id]) => [id, element()]));
  const requests = [];
  const timers = new Set();
  const document = {
    documentElement: { style: { setProperty() {} } },
    getElementById(id) { assert.ok(elements[id], "Unknown DOM id: " + id); return elements[id]; },
    createElement: element,
  };
  class MapStub {
    sources = new Map(); handlers = {};
    on(name, ...args) { this.handlers[name] = args.at(-1); }
    addSource(id, spec) { this.sources.set(id, { data: spec.data, setData(data) { this.data = data; } }); }
    addLayer() {}
    getStyle() { return { layers: [] }; }
    getSource(id) { return this.sources.get(id); }
    fitBounds() {}
    setLayoutProperty() {}
  }
  class BoundsStub { extend() {} isEmpty() { return false; } }
  const window = { innerWidth: 1024, AndroidHealth: bridge };
  const context = vm.createContext({
    window, document, location: { search }, URLSearchParams, CONFIG, THEME, indexSlopes, matchSlopes,
    applyTheme: () => applyTheme(document.documentElement),
    maplibregl: { Map: MapStub, LngLatBounds: BoundsStub },
    get map() { return window.map; },
    fetch: async (url) => {
      requests.push(String(url));
      if (get) { const result = await get(String(url)); if (result) return result; }
      if (url === CONFIG.STYLE_URL) return Response.json({ layers: [] }, { status: styleStatus });
      if (url === "/api/courses") return Response.json({ courses });
      if (url === "/api/weather?scope=haeundae") return Response.json({ summary: { "해운대": { verdict: "good", label: "좋음", score: 100, coverage: "1/1" } }, areas: [] });
      if (url === "/data/haeundae/trail-context.json") return Response.json({ slope: [], signals: [] });
      return Response.json({ error: "unexpected fixture request" }, { status: 404 });
    },
    setTimeout: (fn, delay) => { const timer = setTimeout(() => { timers.delete(timer); fn(); }, delay); timers.add(timer); return timer; },
    clearTimeout: (timer) => { timers.delete(timer); clearTimeout(timer); },
  });
  vm.runInContext(source, context, { filename: "public/map/app.js" });
  return {
    elements, window, requests, context,
    loadMap: () => window.map.handlers.load(),
    close: () => { for (const timer of timers) clearTimeout(timer); },
  };
}

test("map style HTTP failure is reported and does not block the health adapter", async (t) => {
  const app = boot({ styleStatus: 503, bridge: { status: () => "unavailable", requestPermissions() {}, readRecent() {} } });
  t.after(app.close);
  await flush();
  assert.match(app.elements.info.textContent, /지도를 불러오지 못/);
  assert.equal(app.elements.health.hidden, false);
  assert.equal(app.elements.healthRead.disabled, true);
});

test("malformed native status and callback messages cannot break map initialization", async (t) => {
  const app = boot({ bridge: { status: () => { throw new Error("native unavailable"); }, requestPermissions() {}, readRecent() {} } });
  t.after(app.close);
  await flush();
  assert.doesNotThrow(app.loadMap);
  assert.equal(app.elements.healthRead.disabled, true);
  assert.doesNotThrow(() => app.window.onAndroidHealth(null));
  assert.doesNotThrow(() => app.window.onAndroidHealth({ type: "summary", days: 7, steps: "bad", distanceMeters: null, runningSessions: [] }));
  assert.doesNotMatch(app.elements.healthInfo.textContent, /NaN/);
});

test("mock mode cannot replace an existing native health adapter", async (t) => {
  const bridge = { status: () => "available", requestPermissions() {}, readRecent() {} };
  const app = boot({ bridge, search: "?mockHealth=1" });
  t.after(app.close);
  await flush();
  app.loadMap();
  assert.equal(app.window.AndroidHealth, bridge);
});

test("health summaries preserve missing values and reject invalid periods", async (t) => {
  const app = boot({ bridge: { status: () => "available", requestPermissions() {}, readRecent() {} } });
  t.after(app.close);
  await flush();
  app.loadMap();
  app.window.onAndroidHealth({ type: "summary", days: 7, steps: null, distanceMeters: null, runningSessions: [] });
  assert.match(app.elements.healthInfo.textContent, /공유된 기록 없음/);
  app.window.onAndroidHealth({ type: "summary", days: 99, steps: 1, distanceMeters: 2, runningSessions: [] });
  assert.doesNotMatch(app.elements.healthInfo.textContent, /최근 99일/);
});

test("a late course response cannot overwrite the most recently selected course", async (t) => {
  let releaseA;
  const courses = ["A", "B"].map((code) => ({ code, name: code, kind: "trail", length_m: 100, crossings: [], unique_crossings: 0, buffer_m: 20 }));
  const featureCollection = (code) => ({ type: "FeatureCollection", features: [{ type: "Feature", properties: { layer: "course", code }, geometry: { type: "LineString", coordinates: [[129.16, 35.16], [129.17, 35.17]] } }] });
  const app = boot({ courses, get: (url) => {
    if (url === "/api/courses/A/geojson") return new Promise((resolve) => { releaseA = () => resolve(Response.json(featureCollection("A"))); });
    if (url === "/api/courses/B/geojson") return Response.json(featureCollection("B"));
  } });
  t.after(app.close);
  await flush(); app.loadMap();
  app.elements.list.children[0].handlers.click(); await flush();
  app.elements.list.children[1].handlers.click(); await flush();
  releaseA(); await flush();
  assert.equal(app.elements.list.children[1].attributes["aria-pressed"], "true");
  assert.equal(app.window.map.getSource("course").data.features[0].properties.code, "B");
});

test("map failure settles a pending GPX selection and weather still loads", async (t) => {
  const app = boot({ styleStatus: 503 }); t.after(app.close);
  app.elements.gpxFile.files = [{ size: 1, text() { throw new Error("must not read without a map"); } }];
  const picked = app.elements.gpxFile.handlers.change();
  await flush();
  await Promise.race([picked, new Promise((_, reject) => {
    const timer = setTimeout(() => reject(new Error("GPX selection remained pending")), 200);
    t.after(() => clearTimeout(timer));
  })]);
  assert.match(app.elements.gpxInfo.textContent, /지도를 불러오지 못/);
  assert.match(app.elements.wxSummary.textContent, /해운대 종합/);
});

test("native action failures are shown and valid summaries keep zero distinct from missing", async (t) => {
  const app = boot({ bridge: { status: () => "available", requestPermissions() { throw new Error("denied"); }, readRecent() { throw new Error("failed"); } } });
  t.after(app.close); await flush(); app.loadMap();
  assert.doesNotThrow(() => app.elements.healthConnect.handlers.click());
  assert.match(app.elements.healthInfo.textContent, /오류/);
  assert.doesNotThrow(() => app.elements.healthRead.handlers.click());
  assert.match(app.elements.healthInfo.textContent, /오류/);
  app.window.onAndroidHealth({ type: "summary", days: 7, steps: 0, distanceMeters: 0, runningSessions: [] });
  assert.match(app.elements.healthInfo.textContent, /걸음 수 0보/);
  assert.match(app.elements.healthInfo.textContent, /이동거리 0km/);
  assert.doesNotMatch(app.elements.healthInfo.textContent, /공유된 기록 없음/);
});

const origin = [129.17, 35.17];
const point = (x, y = 0) => [origin[0] + x / (111320 * Math.cos(origin[1] * Math.PI / 180)), origin[1] + y / 111320];
const path = (a = 0, b = 100, properties = {}) => ({ type: "Feature", properties, geometry: { type: "LineString", coordinates: [point(a), point(b)] } });
const incline = (id, a, b, grade = 12) => ({ id, a, b, grade });

test("slope overlay clips to the actual trail and does not color perpendicular or parallel neighboring roads", () => {
  const grid = indexSlopes([
    incline(1, point(20, 1), point(40, 1)),
    incline(2, point(50, -10), point(50, 10), 45),
    incline(3, point(60, 10), point(80, 10), 30),
    incline(4, point(110), point(130), 20),
  ]);
  const result = matchSlopes([path()], grid);
  assert.equal(result.features.length, 1);
  const f = result.features[0];
  assert.equal(f.properties.id, 1);
  assert.ok(Math.abs(f.properties.len - 20) < 0.01);
  assert.ok(f.geometry.coordinates.every((p) => Math.abs(p[1] - origin[1]) < 1e-10));
});

test("overlapping slopes do not double-count length; reversed coordinates work and synthetic gap bridges stay uncolored", () => {
  const grid = indexSlopes([incline(1, point(10), point(50)), incline(2, point(60), point(30), -25)]);
  const result = matchSlopes([path()], grid);
  assert.ok(Math.abs(result.features.reduce((n, f) => n + f.properties.len, 0) - 50) < 0.01);
  assert.equal(Math.max(...result.features.map((f) => f.properties.grade)), 25);
  assert.equal(matchSlopes([path(0, 100, { highway: "gap_bridge" })], grid).features.length, 0);
  assert.equal(matchSlopes([path()], indexSlopes([])).features.length, 0);
});

test("turning trails off rejects an outstanding tile request", async (t) => {
  let release;
  const app = boot({ get: (url) => url.startsWith("/data/trails/") ? new Promise((resolve) => { release = () => resolve(Response.json({ trails: [{ i: 1, g: [point(0), point(20)] }] })); }) : undefined });
  t.after(app.close); await flush(); app.loadMap();
  app.window.map.getZoom = () => 14;
  app.window.map.getBounds = () => ({ getSouth: () => 35.17, getNorth: () => 35.18, getWest: () => 129.17, getEast: () => 129.18 });
  app.elements.lyTrails.checked = true; app.elements.lyTrails.handlers.change(); await flush();
  app.elements.lyTrails.checked = false; app.elements.lyTrails.handlers.change(); release(); await flush();
  assert.equal(app.window.map.getSource("trails").data.features.length, 0);
});

test("a slope option selected before map load is applied when the map becomes ready", async (t) => {
  const app = boot(); t.after(app.close); await flush();
  app.elements.lySlope.checked = true;
  app.loadMap(); await flush();
  assert.match(app.elements.slopeStatus.textContent, /추정값/);
  assert.ok(app.requests.includes("/data/haeundae/trail-context.json"));
});
