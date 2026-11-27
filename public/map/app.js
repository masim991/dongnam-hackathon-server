// 지도 화면 로직. 색·지도 설정은 theme.js, 모양은 styles.css, 마크업은 index.html. 설명서: docs/frontend-design-guide.md
import { CONFIG, THEME, applyTheme } from './theme.js';
import { indexSlopes, matchSlopes } from './slope-match.js';
applyTheme();
const KIND_LABEL = { official: '부산시 공식 트랙', trail: '산책로', riverside: '강변길', road_proxy: '차도 기준 근사' };
const OSM_KIND_LABEL = { signal: '신호', signal_junction: '교차로 신호기', uncontrolled: '무신호', marked: '표시 횡단', unmarked: '표시 없음', unknown: '미분류' };

// 이름 라벨을 한글(name:ko, 없으면 현지 name)만 쓰도록 바꾼다. 번호판 라벨(ref)은 그대로 둔다.
function koreanOnly(style) {
  const ko = ['coalesce', ['get', 'name:ko'], ['get', 'name']];
  for (const layer of style.layers) {
    const tf = layer.layout && layer.layout['text-field'];
    if (tf && JSON.stringify(tf).includes('name')) layer.layout['text-field'] = ko;
  }
  return style;
}

const slopeColor = ['step', ['get', 'grade'], THEME.slopeLow, 15, THEME.slopeModerate, 20, THEME.slopeMid, 40, THEME.slopeHigh];
const EMPTY = { type: 'FeatureCollection', features: [] };
let resolveMapReady;
const mapReady = new Promise((r) => { resolveMapReady = r; }); // 지도 준비 전에 파일을 골라도 준비되면 이어서 처리한다
const $ = (id) => document.getElementById(id);

function mapFailed() {
  $('info').textContent = '지도를 불러오지 못했습니다. 연결을 확인하고 새로고침하세요.';
  resolveMapReady(false);
}
getJson(CONFIG.STYLE_URL).then(style => {
  window.map = new maplibregl.Map({
    container: 'map',
    style: koreanOnly(style),
    center: CONFIG.START.center,
    zoom: CONFIG.START.zoom,
    maxBounds: CONFIG.BOUNDS,
  });
  window.map.on('load', () => {
    try { initCourses(); resolveMapReady(true); } catch { mapFailed(); }
  });
}).catch(mapFailed);

async function getJson(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(url + ' ' + r.status);
  return r.json();
}

