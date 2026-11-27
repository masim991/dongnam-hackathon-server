// 경로 비교용 작은 기하 함수. 해운대 근처(위도 35.16) 평면 근사를 쓴다.
const LAT0 = 35.16, KLA = 111320, KLO = 111320 * Math.cos((LAT0 * Math.PI) / 180);
export const toXY = ([lon, lat]) => [(lon - 129.15) * KLO, (lat - LAT0) * KLA];

export function segDist(px, py, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
  const t = l2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
  return Math.hypot(px - ax - t * dx, py - ay - t * dy);
}

export const lineLengthM = (line) => line.slice(1).reduce((s, p, i) => {
  const a = toXY(line[i]), b = toXY(p);
  return s + Math.hypot(b[0] - a[0], b[1] - a[1]);
}, 0);

// 한 점에서 선(여러 선분)까지 최단 거리(m)
function distToLine(p, lineXY) {
  let m = Infinity;
  for (let i = 0; i < lineXY.length - 1; i++) m = Math.min(m, segDist(p[0], p[1], lineXY[i][0], lineXY[i][1], lineXY[i + 1][0], lineXY[i + 1][1]));
  return m;
}

// 우리 코스 위를 약 5m 간격으로 샘플링해, 기준 선에서 tolM 안에 있는 비율과 최대 이탈 거리를 구한다.
export function compareLines(ours, ref, tolM = 30) {
  const refXY = ref.map(toXY);
  const samples = [];
  for (let i = 0; i < ours.length - 1; i++) {
    const a = toXY(ours[i]), b = toXY(ours[i + 1]);
    const n = Math.max(1, Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) / 5));
    for (let k = 0; k <= n; k++) samples.push([a[0] + ((b[0] - a[0]) * k) / n, a[1] + ((b[1] - a[1]) * k) / n]);
  }
  const d = samples.map((s) => distToLine(s, refXY));
  const within = d.filter((v) => v <= tolM).length;
  return { shareWithinTol: within / d.length, maxDeviationM: Math.max(...d), samples: d.length };
}

// GPX 문서에서 trkpt(없으면 rtept) 좌표를 [lon,lat] 배열로 추출. 외부 텍스트이므로 숫자만 취한다.
export function parseGpx(text) {
  const grab = (tag) => [...text.matchAll(new RegExp(`<${tag}\\b([^>]*)>`, "g"))].map((m) => {
    const lat = /\blat="(-?\d+(?:\.\d+)?)"/.exec(m[1]), lon = /\blon="(-?\d+(?:\.\d+)?)"/.exec(m[1]);
    return lat && lon ? [parseFloat(lon[1]), parseFloat(lat[1])] : null;
  }).filter(Boolean);
  const pts = grab("trkpt");
  return pts.length ? pts : grab("rtept");
}
