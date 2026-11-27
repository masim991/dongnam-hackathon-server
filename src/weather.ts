// 러닝 날씨 판정: 풍속·풍향·체감온도·습도·강수·미세먼지를 합쳐 0~100점과 좋음/보통/나쁨으로 구분한다.
// 기준값은 아래 상수에서 한곳으로 조정한다(공식 기준이 아니라 러닝 편의를 위한 경험적 규칙).
export type Verdict = "good" | "fair" | "bad" | "unknown"; // unknown: 필수 관측값(기온·풍속)이 없어 판정하지 않음

export interface Observation {
  temp: number | null;        // °C
  feelsLike: number | null;   // °C
  humidity: number | null;    // %
  windSpeed: number | null;   // m/s
  windGust: number | null;    // m/s
  windDir: number | null;     // 도(바람이 불어오는 방향)
  precip: number | null;      // mm (직전 1시간)
  pm25: number | null;        // ㎍/㎥
  pm10: number | null;        // ㎍/㎥
}

export interface Rating {
  score: number | null;       // unknown이면 null
  verdict: Verdict;
  label: string;
  reasons: string[];
}

export const GOOD_AT = 75;
export const FAIR_AT = 50;

const LABEL: Record<Verdict, string> = { good: "러닝하기 좋은 날씨", fair: "보통", bad: "좋지 않은 날씨", unknown: "판정 불가(관측값 없음)" };
const DIRS = ["북", "북북동", "북동", "동북동", "동", "동남동", "남동", "남남동", "남", "남남서", "남서", "서남서", "서", "서북서", "북서", "북북서"];

export const windDirLabel = (deg: number | null): string | null =>
  deg === null || !Number.isFinite(deg) ? null : DIRS[Math.round((((deg % 360) + 360) % 360) / 22.5) % 16] + "풍";

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

export function rate(o: Observation): Rating {
  // 기온(또는 체감온도)과 풍속은 필수. 없으면 "좋음"으로 오인하지 않도록 판정하지 않는다.
  if ((o.temp === null && o.feelsLike === null) || o.windSpeed === null) {
    return { score: null, verdict: "unknown", label: LABEL.unknown, reasons: ["기온·풍속 관측값이 없어 판정할 수 없습니다"] };
  }
  let score = 100;
  const reasons: string[] = [];
  let hardBad = false;
  const pen = (n: number, why: string) => { if (n > 0.5) { score -= n; reasons.push(why); } };

  const t = o.feelsLike ?? o.temp;
  if (t !== null) {
    if (t > 18) pen(t <= 24 ? (t - 18) * 3 : t <= 30 ? 18 + (t - 24) * 5 : 48 + (t - 30) * 6, t > 24 ? `덥습니다(체감 ${t.toFixed(0)}°C)` : `다소 따뜻합니다(체감 ${t.toFixed(0)}°C)`);
    else if (t < 5) pen(t >= 0 ? (5 - t) * 3 : 15 + (0 - t) * 5, t < 0 ? `춥습니다(체감 ${t.toFixed(0)}°C)` : `쌀쌀합니다(체감 ${t.toFixed(0)}°C)`);
    if (t >= 33 || t <= -10) hardBad = true;
  }
  if (o.humidity !== null && o.humidity > 70) pen(clamp((o.humidity - 70) * 0.5, 0, 15), `습합니다(습도 ${o.humidity.toFixed(0)}%)`);
  if (o.windSpeed !== null) {
    const v = o.windSpeed;
    if (v > 4) pen(v <= 8 ? (v - 4) * 5 : 20 + (v - 8) * 8, `바람이 셉니다(${v.toFixed(1)}m/s)`);
    if (v >= 12) hardBad = true;
  }
  if (o.windGust !== null && o.windGust > 12) pen(10, `돌풍 ${o.windGust.toFixed(0)}m/s`);
  if (o.precip !== null && o.precip > 0) { pen(clamp(20 + o.precip * 10, 0, 60), `비·눈(${o.precip.toFixed(1)}mm/h)`); if (o.precip >= 2) hardBad = true; }
  if (o.pm25 !== null && o.pm25 > 15) { pen(o.pm25 <= 35 ? (o.pm25 - 15) * 0.75 : 15 + (o.pm25 - 35) * 0.75, `초미세먼지 ${o.pm25.toFixed(0)}㎍/㎥`); if (o.pm25 > 75) hardBad = true; }
  if (o.pm10 !== null && o.pm10 > 30) { pen(o.pm10 <= 80 ? (o.pm10 - 30) * 0.3 : 15 + (o.pm10 - 80) * 0.3, `미세먼지 ${o.pm10.toFixed(0)}㎍/㎥`); if (o.pm10 > 150) hardBad = true; }

  score = Math.round(clamp(score, 0, 100));
  const verdict: Verdict = hardBad || score < FAIR_AT ? "bad" : score >= GOOD_AT ? "good" : "fair";
  return { score, verdict, label: LABEL[verdict], reasons: reasons.slice(0, 4) };
}