function initCourses() {
  const map = window.map;
  map.addSource('xwalk', { type: 'geojson', data: EMPTY });
  map.addSource('signals', { type: 'geojson', data: EMPTY });
  map.addSource('trails', { type: 'geojson', data: EMPTY });
  map.addSource('hdNetwork', { type: 'geojson', data: EMPTY });
  map.addSource('slope', { type: 'geojson', data: EMPTY });
  map.addSource('nearbySlope', { type: 'geojson', data: EMPTY });
  const labelLayer = map.getStyle().layers.find((l) => l.type === 'symbol')?.id;
  map.addLayer({ id: 'hd-network', type: 'line', source: 'hdNetwork', minzoom: 12, layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': ['case', ['==', ['get', 'walk'], 'yes'], THEME.trail, THEME.nearby],
      'line-width': ['interpolate', ['linear'], ['zoom'], 12, 0.7, 16, 2], 'line-opacity': ['case', ['==', ['get', 'walk'], 'yes'], 0.65, 0.28] } }, labelLayer);
  map.addLayer({ id: 'trails', type: 'line', source: 'trails', minzoom: 12, layout: { 'line-cap': 'round' },
    paint: { 'line-color': THEME.trail, 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 1, 16, 2.5], 'line-opacity': 0.65 } }, labelLayer);
  map.addLayer({ id: 'slope', type: 'line', source: 'slope', minzoom: 13, layout: { visibility: 'none' },
    paint: { 'line-color': slopeColor, 'line-width': ['interpolate', ['linear'], ['zoom'], 13, 2, 17, 3.5] } }, labelLayer);
  map.addLayer({ id: 'nearby-slope', type: 'line', source: 'nearbySlope', minzoom: 15, layout: { visibility: 'none' },
    paint: { 'line-color': THEME.nearby, 'line-width': 1.5, 'line-opacity': 0.4, 'line-dasharray': [2, 2] } }, 'trails');
  map.addLayer({ id: 'signals', type: 'circle', source: 'signals', minzoom: 14,
    paint: { 'circle-radius': 5, 'circle-color': THEME.controller, 'circle-opacity': 0.8, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5 } });
  map.addSource('course', { type: 'geojson', data: EMPTY });
  map.addLayer({ id: 'xwalk', type: 'circle', source: 'xwalk', minzoom: 15,
    paint: { 'circle-radius': 4, 'circle-color': THEME.crosswalk, 'circle-opacity': 0.55, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1 } });
  map.addLayer({ id: 'course-line', type: 'line', source: 'course', filter: ['all', ['==', ['get', 'layer'], 'course'], ['!=', ['get', 'highway'], 'gap_bridge'], ['!=', ['get', 'highway'], 'closed_reference'], ['!=', ['get', 'highway'], 'detour_candidate'], ['!=', ['get', 'roadAccessUnverified'], true]],
    layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': THEME.course, 'line-width': 4 } }, labelLayer);
  map.addLayer({ id: 'course-unverified', type: 'line', source: 'course', filter: ['all', ['==', ['get', 'layer'], 'course'], ['==', ['get', 'highway'], 'network_connector'], ['==', ['get', 'roadAccessUnverified'], true]],
    paint: { 'line-color': THEME.bridge, 'line-width': 3.5, 'line-dasharray': [2, 1.5] } }, labelLayer);
  map.on('click', 'course-unverified', (e) => {
    new maplibregl.Popup().setLngLat(e.lngLat).setText('OSM 원본 도로를 따라 복원한 연결 · 보행 가능 여부는 현장 미확인입니다.').addTo(map);
  });
  map.addLayer({ id: 'course-closed', type: 'line', source: 'course', filter: ['==', ['get', 'highway'], 'closed_reference'],
    paint: { 'line-color': THEME.nearby, 'line-width': 3, 'line-dasharray': [2, 2], 'line-opacity': 0.7 } }, labelLayer);
  map.addLayer({ id: 'course-detour', type: 'line', source: 'course', filter: ['==', ['get', 'highway'], 'detour_candidate'],
    paint: { 'line-color': THEME.bridge, 'line-width': 3.5, 'line-dasharray': [2, 1.5] } }, labelLayer);
  for (const id of ['course-closed', 'course-detour']) map.on('click', id, (e) => {
    new maplibregl.Popup().setLngLat(e.lngLat).setText(id === 'course-closed' ? '2024년 원본 트랙 · 요트경기장 공사 통제 대상, 현재 통행 안내에 사용하지 마세요.' : '공식 우회 안내도 기반 외곽 우회 후보 · 4.3m 접속은 OSM 공유 노드가 없는 추정입니다. 진입 서비스도로 및 현장 통행 확인이 필요합니다.').addTo(map);
  });
  // 끊긴 곳을 잇기 위해 추가한 연결선(실제 보행 가능 여부는 현장 확인 필요)
  map.addLayer({ id: 'course-bridge', type: 'line', source: 'course', filter: ['all', ['==', ['get', 'layer'], 'course'], ['==', ['get', 'highway'], 'gap_bridge']],
    paint: { 'line-color': THEME.bridge, 'line-width': 3, 'line-dasharray': [1.5, 1.5] } });
  map.addLayer({ id: 'course-issue', type: 'circle', source: 'course', filter: ['==', ['get', 'layer'], 'issue'],
    paint: { 'circle-radius': 8, 'circle-color': THEME.issueFill, 'circle-stroke-color': THEME.issueStroke, 'circle-stroke-width': 3 } });
  map.addSource('courseSlope', { type: 'geojson', data: EMPTY });
  map.addSource('courseCtrl', { type: 'geojson', data: EMPTY });
  map.addLayer({ id: 'course-slope', type: 'line', source: 'courseSlope', minzoom: 13, layout: { 'line-cap': 'round' },
    paint: { 'line-color': slopeColor, 'line-width': 4, 'line-opacity': 1 } }, labelLayer);
  map.addLayer({ id: 'course-ctrl', type: 'circle', source: 'courseCtrl', paint: { 'circle-radius': 6, 'circle-color': THEME.controller, 'circle-stroke-color': '#fff', 'circle-stroke-width': 2 } });
  map.on('click', 'course-slope', (e) => {
    const p = e.features[0].properties;
    new maplibregl.Popup().setLngLat(e.lngLat).setText('경사 ' + Number(p.grade).toFixed(1) + '% · 구간 ' + Number(p.len).toFixed(0) + 'm (선형 정합 추정 · 30m DEM, 오차 큼)').addTo(map);
  });
  map.on('click', 'course-ctrl', (e) => {
    const p = e.features[0].properties;
    new maplibregl.Popup().setLngLat(e.lngLat).setText('신호제어기 · ' + (p.place && p.place !== 'null' ? p.place : '이름 없음') + ' · 코스에서 ' + p.d + 'm (교차로 단위, 기준일 2020-12-31)').addTo(map);
  });
  map.addSource('trailIssues', { type: 'geojson', data: EMPTY });
  map.addLayer({ id: 'trail-issues', type: 'circle', source: 'trailIssues', minzoom: 13, layout: { visibility: 'none' },
    paint: { 'circle-radius': 6, 'circle-color': ['match', ['get', 'type'], 'roundabout_loop', THEME.roundabout, 'gap', THEME.gapMarker, THEME.osmSignal], 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5 } });
  map.addLayer({ id: 'course-osm', type: 'circle', source: 'course', filter: ['==', ['get', 'layer'], 'osm'],
    paint: { 'circle-radius': 5, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5,
      'circle-color': ['match', ['get', 'kind'], 'signal', THEME.osmSignal, 'signal_junction', THEME.osmSignal, THEME.osmOther] } });
  map.addLayer({ id: 'course-entrance', type: 'circle', source: 'course', filter: ['==', ['get', 'layer'], 'entrance'],
    paint: { 'circle-radius': 7, 'circle-color': THEME.white, 'circle-stroke-color': THEME.ink, 'circle-stroke-width': 2.5 } });
  map.addLayer({ id: 'course-signal', type: 'circle', source: 'course', filter: ['==', ['get', 'layer'], 'signal'],
    paint: { 'circle-radius': 6, 'circle-color': THEME.crosswalk, 'circle-stroke-color': '#fff', 'circle-stroke-width': 2 } });

  const ISSUE_LABEL = { roundabout_loop: '회전교차로 고리', gap: '끊김', crossing_without_node: '교차(노드 없음)', t_without_node: 'T자(노드 없음)', overlap: '겹침' };
  const STATUS_LABEL = { arc_replaced: '고리를 지나가는 호로 줄임', bridged: '연결선으로 이음', flagged: '표시만(자동 수정 안 함)' };
  map.on('click', 'course-issue', (e) => {
    const p = e.features[0].properties;
    new maplibregl.Popup().setLngLat(e.lngLat).setText((ISSUE_LABEL[p.type] || p.type) + ' · ' + (STATUS_LABEL[p.status] || p.status) + (p.meters && p.meters !== 'null' ? ' · ' + p.meters + 'm' : '') + ' — ' + (p.detail || '')).addTo(map);
  });
  map.on('click', 'trail-issues', (e) => {
    const p = e.features[0].properties;
    new maplibregl.Popup().setLngLat(e.lngLat).setText((ISSUE_LABEL[p.type] || p.type) + (p.meters && p.meters !== 'null' ? ' · ' + p.meters + 'm' : '') + ' — ' + (p.detail || '')).addTo(map);
  });
  map.on('click', 'course-entrance', (e) => {
    const p = e.features[0].properties;
    const km = (v) => (v === null || v === 'null' ? '정보 없음' : v + 'm');
    new maplibregl.Popup().setLngLat(e.lngLat)
      .setText('출입구 ' + p.seq + ' · 가장 가까운 공공 신호 횡단보도 ' + km(p.nearestSignalM) + ' · OSM 횡단 ' + km(p.nearestOsmM) + (p.nearestOsmKind && p.nearestOsmKind !== 'null' ? '(' + (OSM_KIND_LABEL[p.nearestOsmKind] || p.nearestOsmKind) + ')' : '')).addTo(map);
  });
  map.on('click', 'signals', (e) => {
    const p = e.features[0].properties;
    const t = (v) => (v && v !== 'null' ? v : '정보 없음');
    new maplibregl.Popup().setLngLat(e.lngLat).setText('신호제어기(교차로 단위) · ' + t(p.label) + ' · ' + t(p.sigungu) + ' · ' + t(p.roadType)).addTo(map);
  });
  for (const id of ['xwalk', 'course-osm', 'course-signal']) {
    map.on('click', id, (e) => {
      const p = e.features[0].properties;
      const label = (p.label && p.label !== 'null' ? p.label : '이름 없음') + (p.kind ? ' · ' + (OSM_KIND_LABEL[p.kind] || p.kind) : '') + (p.dong && p.dong !== 'null' ? ' · ' + p.dong : '');
      new maplibregl.Popup().setLngLat(e.lngLat).setText(label).addTo(map);
    });
    map.on('mouseenter', id, () => { map.getCanvas().style.cursor = 'pointer'; });
    map.on('mouseleave', id, () => { map.getCanvas().style.cursor = ''; });
  }
  map.on('moveend', loadViewLayers);
  map.on('moveend', loadTrailsInView);
  $('lyTrails').addEventListener('change', loadTrailsInView);
  $('lyNetwork').addEventListener('change', loadNetwork);
  map.on('click', 'hd-network', (e) => {
    const p = e.features[0].properties;
    new maplibregl.Popup().setLngLat(e.lngLat).setText((p.name || '이름 없는 길') + ' · ' + p.highway + ' · ' + (p.walk === 'yes' ? 'OSM 보행로 분류(현장 미확인)' : '도로 · 보행 가능 여부 미확인') + ' · 원본 노드·좌표 보존').addTo(map);
  });
  $('lySlope').addEventListener('change', toggleSlope);
  $('lyNearbySlope').addEventListener('change', toggleSlope);
  for (const id of ['slope', 'nearby-slope']) map.on('click', id, (e) => {
    const p = e.features[0].properties;
    new maplibregl.Popup().setLngLat(e.lngLat).setText('경사 크기 ' + Number(p.grade).toFixed(1) + '% · ' + Number(p.len).toFixed(0) + 'm · ' + (id === 'slope' ? '산책로 선형 정합 추정' : '주변 원본 구간, 같은 길 확인 안 됨') + ' (30m DEM 추정)').addTo(map);
  });
  $('lyIssues').addEventListener('change', toggleIssues);
  // 타일 로딩 전에 선택한 옵션도 지도 준비 후 적용한다.
  if ($('lyTrails').checked) loadTrailsInView();
  if ($('lyNetwork').checked) loadNetwork();
  if ($('lySlope').checked || $('lyNearbySlope').checked) toggleSlope();
  if ($('lyIssues').checked) toggleIssues();
}

// 코스 목록은 지도 타일 로딩과 무관하므로 지도 load 를 기다리지 않고 바로 받는다.
Promise.all([
  getJson('/api/courses').catch(() => null),
  getJson('/data/galmaetgil/index.json').catch(() => null),
  getJson('/data/haeundae/courses/index.json').catch(() => null),
]).then(([legacy, official, refined]) => {
  const courses = [...(official?.courses || []), ...(refined?.courses || legacy?.courses || []).filter((c) => !official?.courses?.length || c.code !== 'HD-GALMAET')];
  if (courses.length) renderList(courses);
  else $('list').textContent = '코스 데이터를 불러오지 못했습니다.';
});

let viewTimer;
function loadViewLayers() {
  const map = window.map;
  clearTimeout(viewTimer);
  const z = map.getZoom();
  if (z < 14) map.getSource('signals').setData(EMPTY);
  if (z < 15) map.getSource('xwalk').setData(EMPTY);
  if (z < 14) return;
  viewTimer = setTimeout(() => {
    const c = map.getCenter(), b = map.getBounds();
    const half = (v, lim) => Math.min(v, lim);
    const w = Math.max(b.getWest(), c.lng - half(c.lng - b.getWest(), 0.024));
    const e = Math.min(b.getEast(), c.lng + half(b.getEast() - c.lng, 0.024));
    const s = Math.max(b.getSouth(), c.lat - half(c.lat - b.getSouth(), 0.024));
    const n = Math.min(b.getNorth(), c.lat + half(b.getNorth() - c.lat, 0.024));
    const q = '?bbox=' + [w, s, e, n].map((v) => v.toFixed(5)).join(',');
    getJson('/api/signals' + q).then((fc) => map.getSource('signals').setData(fc)).catch(() => {});
    if (z >= 15) getJson('/api/crosswalks' + q).then((fc) => map.getSource('xwalk').setData(fc)).catch(() => {});
  }, 250);
}

function renderList(courses) {
  const list = $('list');
  list.textContent = '';
  for (const c of courses) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn course-btn';
    b.setAttribute('aria-pressed', 'false');
    b.textContent = c.name + ' (' + (KIND_LABEL[c.kind] || c.kind) + ', ' + (c.length_m / 1000).toFixed(1) + 'km)';
    b.addEventListener('click', () => mapReady.then((ready) => { if (ready) return selectCourse(c, b); }));
    list.appendChild(b);
  }
}

