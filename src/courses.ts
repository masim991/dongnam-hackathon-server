// D1(dongnam-running) 읽기 전용 API. 쓰기·집계 갱신은 scripts/ 와 wrangler d1 execute 로만 한다.
export interface D1Like {
  prepare(sql: string): {
    bind(...values: unknown[]): { all<T = Record<string, unknown>>(): Promise<{ results: T[] }> };
    all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
  };
}
export interface Env {
  DB?: D1Like;
}

const CODE_RE = /^[A-Z0-9-]{3,40}$/;
const MAX_POINTS = 1500;

const json = (data: unknown, status = 200, extra: Record<string, string> = {}) =>
  Response.json(data, { status, headers: { "Cache-Control": "public, max-age=300", ...extra } });

type Row = Record<string, unknown>;

export async function handleCourseApi(url: URL, env: Env): Promise<Response | null> {
  const { pathname } = url;
  const isCourses = pathname === "/api/courses";
  const geo = pathname.match(/^\/api\/courses\/([^/]+)\/geojson$/);
  const isCrosswalks = pathname === "/api/crosswalks";
  if (!isCourses && !geo && !isCrosswalks) return null;
  if (!env.DB) return json({ error: "Database not configured" }, 503, { "Cache-Control": "no-store" });
  const db = env.DB;

  if (isCourses) {
    const courses = (await db.prepare(
      "SELECT code,name,region,gu,kind,length_m,way_count,buffer_m,matched_points,unique_crossings,crossings_per_km,note,built_at FROM course ORDER BY code",
    ).all<Row>()).results;
    const summary = (await db.prepare("SELECT course_code,source,kind,count FROM course_crossing_summary").all<Row>()).results;
    return json({
      courses: courses.map((c) => ({
        ...c,
        crossings: summary.filter((s) => s.course_code === c.code).map(({ source, kind, count }) => ({ source, kind, count })),
      })),
    });
  }

  if (geo) {
    const code = geo[1];
    if (!CODE_RE.test(code)) return json({ error: "Bad course code" }, 400, { "Cache-Control": "no-store" });
    const [ways, signals, osm, entrances] = await Promise.all([
      db.prepare("SELECT osm_way_id,name,highway,length_m,geometry_geojson FROM course_way WHERE course_code=?").bind(code).all<Row>(),
      db.prepare(
        "SELECT c.id,c.intersection_name,c.dong,c.lon,c.lat,cc.distance_m FROM course_crosswalk cc JOIN busan_crosswalk c ON c.id=cc.crosswalk_id WHERE cc.course_code=?",
      ).bind(code).all<Row>(),
      db.prepare(
        "SELECT o.osm_id,o.kind,o.name,o.lon,o.lat,co.distance_m FROM course_osm_crossing co JOIN osm_crossing o ON o.osm_id=co.osm_id WHERE co.course_code=?",
      ).bind(code).all<Row>(),
      db.prepare("SELECT seq,lon,lat,nearest_signal_m,nearest_osm_m,nearest_osm_kind FROM course_entrance WHERE course_code=? ORDER BY seq").bind(code).all<Row>(),
    ]);
    if (ways.results.length === 0) return json({ error: "Course not found" }, 404, { "Cache-Control": "no-store" });
    const point = (r: Row, props: Row) => ({ type: "Feature", properties: props, geometry: { type: "Point", coordinates: [r.lon, r.lat] } });
    return json({
      type: "FeatureCollection",
      features: [
        ...ways.results.map((w) => ({
          type: "Feature",
          properties: { layer: "course", code, wayId: w.osm_way_id, name: w.name, highway: w.highway, lengthM: w.length_m },
          geometry: JSON.parse(String(w.geometry_geojson)),
        })),
        ...signals.results.map((r) => point(r, { layer: "signal", id: r.id, label: r.intersection_name, dong: r.dong, distanceM: r.distance_m })),
        ...osm.results.map((r) => point(r, { layer: "osm", kind: r.kind, label: r.name, distanceM: r.distance_m })),
        ...entrances.results.map((r) => point(r, { layer: "entrance", seq: r.seq, nearestSignalM: r.nearest_signal_m, nearestOsmM: r.nearest_osm_m, nearestOsmKind: r.nearest_osm_kind })),
      ],
    });
  }

  // /api/crosswalks?bbox=서,남,동,북 — 신호등 있는 횡단보도(공공 파일). 축소 화면에서의 대량 조회를 막기 위해 상한을 둔다.
  const bbox = (url.searchParams.get("bbox") ?? "").split(",").map(Number);
  const [w, s, e, n] = bbox;
  if (bbox.length !== 4 || bbox.some((v) => !Number.isFinite(v)) || w >= e || s >= n || e - w > 0.05 || n - s > 0.05
    || w < 124 || e > 132.2 || s < 32.8 || n > 38.8) {
    return json({ error: "bbox must be west,south,east,north within Korea and at most 0.05 degrees wide/high" }, 400, { "Cache-Control": "no-store" });
  }
  const rows = (await db.prepare(
    "SELECT id,intersection_name,dong,lon,lat FROM busan_crosswalk WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT ?",
  ).bind(s, n, w, e, MAX_POINTS).all<Row>()).results;
  return json({
    type: "FeatureCollection",
    features: rows.map((r) => ({
      type: "Feature",
      properties: { id: r.id, label: r.intersection_name, dong: r.dong },
      geometry: { type: "Point", coordinates: [r.lon, r.lat] },
    })),
  });
}
