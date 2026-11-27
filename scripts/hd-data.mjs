// 해운대구 신호제어기·횡단보도 정제 함수(순수 계산). build-haeundae-courses.mjs 가 호출한다.
import { readFileSync } from "node:fs";
import { toXY } from "./lib-geo.mjs";

export const SCOPE_GU = "해운대구";
const LEGAL_DONG = new Set(["우동", "중동", "좌동", "송정동", "반여동", "반송동", "재송동", "석대동"]);

function parseCsvLine(l) {
  const out = []; let f = "", q = false;
  for (let i = 0; i < l.length; i++) {
    const c = l[i];
    if (q) { if (c === '"') { if (l[i + 1] === '"') { f += '"'; i++; } else q = false; } else f += c; }
    else if (c === '"') q = true; else if (c === ",") { out.push(f); f = ""; } else f += c;
  }
  out.push(f);
  return out;
}

// 신호제어기 CSV(UTF-8) → 해운대구만, 정규화
export function readHaeundaeSignals(csvPath) {
  const lines = readFileSync(csvPath, "utf8").replace(/^﻿/, "").trim().split(/\r?\n/);
  const head = lines[0].split(",");
  const all = lines.slice(1).map((l) => Object.fromEntries(parseCsvLine(l).map((v, i) => [head[i], v])));
  const rows = all.filter((r) => r.sigungu === SCOPE_GU).map((r) => ({ ...r, lon: parseFloat(r.lon), lat: parseFloat(r.lat) }))
    .filter((r) => Number.isFinite(r.lon) && Number.isFinite(r.lat));
  const byMgmt = new Map(), byCoord = new Map();
  for (const r of rows) {
    (byMgmt.get(r.mgmt_no) ?? byMgmt.set(r.mgmt_no, []).get(r.mgmt_no)).push(r);
    const k = r.lat.toFixed(5) + "," + r.lon.toFixed(5);
    (byCoord.get(k) ?? byCoord.set(k, []).get(k)).push(r);
  }
  return rows.map((r) => {
    const notes = [];
    if (byMgmt.get(r.mgmt_no).length > 1) notes.push("관리번호가 다른 장소와 중복(원본 오입력 가능)");
    const same = byCoord.get(r.lat.toFixed(5) + "," + r.lon.toFixed(5)).length;
    if (same > 1) notes.push(`같은 좌표에 제어기 ${same}개(좌표가 근사일 수 있음)`);
    const m = /해운대구\s+([가-힣0-9]+)/.exec(r.address ?? "");
    let dong = null;
    if (m) {
      const stripped = m[1].replace(/[0-9]/g, "");
      if (LEGAL_DONG.has(stripped)) { dong = stripped; if (stripped !== m[1]) notes.push(`주소의 ${m[1]}을 법정동 ${stripped}으로 환산`); }
    }
    const kind = /전자신호/.test(r.control_kind) ? "전자신호제어" : /일반신호/.test(r.control_kind) ? "일반신호제어" : "기타";
    const isSignal = r.road_type === "모뎀" ? 0 : 1;
    if (!isSignal) notes.push("모뎀 등 신호기가 아닌 장비(신호 횡단 연결에서 제외)");
    return { ...r, control_kind_norm: kind, dong, is_signal: isSignal, note: notes.join("; ") || null };
  });
}

const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);

// 12m 이내 점을 하나의 건널목으로 묶는다. 대표 점은 가장 먼저 나온 점.
export function clusterCrosswalks(crosswalks, mergeM = 12) {
  const centers = [];
  return crosswalks.map((c) => {
    const p = toXY([c.lon, c.lat]);
    let k = centers.findIndex((q) => dist(q.p, p) <= mergeM);
    const primary = k < 0;
    if (primary) { centers.push({ p }); k = centers.length - 1; }
    return { crosswalk_id: c.id, cluster_id: k + 1, is_primary: primary ? 1 : 0 };
  });
}

// 횡단보도마다 가장 가까운 신호제어기(모뎀 제외). 100m 이내면 matched=1
export function linkSignals(crosswalks, signals, matchM = 100) {
  const sig = signals.map((s) => ({ s, p: toXY([s.lon, s.lat]) }));
  return crosswalks.map((c) => {
    const p = toXY([c.lon, c.lat]);
    let best = null;
    for (const x of sig) { const d = dist(p, x.p); if (!best || d < best.d) best = { d, id: x.s.sid }; }
    return { crosswalk_id: c.id, signal_id: best.id, distance_m: +best.d.toFixed(1), matched: best.d <= matchM ? 1 : 0 };
  });
}

const pct = (n, d) => (d ? ((n / d) * 100).toFixed(1) + "%" : "n/a");