// ---- 안드로이드 앱 브리지(삼성헬스 / Health Connect) ----
// 규약: docs/android-web-bridge.md. 앱이 window.AndroidHealth 를 주입하고, 결과는 window.onAndroidHealth(객체)로 받는다.
// 웹 브라우저에서 화면만 시험하려면 주소에 ?mockHealth=1 을 붙인다(가짜 응답, 실제 데이터 아님).
function installMockHealth() {
  window.AndroidHealth = {
    status: () => 'available',
    requestPermissions: () => setTimeout(() => window.onAndroidHealth({ type: 'permission', granted: true }), 300),
    readRecent: (days) => setTimeout(() => window.onAndroidHealth({ type: 'summary', days, startMillis: Date.now() - days * 864e5, endMillis: Date.now(), steps: 48210, distanceMeters: 35120.5,
      runningSessions: [{ id: 'mock-1', startMillis: Date.now() - 36e5 * 30, endMillis: Date.now() - 36e5 * 29, title: '모의 러닝' }] }), 300),
  };
}
const HEALTH_ERR = { permission_denied: '권한이 없습니다. 연동 권한을 다시 요청하세요.', unavailable: '이 기기에서는 Health Connect를 쓸 수 없습니다.', update_required: 'Health Connect 설치 또는 업데이트가 필요합니다.', failed: '읽는 중 오류가 났습니다.' };
function initHealthBridge() {
  const mock = !window.AndroidHealth && new URLSearchParams(location.search).get('mockHealth') === '1';
  if (mock) installMockHealth();
  const bridge = window.AndroidHealth;
  if (!bridge) return; // 일반 브라우저에서는 이 구역을 숨긴 채 둔다
  $('health').hidden = false;
  const info = $('healthInfo');
  let st = 'failed';
  try {
    if (typeof bridge.status === 'function' && typeof bridge.requestPermissions === 'function' && typeof bridge.readRecent === 'function') st = bridge.status();
  } catch { /* 네이티브 오류가 지도나 날씨 초기화를 막지 않도록 한다. */ }
  $('healthConnect').disabled = st !== 'available';
  $('healthRead').disabled = st !== 'available';
  if (st !== 'available') info.textContent = HEALTH_ERR[st] || HEALTH_ERR.unavailable;
  else if (mock) info.textContent = '모의 데이터 테스트입니다. 실제 건강 기록이 아닙니다.';
  window.onAndroidHealth = (m) => {
    if (!m || typeof m !== 'object') { info.textContent = HEALTH_ERR.failed; return; }
    if (m.type === 'permission') info.textContent = m.granted === true ? '권한이 허용되었습니다. "최근 7일 불러오기"를 누르세요.' : '권한이 거부되었습니다. 삼성헬스 공유 설정을 확인하세요.';
    else if (m.type === 'error') info.textContent = HEALTH_ERR[m.code] || HEALTH_ERR.failed;
    else if (m.type === 'summary') {
      const validNumber = (v) => v === null || (typeof v === 'number' && Number.isFinite(v) && v >= 0);
      if (!Number.isInteger(m.days) || m.days < 1 || m.days > 7 || !validNumber(m.steps) || (m.steps !== null && !Number.isSafeInteger(m.steps)) || !validNumber(m.distanceMeters) || !Array.isArray(m.runningSessions)) {
        info.textContent = HEALTH_ERR.failed;
        return;
      }
      const fmt = (v, u) => (v === null || v === undefined ? '공유된 기록 없음' : Number(v).toLocaleString('ko-KR') + u);
      const runs = (m.runningSessions || []).length;
      info.textContent = (mock ? '[모의 데이터] ' : '') + '최근 ' + m.days + '일 · 걸음 수 ' + fmt(m.steps, '보') + ' · 이동거리 ' + fmt(m.distanceMeters === null ? null : m.distanceMeters / 1000, 'km') + ' · 러닝 기록 ' + runs + '건. 이동거리는 러닝만의 거리가 아니라 전체 활동 합계입니다.';
    }
  };
  const invoke = (action) => {
    try { action(); } catch { info.textContent = HEALTH_ERR.failed; }
  };
  $('healthConnect').addEventListener('click', () => invoke(() => bridge.requestPermissions()));
  $('healthRead').addEventListener('click', () => { info.textContent = '읽는 중…'; invoke(() => bridge.readRecent(7)); });
}

