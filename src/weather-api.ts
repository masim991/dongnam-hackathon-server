// GET /api/weather?scope=haeundae|region|all — Open-Meteo(키 불필요)로 지역별 날씨를 받아 러닝 지수로 판정한다.
import { HAEUNDAE_AREAS, REGION_AREAS, type Area } from "./areas.ts";
import { observationAt, rate, summarize, windDirLabel, type PointWeather, type Rating } from "./weather.ts";

const FORECAST = "https://api.open-meteo.com/v1/forecast";
const AIR = "https://air-quality-api.open-meteo.com/v1/air-quality";
const WX_FIELDS = "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,wind_speed_10m,wind_direction_10m,wind_gusts_10m";
const AQ_FIELDS = "pm10,pm2_5";
const TTL_SECONDS = 900;
const SCOPES = new Set(["haeundae", "region", "all"]);

type Json = PointWeather & { current?: Record<string, number | string | null>; hourly?: Record<string, (number | string | null)[]> };

async function upstream(url: string): Promise<Json[]> {
  // Cloudflare Workers의 캐시(없는 환경에서는 건너뜀)로 같은 요청을 15분간 재사용해 외부 API 호출을 줄인다.
  const cache = (globalThis as unknown as { caches?: { default?: { match(r: Request): Promise<Response | undefined>; put(r: Request, res: Response): Promise<void> } } }).caches?.default;
  const key = new Request(url);
  const hit = cache ? await cache.match(key) : undefined;
  const res = hit ?? await fetch(url, { signal: AbortSignal.timeout(8000), headers: { "user-agent": "dongnam-running/1.0" } });
  if (!res.ok) throw new Error("upstream " + res.status);
  if (!hit && cache) {
    const copy = new Response(res.clone().body, res);
    copy.headers.set("Cache-Control", `public, max-age=${TTL_SECONDS}`);
    await cache.put(key, copy);
  }
  const body = await res.json() as Json | Json[];
  return Array.isArray(body) ? body : [body];
}

const q = (areas: Area[]) => `latitude=${areas.map((a) => a.lat).join(",")}&longitude=${areas.map((a) => a.lon).join(",")}`;

async function load(areas: Area[], withHourly: boolean) {
  const hourly = withHourly ? `&hourly=${WX_FIELDS}` : "";
  const aqHourly = withHourly ? `&hourly=${AQ_FIELDS}` : "";
  const [wx, aq] = await Promise.all([
    upstream(`${FORECAST}?${q(areas)}&current=${WX_FIELDS}${hourly}&wind_speed_unit=ms&timezone=Asia%2FSeoul&forecast_days=2`),
    upstream(`${AIR}?${q(areas)}&current=${AQ_FIELDS}${aqHourly}&timezone=Asia%2FSeoul&forecast_days=2`).catch(() => [] as Json[]), // 미세먼지 실패는 날씨 판정에서 제외
  ]);
  // 공급자가 지점 수만큼 돌려주지 않으면 어긋난 값을 지역에 붙일 위험이 있어 통째로 실패 처리한다(502).
  if (wx.length !== areas.length) throw new Error("weather count mismatch");
  const aqOk = aq.length === areas.length;
  return areas.map((a, i) => describe(a, wx[i], aqOk ? aq[i] : undefined, withHourly));
}

const MAX_AGE_MS = 3 * 3600_000;
// current.time 은 Asia/Seoul 현지시각(오프셋 없음). 없거나 너무 오래됐거나 미래면 관측을 쓰지 않는다.
function freshness(time: unknown, now = Date.now()): "ok" | "missing" | "stale" {
  if (typeof time !== "string") return "missing";
  const t = Date.parse(`${time.length === 16 ? time + ":00" : time}+09:00`);
  if (!Number.isFinite(t)) return "missing";
  return now - t > MAX_AGE_MS || t - now > 3600_000 ? "stale" : "ok";
}