// Open-Meteo 응답의 한 지점에서 현재 관측을 꺼낸다(필드가 없으면 null).
type Num = number | null | undefined;
// 숫자이고 물리적으로 말이 되는 범위일 때만 사용한다(공급자 오류값 방어).
const RANGE: Record<string, [number, number]> = {
  temperature_2m: [-60, 60], apparent_temperature: [-80, 70], relative_humidity_2m: [0, 100], wind_speed_10m: [0, 100],
  wind_gusts_10m: [0, 120], wind_direction_10m: [0, 360], precipitation: [0, 500], pm2_5: [0, 2000], pm10: [0, 3000],
};
const n = (v: Num, key = ""): number | null => {
  if (typeof v !== "number" || !Number.isFinite(v)) return null;
  const r = RANGE[key];
  return r && (v < r[0] || v > r[1]) ? null : v;
};

export interface PointWeather {
  current?: Record<string, Num>;
  hourly?: Record<string, Num[] | string[]>;
}

export function observationAt(w: PointWeather | undefined, aq: PointWeather | undefined, hourIndex: number | null = null): Observation {
  const pick = (src: PointWeather | undefined, key: string): number | null => {
    if (!src) return null;
    if (hourIndex === null) return n(src.current?.[key], key);
    const arr = src.hourly?.[key] as Num[] | undefined;
    return arr ? n(arr[hourIndex], key) : null;
  };
  return {
    temp: pick(w, "temperature_2m"), feelsLike: pick(w, "apparent_temperature"), humidity: pick(w, "relative_humidity_2m"),
    windSpeed: pick(w, "wind_speed_10m"), windGust: pick(w, "wind_gusts_10m"), windDir: pick(w, "wind_direction_10m"),
    precip: pick(w, "precipitation"), pm25: pick(aq, "pm2_5"), pm10: pick(aq, "pm10"),
  };
}

export function summarize(ratings: Rating[]): { score: number | null; verdict: Verdict; label: string; counts: Record<Verdict, number>; coverage: string } {
  const counts: Record<Verdict, number> = { good: 0, fair: 0, bad: 0, unknown: 0 };
  for (const r of ratings) counts[r.verdict]++;
  // 판정 불가 지점은 평균·추천에서 제외한다. 판정된 지점이 하나도 없으면 전체도 판정 불가.
  const known = ratings.filter((r): r is Rating & { score: number } => r.score !== null);
  const coverage = `${known.length}/${ratings.length}`;
  if (known.length === 0) return { score: null, verdict: "unknown", label: LABEL.unknown, counts, coverage };
  const score = Math.round(known.reduce((s, r) => s + r.score, 0) / known.length);
  const verdict: Verdict = counts.bad > known.length / 2 ? "bad" : score >= GOOD_AT && counts.bad === 0 ? "good" : score < FAIR_AT ? "bad" : "fair";
  return { score, verdict, label: LABEL[verdict], counts, coverage };
}