// ---- 러닝 날씨(해운대 구역별 판정) ----
const VERDICT_CLASS = { good: 'v-good', fair: 'v-fair', bad: 'v-bad', unknown: 'v-unknown' };
function loadWeather() {
  const sum = $('wxSummary'), list = $('wxList');
  getJson('/api/weather?scope=haeundae').then((w) => {
    const s = w.summary['해운대'];
    sum.className = 'wx-summary ' + VERDICT_CLASS[s.verdict];
    sum.textContent = '해운대 종합: ' + s.label + (s.score === null ? '' : ' (' + s.score + '점)') + ' · 판정 ' + s.coverage + '곳';
    list.textContent = '';
    for (const a of w.areas) {
      const li = document.createElement('li');
      const r = a.rating, c = a.current;
      const best = (a.bestHours || [])[0];
      li.className = VERDICT_CLASS[r.verdict];
      li.textContent = a.name + ': ' + r.label + (r.score === null ? '' : ' ' + r.score + '점')
        + (c.tempC === null ? '' : ' · ' + c.tempC.toFixed(0) + '°C(체감 ' + (c.feelsLikeC ?? '?') + ')')
        + (c.windMs === null ? '' : ' · ' + (c.windFrom || '') + ' ' + c.windMs.toFixed(1) + 'm/s')
        + (c.humidityPct === null ? '' : ' · 습도 ' + c.humidityPct.toFixed(0) + '%')
        + (r.reasons.length ? ' — ' + r.reasons.join(', ') : '')
        + (best ? ' · 추천 ' + best.time.slice(11, 16) : '');
      list.appendChild(li);
    }
  }).catch(() => { sum.className = 'wx-summary v-unknown'; sum.textContent = '날씨를 불러오지 못했습니다.'; });
}