function describe(area: Area, wx: Json | undefined, aq: Json | undefined, withHourly: boolean) {
  const fresh = freshness(wx?.current?.time);
  const usable = fresh === "ok" ? wx : undefined;
  const obs = observationAt(usable, aq);
  const rating = rate(obs);
  if (rating.verdict === "unknown" && fresh !== "ok") rating.reasons = [fresh === "stale" ? "관측 시각이 오래되어 판정하지 않습니다" : "관측 시각이 없어 판정하지 않습니다"];
  const out: Record<string, unknown> = {
    id: area.id, name: area.name, group: area.group, lat: area.lat, lon: area.lon,
    observedAt: wx?.current?.time ?? null,
    airQualityAvailable: obs.pm25 !== null || obs.pm10 !== null,
    current: {
      tempC: obs.temp, feelsLikeC: obs.feelsLike, humidityPct: obs.humidity, windMs: obs.windSpeed, windGustMs: obs.windGust,
      windFromDeg: obs.windDir, windFrom: windDirLabel(obs.windDir), precipMm: obs.precip, pm25: obs.pm25, pm10: obs.pm10,
    },
    rating,
  };
  if (withHourly && usable?.hourly?.time) {
    const times = usable.hourly!.time as string[];
    const from = String(usable.current?.time ?? "").slice(0, 13) + ":00";
    const start = Math.max(0, times.findIndex((t) => t >= from));
    const hours: { time: string; score: number | null; verdict: Rating["verdict"]; label: string }[] = [];
    for (let i = start; i < Math.min(times.length, start + 24); i++) {
      const r = rate(observationAt(usable, aq, i));
      hours.push({ time: times[i], score: r.score, verdict: r.verdict, label: r.label });
    }
    out.bestHours = hours.filter((h) => h.verdict === "good" || h.verdict === "fair").sort((a, b) => (b.score ?? 0) - (a.score ?? 0) || (a.time < b.time ? -1 : 1)).slice(0, 3);
    out.nextHours = hours.filter((_, i) => i % 3 === 0).slice(0, 8); // 3시간 간격 요약
  }
  return out as { rating: Rating; group: string } & Record<string, unknown>;
}

export async function handleWeather(url: URL): Promise<Response> {
  const scope = url.searchParams.get("scope") ?? "haeundae";
  if (!SCOPES.has(scope)) return Response.json({ error: "scope must be haeundae, region or all" }, { status: 400, headers: { "Cache-Control": "no-store" } });
  try {
    const wantHd = scope === "haeundae" || scope === "all";
    const wantRegion = scope === "region" || scope === "all";
    const [hd, region] = await Promise.all([wantHd ? load(HAEUNDAE_AREAS, true) : [], wantRegion ? load(REGION_AREAS, false) : []]);
    if (![...hd, ...region].some((a) => a.rating.verdict !== "unknown")) throw new Error("no usable observations");
    const summary: Record<string, unknown> = {};
    if (hd.length) summary["해운대"] = summarize(hd.map((a) => a.rating));
    for (const g of ["부산", "울산", "경남"] as const) {
      const part = region.filter((a) => a.group === g);
      if (part.length) summary[g] = summarize(part.map((a) => a.rating));
    }
    return Response.json({
      generatedAt: new Date().toISOString(),
      source: "Open-Meteo (https://open-meteo.com, CC BY 4.0)",
      notice: "러닝 지수는 참고용 경험 규칙입니다. 관측값이 없는 지점은 판정 불가로 두고 평균·추천에서 제외합니다. 해운대는 구역별 정밀 지점, 나머지 부울경은 시·군·구 대표 좌표(근사)입니다. 미세먼지는 모델값으로 측정소 값과 다를 수 있습니다. 무료 API는 비상업 용도 조건이므로 상용 출시 전 이용 조건을 확인하세요.",
      scope, summary, areas: [...hd, ...region],
    }, { headers: { "Cache-Control": `public, max-age=${TTL_SECONDS / 2}` } });
  } catch {
    return Response.json({ error: "Weather provider unavailable" }, { status: 502, headers: { "Cache-Control": "no-store" } });
  }
}