export function buildChecks({ crosswalks, clusters, signals, links, osmNodes, crossingsDated }) {
  const out = [];
  const add = (subject, metric, value, note = "") => out.push({ subject, metric, value: String(value), note });
  const uniq = clusters.filter((c) => c.is_primary).length;
  add("해운대 횡단보도", "행 수(원본, 해운대구만)", crosswalks.length, "부산 전체 파일 중 행정구역(구)=해운대구인 행");
  add("해운대 횡단보도", "고유 건널목(12m 이내 병합)", uniq, `병합으로 ${crosswalks.length - uniq}행이 대표 점에 묶임`);
  add("해운대 횡단보도", "크기(가로·세로) 비어 있음", crosswalks.filter((c) => !c.width || !c.length).length);
  add("해운대 횡단보도", "교차로명 비어 있음", crosswalks.filter((c) => !c.name).length, "교차로명은 주변 건물명 라벨이며 도로명이 아님");
  const byDong = {}; for (const c of crosswalks) byDong[c.dong] = (byDong[c.dong] ?? 0) + 1;
  add("해운대 횡단보도", "동별 분포", Object.keys(byDong).length, Object.entries(byDong).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k} ${v}`).join(", "));
  add("해운대 신호제어기", "행 수", signals.length, "교차로 신호제어기 단위. 보행 신호등 개별 위치가 아님");
  const typ = {}; for (const s of signals) typ[s.road_type] = (typ[s.road_type] ?? 0) + 1;
  add("해운대 신호제어기", "형태별", Object.keys(typ).length, Object.entries(typ).map(([k, v]) => `${k} ${v}`).join(", "));
  add("해운대 신호제어기", "신호기가 아닌 장비(모뎀)", signals.filter((s) => !s.is_signal).length, "연결·집계에서 제외");
  add("해운대 신호제어기", "제어 방식 표기 정규화", [...new Set(signals.map((s) => s.control_kind_norm))].join("/"), "원본에 '전자신호 제어'/'전자신호제어' 같은 표기 흔들림이 있어 통일");
  add("해운대 신호제어기", "관리번호 중복 행", signals.filter((s) => (s.note ?? "").includes("관리번호")).length);
  add("해운대 신호제어기", "같은 좌표를 공유하는 행", signals.filter((s) => (s.note ?? "").includes("같은 좌표")).length);
  add("해운대 신호제어기", "법정동 추출 실패", signals.filter((s) => !s.dong).length, "주소가 도로명 등으로 적힌 행");
  add("해운대 신호제어기", "행의 데이터기준일자", [...new Set(signals.map((s) => s.confirmed_on))].join(" | "), "행마다 적힌 기준일이 오래됨(파일 수정일은 2025-08-26). 신설·철거 반영 여부 미확인");
  const m50 = links.filter((l) => l.distance_m <= 50).length, m100 = links.filter((l) => l.matched).length, m200 = links.filter((l) => l.distance_m <= 200).length;
  add("횡단보도↔제어기", "가장 가까운 제어기 50m 이내", pct(m50, links.length), `100m: ${pct(m100, links.length)}, 200m: ${pct(m200, links.length)}`);
  const far = links.filter((l) => l.distance_m > 300).map((l) => crosswalks.find((c) => c.id === l.crosswalk_id)).map((c) => `${c.name || c.jibun}(${c.dong})`);
  add("횡단보도↔제어기", "제어기에서 300m 넘는 횡단보도", far.length, far.join(", ") + " — 구 경계(수영강 건너편 등) 근처는 이웃 구의 제어기가 더 가까울 수 있으나 이웃 구는 적재하지 않음");
  const sp = signals.filter((s) => s.is_signal).map((s) => toXY([s.lon, s.lat]));
  const cp = crosswalks.map((c) => toXY([c.lon, c.lat]));
  const near100 = sp.filter((p) => cp.some((q) => dist(p, q) <= 100)).length;
  add("횡단보도↔제어기", "제어기 중 횡단보도가 100m 이내", pct(near100, sp.length), "제어기 위치는 교차로 기준이라 횡단보도와 떨어질 수 있음");
  if (osmNodes.length) {
    const near = osmNodes.filter((p) => sp.some((q) => dist(p, q) <= 50)).length;
    add("OSM 비교(해운대)", "OSM 신호기 노드 수", osmNodes.length);
    add("OSM 비교(해운대)", "OSM 신호기 중 제어기가 50m 이내", pct(near, osmNodes.length), "OSM은 기여자 입력이라 완전성 미보증");
    const covered = sp.filter((p) => osmNodes.some((q) => dist(p, q) <= 50)).length;
    add("OSM 비교(해운대)", "제어기 중 OSM 신호기가 50m 이내", pct(covered, sp.length), "낮으면 OSM이 신호기를 덜 표기했다는 뜻");
  }
  return out;
}
