// shortcut: 원래 OSM way ID가 없어 선형 정합은 추정이다. 원본 ID 확보 시 같은 길 확인을 추가한다.
const X = 111320 * Math.cos(35.17 * Math.PI / 180), Y = 111320, CELL = 100;
const xy = (p) => [p[0] * X, p[1] * Y];
const cells = (a, b, pad = 3) => {
  const keys = [];
  for (let x = Math.floor((Math.min(a[0], b[0]) - pad) / CELL); x <= Math.floor((Math.max(a[0], b[0]) + pad) / CELL); x++)
    for (let y = Math.floor((Math.min(a[1], b[1]) - pad) / CELL); y <= Math.floor((Math.max(a[1], b[1]) + pad) / CELL); y++) keys.push(x + ':' + y);
  return keys;
};

export function indexSlopes(slopes) {
  const grid = new Map();
  for (const s of slopes) {
    if (![...s.a, ...s.b, s.grade].every(Number.isFinite) || Math.abs(s.grade) < 10) continue;
    const item = { ...s, start: xy(s.a), end: xy(s.b) };
    for (const key of cells(item.start, item.end)) {
      if (!grid.has(key)) grid.set(key, []);
      grid.get(key).push(item);
    }
  }
  return grid;
}

// 3m 이내·방향 차이 20도 이하·겹침 2m 이상만 실제 산책로 중심선에 잘라 표시한다.
export function matchSlopes(features, grid) {
  const output = [];
  for (const f of features) {
    if (f.geometry.type !== 'LineString' || f.properties.highway === 'gap_bridge') continue;
    const line = f.geometry.coordinates;
    for (let i = 1; i < line.length; i++) {
      const a = xy(line[i - 1]), b = xy(line[i]), dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy);
      if (len < 2) continue;
      const candidates = new Set(cells(a, b).flatMap((key) => grid.get(key) || [])), intervals = [];
      for (const s of candidates) {
        const sx = s.end[0] - s.start[0], sy = s.end[1] - s.start[1], slen = Math.hypot(sx, sy);
        if (!slen || Math.abs((dx * sx + dy * sy) / (len * slen)) < Math.cos(20 * Math.PI / 180)) continue;
        const projection = (p) => ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len;
        const lateral = (p) => Math.abs((p[0] - a[0]) * dy - (p[1] - a[1]) * dx) / len;
        if (Math.max(lateral(s.start), lateral(s.end)) > 3) continue;
        const p = projection(s.start), q = projection(s.end), lo = Math.max(0, Math.min(p, q)), hi = Math.min(len, Math.max(p, q));
        if (hi - lo >= 2) intervals.push({ lo, hi, s });
      }
      // 겹친 추정값은 최대 절댓값 하나로 정리해 길이·선이 중복되지 않게 한다.
      const cuts = [...new Set(intervals.flatMap((v) => [v.lo, v.hi]))].sort((p, q) => p - q);
      for (let j = 1; j < cuts.length; j++) {
        const lo = cuts[j - 1], hi = cuts[j], mid = (lo + hi) / 2;
        const hit = intervals.filter((v) => v.lo <= mid && v.hi >= mid).sort((p, q) => Math.abs(q.s.grade) - Math.abs(p.s.grade))[0];
        if (!hit) continue;
        const at = (t) => [(a[0] + dx * t / len) / X, (a[1] + dy * t / len) / Y];
        output.push({ type: 'Feature', properties: { grade: Math.abs(hit.s.grade), len: hi - lo, id: hit.s.id }, geometry: { type: 'LineString', coordinates: [at(lo), at(hi)] } });
      }
    }
  }
  return { features: output };
}