// ---- 부울경 산책로·산길 벡터 조각(0.1° 칸) ----
const trailCache = new Map();
let trailRequest = 0, visibleTrails = EMPTY;
function trailCell(la, lo) { return (Math.floor(la * 10) / 10).toFixed(1) + '_' + (Math.floor(lo * 10) / 10).toFixed(1); }
function loadTrailsInView() {
  const map = window.map;
  const request = ++trailRequest;
  if (!$('lyTrails').checked || map.getZoom() < 12) { visibleTrails = EMPTY; map.getSource('trails').setData(EMPTY); refreshSlope(); return; }
  const b = map.getBounds(), keys = [];
  for (let la = Math.floor(b.getSouth() * 10) / 10; la <= b.getNorth(); la += 0.1) for (let lo = Math.floor(b.getWest() * 10) / 10; lo <= b.getEast(); lo += 0.1) keys.push(trailCell(la + 1e-6, lo + 1e-6));
  const uniq = [...new Set(keys)].slice(0, 12);
  Promise.all(uniq.map((k) => trailCache.get(k) ?? getJson('/data/trails/' + k + '.json').then((j) => { trailCache.set(k, j); return j; }).catch(() => ({ trails: [] }))))
    .then((parts) => {
      if (request !== trailRequest) return;
      const unique = new Map(parts.flatMap((p) => p.trails).map((t) => [t.i, t]));
      visibleTrails = { type: 'FeatureCollection', features: [...unique.values()].map((t) => ({ type: 'Feature', properties: { id: t.i, name: t.n || '', kind: t.k, region: t.r }, geometry: { type: 'LineString', coordinates: t.g } })) };
      map.getSource('trails').setData(visibleTrails);
      refreshSlope();
    });
}

// 해운대 정밀 길: 짧은 연결로와 보도를 생략하지 않은 OSM 원본이다.
let networkPromise, visibleNetwork = EMPTY;
function networkFeatures(data) {
  return { type: 'FeatureCollection', features: data.ways.map((w) => ({ type: 'Feature',
    properties: { id: w.id, highway: w.hw, walk: w.walk, name: w.name || '', footway: w.footway || '' },
    geometry: { type: 'LineString', coordinates: w.nodes.map((id) => data.nodes[id]) } })) };
}
async function loadNetwork() {
  const map = window.map;
  if (!$('lyNetwork').checked) { visibleNetwork = EMPTY; map.getSource('hdNetwork').setData(EMPTY); refreshSlope(); return; }
  $('networkStatus').textContent = '해운대 도로·보도 불러오는 중…';
  try {
    networkPromise ??= getJson('/data/haeundae/walking-network.json').then(networkFeatures).catch((err) => { networkPromise = undefined; throw err; });
    const fc = await networkPromise;
    if (!$('lyNetwork').checked) return;
    visibleNetwork = fc; map.getSource('hdNetwork').setData(fc); refreshSlope();
    $('networkStatus').textContent = '짧은 보도·횡단 연결로도 표시합니다. 회색 도로는 보행 가능 여부 미확인, 녹회색은 OSM 보행로 분류입니다.';
  } catch { $('networkStatus').textContent = '정밀 길을 불러오지 못했습니다. 옵션을 껐다 켜서 다시 시도하세요.'; }
}

// ---- 해운대 산책로 위상 문제(끊김·회전교차로·교차) 표시 ----
let issuesLoaded = false;
function toggleIssues() {
  const map = window.map;
  if (!$('lyIssues').checked) { map.setLayoutProperty('trail-issues', 'visibility', 'none'); return; }
  map.setLayoutProperty('trail-issues', 'visibility', 'visible');
  if (issuesLoaded) return;
  getJson('/data/haeundae/trail-issues.json').then((j) => {
    issuesLoaded = true;
    map.getSource('trailIssues').setData({ type: 'FeatureCollection', features: j.issues.map((i) => ({ type: 'Feature', properties: { type: i.type, meters: i.meters, detail: i.detail }, geometry: { type: 'Point', coordinates: i.at } })) });
  }).catch(() => { $('lyIssues').checked = false; });
}

