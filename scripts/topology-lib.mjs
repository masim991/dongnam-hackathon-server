// 산책로 선형의 위상 문제 탐지: 회전교차로·작은 고리, 끊긴 끝점, 노드 없는 교차(X)·T자, 겹침.
// OSM `out geom` 은 노드 ID가 없어 같은 좌표(소수 7자리)를 같은 노드로 본다. 좌표는 해운대 기준 평면(m)으로 변환해 계산한다.
const LAT0 = 35.16, KLA = 111320, KLO = 111320 * Math.cos((LAT0 * Math.PI) / 180);
export const xy = ([lon, lat]) => [(lon - 129.15) * KLO, (lat - LAT0) * KLA];
const key = ([lon, lat]) => lon.toFixed(6) + "," + lat.toFixed(6);
const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);

export function lengthM(line) { let s = 0; for (let i = 1; i < line.length; i++) s += dist(xy(line[i - 1]), xy(line[i])); return s; }

function segPointDist(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
  const t = l2 ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2)) : 0;
  return { d: Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy), t };
}

function segIntersect(a, b, c, d) {
  const r = [b[0] - a[0], b[1] - a[1]], s = [d[0] - c[0], d[1] - c[1]];
  const den = r[0] * s[1] - r[1] * s[0];
  if (Math.abs(den) < 1e-9) return null;
  const t = ((c[0] - a[0]) * s[1] - (c[1] - a[1]) * s[0]) / den, u = ((c[0] - a[0]) * r[1] - (c[1] - a[1]) * r[0]) / den;
  if (t <= 1e-6 || t >= 1 - 1e-6 || u <= 1e-6 || u >= 1 - 1e-6) return null; // 끝점에서 만나는 경우는 교차로 보지 않음
  return [a[0] + t * r[0], a[1] + t * r[1]];
}

const toLonLat = ([x, y]) => [x / KLO + 129.15, y / KLA + LAT0];

/**
 * ways: [{ id, name, tags, line: [[lon,lat],...] }]
 * 반환: { issues: [{ type, severity, ways: [ids], at: [lon,lat], detail, meters? }], stats }
 */