// ---- 해운대 산책로 500m 이내 경사(10% 이상) ----
let slopeGrid;
function refreshSlope() {
  const map = window.map;
  if (!slopeGrid || !$('lySlope').checked) { map.getSource('slope').setData(EMPTY); return; }
  const all = new Map([...visibleTrails.features, ...visibleNetwork.features.filter((f) => f.properties.walk === 'yes')].map((f) => [f.properties.id, f]));
  const matched = matchSlopes([...all.values()], slopeGrid);
  map.getSource('slope').setData({ type: 'FeatureCollection', features: matched.features });
}
async function toggleSlope() {
  const map = window.map;
  map.setLayoutProperty('slope', 'visibility', $('lySlope').checked ? 'visible' : 'none');
  map.setLayoutProperty('nearby-slope', 'visibility', $('lyNearbySlope').checked ? 'visible' : 'none');
  if (!$('lySlope').checked && !$('lyNearbySlope').checked) return;
  $('slopeStatus').textContent = '경사 구간 불러오는 중…';
  try {
    const j = await loadContext();
    slopeGrid ??= indexSlopes(j.slope);
    map.getSource('nearbySlope').setData({ type: 'FeatureCollection', features: j.slope.map((s) => ({ type: 'Feature', properties: { grade: Math.abs(s.grade), len: s.len }, geometry: { type: 'LineString', coordinates: [s.a, s.b] } })) });
    refreshSlope();
    $('slopeStatus').textContent = '색 선은 보행로와 선형이 맞는 구간의 추정값입니다. 보행 미확인 도로는 경사 색을 입히지 않습니다. 주변 원본은 회색 점선입니다.';
  } catch {
    $('slopeStatus').textContent = '경사를 불러오지 못했습니다. 체크를 껐다 켜서 다시 시도하세요.';
  }
}

// ---- 삼성헬스 GPX 불러오기: 파일은 브라우저 안에서만 읽고 서버로 보내지 않는다 ----
const GPX_MAX_BYTES = 10 * 1024 * 1024, GPX_MAX_POINTS = 60000, CELL = 0.02, MAX_CELLS = 12, BUF_M = 20, MERGE_M = 12, TRIM_M = 150;
const KLA = 111320, mx = (lat) => 111320 * Math.cos(lat * Math.PI / 180);
const dist = (a, b) => Math.hypot((b[0] - a[0]) * mx(a[1]), (b[1] - a[1]) * KLA);

function parseGpxText(text) {
  const doc = new DOMParser().parseFromString(text, 'application/xml');
  if (doc.querySelector('parsererror')) throw new Error('GPX 형식이 올바르지 않습니다.');
  let nodes = [...doc.getElementsByTagName('trkpt')];
  if (!nodes.length) nodes = [...doc.getElementsByTagName('rtept')];
  const pts = [];
  for (const n of nodes) {
    const lat = parseFloat(n.getAttribute('lat')), lon = parseFloat(n.getAttribute('lon'));
    if (Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) pts.push([lon, lat]);
    if (pts.length > GPX_MAX_POINTS) throw new Error('점이 너무 많습니다(최대 ' + GPX_MAX_POINTS + ').');
  }
  return pts;
}

function trimEnds(pts, m) {
  let a = 0, acc = 0;
  while (a < pts.length - 1 && acc < m) { acc += dist(pts[a], pts[a + 1]); a++; }
  let b = pts.length - 1; acc = 0;
  while (b > a && acc < m) { acc += dist(pts[b], pts[b - 1]); b--; }
  return b - a >= 1 ? pts.slice(a, b + 1) : [];
}

function densify(pts, stepM) {
  const out = [];
  for (let i = 0; i < pts.length - 1; i++) {
    const n = Math.max(1, Math.ceil(dist(pts[i], pts[i + 1]) / stepM));
    for (let k = 0; k < n; k++) out.push([pts[i][0] + (pts[i + 1][0] - pts[i][0]) * k / n, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * k / n]);
  }
  out.push(pts.at(-1));
  return out;
}

async function analyzeRun(pts) {
  const lons = pts.map((p) => p[0]), lats = pts.map((p) => p[1]);
  const cells = [];
  for (let x = Math.floor(Math.min(...lons) / CELL); x <= Math.floor(Math.max(...lons) / CELL); x++)
    for (let y = Math.floor(Math.min(...lats) / CELL); y <= Math.floor(Math.max(...lats) / CELL); y++) cells.push([x, y]);
  if (cells.length > MAX_CELLS) throw new Error('경로가 너무 넓어 분석하지 않습니다(부산 해운대 일대용).');
  const found = new Map();
  for (const [x, y] of cells) {
    const q = [x * CELL, y * CELL, (x + 1) * CELL, (y + 1) * CELL].map((v) => v.toFixed(5)).join(',');
    const fc = await getJson('/api/crosswalks?bbox=' + q);
    for (const f of fc.features) found.set(f.properties.id, f);
  }
  if (found.size === 0) throw new Error('이 지역에는 신호 횡단보도 데이터가 없습니다(부산 데이터만 제공).');
  const dense = densify(pts, 5);
  const near = [];
  for (const f of found.values()) {
    const c = f.geometry.coordinates;
    let min = Infinity;
    for (const p of dense) { const d = dist(p, c); if (d < min) min = d; if (min <= BUF_M) break; }
    if (min <= BUF_M) near.push({ c, label: f.properties.label });
  }
  const merged = [];
  for (const n of near) if (!merged.some((m) => dist(m.c, n.c) <= MERGE_M)) merged.push(n);
  const km = pts.slice(1).reduce((s, p, i) => s + dist(pts[i], p), 0) / 1000;
  return { km, merged, loaded: found.size };
}

function showRun(pts, result) {
  const map = window.map;
  if (!map.getSource('myrun')) {
    map.addSource('myrun', { type: 'geojson', data: EMPTY });
    map.addLayer({ id: 'myrun-line', type: 'line', source: 'myrun', filter: ['==', ['get', 'k'], 'line'], paint: { 'line-color': THEME.myrun, 'line-width': 4, 'line-dasharray': [2, 1] } });
    map.addLayer({ id: 'myrun-xwalk', type: 'circle', source: 'myrun', filter: ['==', ['get', 'k'], 'xwalk'],
      paint: { 'circle-radius': 7, 'circle-color': THEME.crosswalk, 'circle-stroke-color': THEME.myrun, 'circle-stroke-width': 3 } });
  }
  const features = [{ type: 'Feature', properties: { k: 'line' }, geometry: { type: 'LineString', coordinates: pts } },
    ...result.merged.map((m) => ({ type: 'Feature', properties: { k: 'xwalk', label: m.label }, geometry: { type: 'Point', coordinates: m.c } }))];
  map.getSource('myrun').setData({ type: 'FeatureCollection', features });
  const b = new maplibregl.LngLatBounds();
  for (const p of pts) b.extend(p);
  map.fitBounds(b, { padding: 60, maxZoom: 17 });
}

function clearRun() {
  const map = window.map;
  if (map.getSource('myrun')) map.getSource('myrun').setData(EMPTY);
  $('gpxFile').value = '';
  $('gpxInfo').textContent = '기록을 지웠습니다.';
}

async function onGpxPicked() {
  const file = $('gpxFile').files[0];
  const info = $('gpxInfo');
  if (!file) return;
  info.textContent = '지도를 불러오는 중…';
  if (!await mapReady) { info.textContent = '지도를 불러오지 못해 경로를 표시할 수 없습니다. 새로고침 후 다시 시도하세요.'; return; }
  if (file.size > GPX_MAX_BYTES) { info.textContent = '파일이 너무 큽니다(최대 10MB).'; return; }
  info.textContent = '분석 중…';
  try {
    let pts = parseGpxText(await file.text());
    if (pts.length < 2) throw new Error('경로 점을 찾지 못했습니다.');
    if ($('trimEnds').checked) pts = trimEnds(pts, TRIM_M);
    if (pts.length < 2) throw new Error('시작·끝 150m를 숨기면 남는 경로가 없습니다. 옵션을 끄고 다시 시도하세요.');
    const result = await analyzeRun(pts);
    showRun(pts, result);
    info.textContent = '거리 ' + result.km.toFixed(2) + 'km · 지나친 신호 횡단보도 ' + result.merged.length + '곳(' + (result.km > 0 ? (result.merged.length / result.km).toFixed(1) : '0') + '곳/km, ' + BUF_M + 'm 이내·12m 합산). 신호 없는 횡단보도는 포함되지 않습니다.';
    if ($('coursebox')) $('coursebox').open = false;
  } catch (err) {
    info.textContent = err.message || '분석하지 못했습니다.';
  }
}

$('gpxFile').addEventListener('change', onGpxPicked);
$('gpxClear').addEventListener('click', () => mapReady.then((ready) => {
  if (ready) clearRun();
  else { $('gpxFile').value = ''; $('gpxInfo').textContent = '기록을 지웠습니다.'; }
}));