export function analyze(ways, { gapM = 30, nodeSnapM = 1.5, roundaboutMaxPerimM = 160, overlapMinM = 25 } = {}) {
  const issues = [];
  const P = ways.map((w) => ({ w, pts: w.line.map(xy) }));

  // 1) 닫힌 길: 회전교차로(태그 또는 작은 원형)와 큰 순환
  for (const { w, pts } of P) {
    const closed = key(w.line[0]) === key(w.line.at(-1));
    if (!closed) continue;
    const per = lengthM(w.line);
    let area = 0; for (let i = 0; i < pts.length - 1; i++) area += pts[i][0] * pts[i + 1][1] - pts[i + 1][0] * pts[i][1];
    const compact = (4 * Math.PI * Math.abs(area / 2)) / (per * per || 1);
    const tagged = w.tags?.junction === "roundabout" || w.tags?.junction === "circular";
    const c = toLonLat([pts.reduce((s, p) => s + p[0], 0) / pts.length, pts.reduce((s, p) => s + p[1], 0) / pts.length]);
    if (tagged || (per <= roundaboutMaxPerimM && compact > 0.6)) {
      issues.push({ type: "roundabout_loop", severity: "high", ways: [w.id], at: c, meters: Math.round(per), detail: tagged ? "회전교차로(OSM junction 태그): 한 바퀴를 도는 선으로 그려짐" : `작은 원형 고리(둘레 ${Math.round(per)}m, 원형도 ${compact.toFixed(2)}): 회전교차로·로터리 가능성` });
    } else if (per <= 600) {
      issues.push({ type: "small_loop", severity: "info", ways: [w.id], at: c, meters: Math.round(per), detail: `닫힌 고리(둘레 ${Math.round(per)}m)` });
    }
  }

  // 2) 노드(끝점) 차수: 같은 좌표를 공유하는 끝점/꼭짓점을 센다
  const nodeWays = new Map(); // key -> Set(wayIndex)
  P.forEach(({ w }, i) => w.line.forEach((p) => { const k = key(p); (nodeWays.get(k) ?? nodeWays.set(k, new Set()).get(k)).add(i); }));
  const endpoints = [];
  P.forEach(({ w, pts }, i) => {
    if (key(w.line[0]) === key(w.line.at(-1))) return;
    for (const [idx, p] of [[0, pts[0]], [pts.length - 1, pts.at(-1)]]) {
      const shared = nodeWays.get(key(w.line[idx])).size > 1;
      if (!shared) endpoints.push({ i, p, ll: w.line[idx] });
    }
  });

  // 3) 끊김/노드 없는 T자: 공유되지 않은 끝점이 다른 길 선분 가까이에 있으면
  for (const e of endpoints) {
    let best = { d: Infinity, j: -1, t: 0, seg: -1 };
    P.forEach(({ pts }, j) => {
      if (j === e.i) return;
      for (let s = 0; s < pts.length - 1; s++) {
        const r = segPointDist(e.p, pts[s], pts[s + 1]);
        if (r.d < best.d) best = { d: r.d, j, t: r.t, seg: s };
      }
    });
    if (best.d <= gapM && best.j >= 0) {
      const onVertex = best.t < 0.02 || best.t > 0.98;
      const kind = best.d <= nodeSnapM ? (onVertex ? null : "t_without_node") : "gap";
      if (kind) issues.push({ type: kind, severity: kind === "gap" ? "high" : "medium", ways: [P[e.i].w.id, P[best.j].w.id], at: e.ll, meters: Math.round(best.d * 10) / 10,
        detail: kind === "gap" ? `끊김: 끝점이 다른 길에서 ${best.d.toFixed(1)}m 떨어져 이어지지 않음` : "T자 접속인데 노드가 없어 이어지지 않은 것으로 처리됨" });
    }
  }

  // 4) 노드 없는 교차(X) — 서로 다른 길의 선분이 끝점이 아닌 곳에서 교차
  const seen = new Set();
  for (let i = 0; i < P.length; i++) for (let j = i + 1; j < P.length; j++) {
    const A = P[i].pts, B = P[j].pts;
    // 빠른 거절: 외곽 상자
    const bx = (pts) => [Math.min(...pts.map((p) => p[0])), Math.min(...pts.map((p) => p[1])), Math.max(...pts.map((p) => p[0])), Math.max(...pts.map((p) => p[1]))];
    const ba = P[i].box ??= bx(A), bb = P[j].box ??= bx(B);
    if (ba[2] < bb[0] || bb[2] < ba[0] || ba[3] < bb[1] || bb[3] < ba[1]) continue;
    for (let s = 0; s < A.length - 1; s++) for (let t = 0; t < B.length - 1; t++) {
      const hit = segIntersect(A[s], A[s + 1], B[t], B[t + 1]);
      if (!hit) continue;
      const near = (pts) => pts.some((p) => dist(p, hit) <= nodeSnapM);
      if (near(A) && near(B)) continue; // 같은 노드에서 만난 것
      const k = `${P[i].w.id}-${P[j].w.id}-${Math.round(hit[0] / 3)}-${Math.round(hit[1] / 3)}`;
      if (seen.has(k)) continue; seen.add(k);
      issues.push({ type: "crossing_without_node", severity: "medium", ways: [P[i].w.id, P[j].w.id], at: toLonLat(hit),
        detail: "두 길이 교차하지만 공유 노드가 없어 십자로가 이어지지 않은 것으로 처리됨(육교·터널이면 정상일 수 있음)" });
    }
  }

  // 5) 겹침: 한 길의 대부분이 다른 길 위(2m 이내)에 있음
  for (let i = 0; i < P.length; i++) {
    const A = P[i];
    const total = lengthM(A.w.line);
    if (total < overlapMinM) continue;
    for (let j = 0; j < P.length; j++) {
      if (i === j) continue;
      const B = P[j];
      let on = 0, n = 0;
      for (let s = 0; s < A.pts.length - 1; s++) {
        const len = dist(A.pts[s], A.pts[s + 1]), steps = Math.max(1, Math.ceil(len / 5));
        for (let k = 0; k <= steps; k++) {
          const q = [A.pts[s][0] + ((A.pts[s + 1][0] - A.pts[s][0]) * k) / steps, A.pts[s][1] + ((A.pts[s + 1][1] - A.pts[s][1]) * k) / steps];
          let m = Infinity; for (let u = 0; u < B.pts.length - 1; u++) m = Math.min(m, segPointDist(q, B.pts[u], B.pts[u + 1]).d);
          n++; if (m <= 2) on++;
        }
      }
      if (on / n >= 0.8 && i < j) issues.push({ type: "overlap", severity: "info", ways: [A.w.id, B.w.id], at: A.w.line[Math.floor(A.w.line.length / 2)], meters: Math.round(total), detail: `두 길이 거의 겹침(${Math.round((on / n) * 100)}%): 길이·횟수를 이중 집계할 수 있음` });
    }
  }

  const byType = {}; for (const x of issues) byType[x.type] = (byType[x.type] ?? 0) + 1;
  return { issues, stats: { ways: ways.length, endpointsUnshared: endpoints.length, byType } };
}