// ---- 선택한 코스 기준 상세: 경사 10%↑ 구간과 신호제어기·횡단보도 ----
let contextPromise;
const loadContext = () => (contextPromise ??= getJson('/data/haeundae/trail-context.json').catch((err) => { contextPromise = undefined; throw err; }));
const CS_SIGNAL_M = 100;
function pointToLineM(p, lines) {
  let best = Infinity;
  const kx = 111320 * Math.cos(p[1] * Math.PI / 180), ky = 111320;
  for (const line of lines) {
    for (let i = 0; i < line.length - 1; i++) {
      const ax = (line[i][0] - p[0]) * kx, ay = (line[i][1] - p[1]) * ky, bx = (line[i + 1][0] - p[0]) * kx, by = (line[i + 1][1] - p[1]) * ky;
      const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
      const t = l2 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0;
      const d = Math.hypot(ax + t * dx, ay + t * dy);
      if (d < best) best = d;
    }
  }
  return best;
}
async function showCourseDetail(fc, selection) {
  const box = $('detail');
  const analysisFeatures = fc.features.filter((f) => f.properties.layer === 'course' && !['gap_bridge', 'closed_reference', 'detour_candidate'].includes(f.properties.highway) && !f.properties.roadAccessUnverified);
  const lines = analysisFeatures.filter((f) => f.geometry.type === 'LineString').map((f) => f.geometry.coordinates);
  const map = window.map;
  map.getSource('courseSlope').setData(EMPTY); map.getSource('courseCtrl').setData(EMPTY);
  box.textContent = '';
  if (!lines.length) return;
  let ctx;
  try { ctx = await loadContext(); } catch { return; }
  if (selection !== courseSelection) return;
  slopeGrid ??= indexSlopes(ctx.slope);
  const slope = matchSlopes(analysisFeatures, slopeGrid).features;
  const ctrl = [];
  for (const s of (fc.metadata?.trafficCoverage === 'not_analyzed' ? [] : ctx.signals)) { const d = pointToLineM([s.lon, s.lat], lines); if (d <= CS_SIGNAL_M) ctrl.push({ ...s, d: Math.round(d) }); }
  map.getSource('courseSlope').setData({ type: 'FeatureCollection', features: slope });
  map.getSource('courseCtrl').setData({ type: 'FeatureCollection', features: ctrl.map((s) => ({ type: 'Feature', properties: { place: s.place, d: s.d }, geometry: { type: 'Point', coordinates: [s.lon, s.lat] } })) });

  const add = (tag, text) => { const el = document.createElement(tag); el.textContent = text; box.appendChild(el); return el; };
  add('h2', '코스 선형에 맞춘 경사 (10% 이상, 추정)');
  if (!slope.length) add('p', '해당 구간이 없습니다. (경사 원본이 30m 해상도 DEM 기반이라 완만하다는 뜻은 아닙니다)');
  else {
    const estimates = slope.map((f) => f.properties);
    const total = estimates.reduce((s, x) => s + x.len, 0), max = estimates.reduce((m, x) => (Math.abs(x.grade) > Math.abs(m.grade) ? x : m));
    add('p', '정합 구간 합 ' + Math.round(total) + 'm · 최대 ' + Math.abs(max.grade).toFixed(1) + '% (오르막/내리막은 점 방향에 따라 부호가 달라 크기만 표시). 추정값으로 오차가 큽니다.');
    const ul = add('ul', '');
    for (const x of [...estimates].sort((a, b) => Math.abs(b.grade) - Math.abs(a.grade)).slice(0, 5)) {
      const li = document.createElement('li'); li.textContent = Math.abs(x.grade).toFixed(1) + '% · ' + x.len.toFixed(0) + 'm'; ul.appendChild(li);
    }
  }
  if (fc.metadata?.trafficCoverage === 'not_analyzed') {
    add('h2', '신호·횡단 정보');
    add('p', '이 공식 트랙의 횡단보도·신호 개수는 아직 분석하지 않았습니다. 정보 없음은 신호 없음이라는 뜻이 아닙니다. 경사 자료도 해운대 일부 구간에 한정됩니다.');
  } else {
    if (fc.metadata?.trafficCoverage === 'baseline_before_repair') add('p', '횡단보도 수는 경로 보완 전 집계입니다. 추가 연결로의 신호는 아직 반영하지 않았습니다.');
    add('h2', '신호 (신호제어기 ' + CS_SIGNAL_M + 'm 이내 · 횡단보도 ' + 20 + 'm 이내)');
    const cross = fc.features.filter((f) => f.properties.layer === 'signal').length;
    add('p', '신호제어기 ' + ctrl.length + '곳 · 코스 위 신호 횡단보도 ' + cross + '곳(12m 이내 점은 서버 집계에서 합산). 신호 없는 횡단보도는 공공 파일에 없고, 제어기 위치는 2020-12-31 기준 교차로 단위입니다.');
    if (ctrl.length) {
      const ul = add('ul', '');
      for (const x of [...ctrl].sort((a, b) => a.d - b.d).slice(0, 5)) {
        const li = document.createElement('li'); li.textContent = (x.place || '이름 없음') + ' · ' + x.d + 'm'; ul.appendChild(li);
      }
    }
  }
  const iss = fc.features.filter((f) => f.properties.layer === 'issue');
  if (iss.length) {
    add('h2', '선형 정리 내역');
    const ul = add('ul', '');
    for (const f2 of iss) { const li = document.createElement('li'); li.textContent = (ISSUE_LABEL_TXT[f2.properties.type] || f2.properties.type) + ' → ' + (STATUS_LABEL_TXT[f2.properties.status] || f2.properties.status) + (f2.properties.meters ? ' (' + f2.properties.meters + 'm)' : ''); ul.appendChild(li); }
  }
}
const ISSUE_LABEL_TXT = { roundabout_loop: '회전교차로 고리', gap: '끊김', crossing_without_node: '교차(노드 없음)', t_without_node: 'T자(노드 없음)', overlap: '겹침' };
const STATUS_LABEL_TXT = { arc_replaced: '지나가는 호만 표시', bridged: '점선 연결선으로 이음', flagged: '표시만(자동 수정 안 함)' };

let courseSelection = 0;
async function selectCourse(c, button) {
  const selection = ++courseSelection;
  for (const b of $('list').children) b.setAttribute('aria-pressed', String(b === button));
  const info = $('info');
  info.textContent = '불러오는 중…';
  try {
    const fc = await getJson(c.geojson || '/api/courses/' + encodeURIComponent(c.code) + '/geojson');
    if (selection !== courseSelection) return;
    const map = window.map;
    map.getSource('course').setData(fc);
    const bounds = new maplibregl.LngLatBounds();
    for (const f of fc.features) {
      const coords = f.geometry.type === 'LineString' ? f.geometry.coordinates : [f.geometry.coordinates];
      for (const p of coords) bounds.extend(p);
    }
    showCourseDetail(fc, selection).catch(() => { if (selection === courseSelection) $('detail').textContent = '상세 정보를 불러오지 못했습니다. 코스를 다시 선택하세요.'; });
    if (window.innerWidth <= 600 || c.kind === 'official') $('coursebox').open = false; // 공식 트랙의 통제 안내가 긴 목록 아래 가려지지 않게 한다.
    if (!bounds.isEmpty()) map.fitBounds(bounds, { padding: 60, maxZoom: 17 });
    if (c.kind === 'official') {
      info.textContent = c.note + (c.alert ? ' ' + c.alert : '');
      for (const [label, href] of [['공식 원본', c.sourceUrl], ['공사·우회 공지', c.noticeUrl]]) {
        if (!href) continue;
        const link = document.createElement('a'); link.href = href; link.target = '_blank'; link.rel = 'noopener noreferrer'; link.textContent = ' ' + label;
        info.appendChild(link);
      }
      return;
    }
    const osm = c.crossings.filter((x) => x.source === 'osm').map((x) => (OSM_KIND_LABEL[x.kind] || x.kind) + ' ' + x.count).join(', ');
    info.textContent = '공공 데이터의 신호 횡단보도 ' + c.unique_crossings + '곳(' + c.buffer_m + 'm 이내, 12m 이내는 한 곳으로 합산)'
      + (osm ? ' · OSM 횡단 참고: ' + osm : '') + '. ' + (c.note || '');
  } catch (err) {
    if (selection === courseSelection) info.textContent = '코스를 불러오지 못했습니다.';
  }
}

// 지도 서비스 장애와 무관하게 독립 기능을 사용할 수 있다.
loadWeather();
initHealthBridge();