// ---------------------------------------------------------------------------------------------
// 코스 선형 정리: ① 회전교차로 고리는 지나가는 호(arc)만 남기고 ② 끊긴 곳은 짧은 연결선(gap_bridge)으로 잇고 ③ 나머지 문제는 표시만 한다.
// 입력: ways [{ id, name, tags, line }]  출력: { ways(정리됨), bridges, issues }
//   issues[].status: arc_replaced(고리→호), bridged(연결선 추가), flagged(자동 수정 못 함, 표시만)
export function cleanWays(ways, { gapM = 30, snapM = 1.5 } = {}) {
  const base = analyze(ways, { gapM, nodeSnapM: snapM });
  const out = ways.map((w) => ({ ...w, line: w.line.slice(), cleaned: null }));
  const issues = [];
  const bridges = [];

  // ① 회전교차로 고리 → 호
  for (const iss of base.issues.filter((x) => x.type === "roundabout_loop")) {
    const ring = out.find((w) => w.id === iss.ways[0]);
    const pts = ring.line.map(xy);
    const others = out.filter((w) => w !== ring);
    // 고리 꼭짓점 중 다른 길 꼭짓점과 맞닿은(2m 이내) 접속점
    const conn = [];
    for (let i = 0; i < pts.length - 1; i++) {
      if (others.some((o) => o.line.some((q) => dist(xy(q), pts[i]) <= 2))) conn.push(i);
    }
    if (conn.length === 2) {
      const [i, j] = conn;
      const arc1 = ring.line.slice(i, j + 1);
      const arc2 = ring.line.slice(j, ring.line.length - 1).concat(ring.line.slice(0, i + 1));
      const arc = lengthM(arc1) <= lengthM(arc2) ? arc1 : arc2;
      ring.line = arc; ring.cleaned = "roundabout_arc";
      issues.push({ ...iss, status: "arc_replaced", detail: `${iss.detail} → 코스가 지나가는 호(${Math.round(lengthM(arc))}m)만 남김(고리 ${iss.meters}m)` });
    } else {
      issues.push({ ...iss, status: "flagged", detail: `${iss.detail} — 접속점 ${conn.length}개라 자동으로 호를 고를 수 없어 고리 그대로 표시` });
    }
  }

  // ② 끊김 → 연결선
  for (const iss of base.issues.filter((x) => x.type === "gap")) {
    const [aId, bId] = iss.ways;
    const a = out.find((w) => w.id === aId), b = out.find((w) => w.id === bId);
    // 끊긴 끝점(iss.at)에서 상대 길의 가장 가까운 점까지 선을 긋는다
    const endLL = [a.line[0], a.line.at(-1)].reduce((best, p) => (dist(xy(p), xy(iss.at)) < dist(xy(best), xy(iss.at)) ? p : best));
    const P = b.line.map(xy), e = xy(endLL);
    let best = { d: Infinity, pt: null };
    for (let s = 0; s < P.length - 1; s++) {
      const dx = P[s + 1][0] - P[s][0], dy = P[s + 1][1] - P[s][1], l2 = dx * dx + dy * dy;
      const t = l2 ? Math.max(0, Math.min(1, ((e[0] - P[s][0]) * dx + (e[1] - P[s][1]) * dy) / l2)) : 0;
      const q = [P[s][0] + t * dx, P[s][1] + t * dy], d = dist(e, q);
      if (d < best.d) best = { d, pt: q };
    }
    // A trimmed roundabout can move the nearest point beyond the original gap limit.
    if (best.d > gapM) {
      issues.push({ ...iss, status: "flagged", meters: Math.round(best.d * 10) / 10,
        detail: `선형 정리 후 간격 ${best.d.toFixed(1)}m가 연결 상한 ${gapM}m를 넘어 자동으로 잇지 않음` });
      continue;
    }
    const to = toLonLat(best.pt);
    // 같은 두 길 사이 틈을 양쪽에서 두 번 보고한 것을 합친다(같은 길 쌍이고 25m 이내면 같은 틈)
    const pair = [aId, bId].sort().join("-");
    if (bridges.some((b) => [b.ways[0], b.ways[1]].sort().join("-") === pair && dist(xy(b.from), xy(endLL)) <= 25)) continue;
    bridges.push({ from: endLL, to, meters: Math.round(best.d * 10) / 10, ways: [aId, bId] });
    issues.push({ ...iss, status: "bridged", meters: Math.round(best.d * 10) / 10, detail: `${iss.detail} → ${Math.round(best.d)}m 직선 연결선으로 이음(실제 보행 가능 여부는 현장 확인 필요)` });
  }

  // ③ 자동 수정하지 않는 문제는 표시만
  for (const iss of base.issues.filter((x) => ["crossing_without_node", "t_without_node", "overlap"].includes(x.type))) issues.push({ ...iss, status: "flagged" });
  return { ways: out, bridges, issues };
}
