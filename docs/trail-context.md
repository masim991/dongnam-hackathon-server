# 산책로 벡터·500m 주변 정밀화·러닝 날씨

2026-11-27 KST. 담당: Claude. (Android·삼성헬스는 Codex 담당: `docs/android-health-connect.md`)

## 1. 부울경 산책로·산길 벡터 (정적 에셋)

- 원천: OpenStreetMap(ODbL) `highway=path|footway|pedestrian|steps|track|bridleway`, `footway≠sidewalk|crossing|traffic_island`, `access≠private|no`.
- 처리: `node scripts/fetch-trails.mjs`(0.1° 칸 단위 수집, 이어받기) → `node scripts/fetch-boundaries.mjs`(부산·울산·경남 경계) → `node scripts/build-trails.mjs`(경계로 자르기·중복 제거·4m 단순화·20m 미만 제거).
- 결과: `public/data/trails/<위도>_<경도>.json`(0.1° 조각)과 `index.json`(수집 시각·필터·통계·한계). Worker 정적 에셋으로 배포되어 Cloudflare 엣지에서 제공됩니다. D1에는 쓰지 않습니다(무료 일일 쓰기 한도 때문).
- 한계: OSM에 입력된 길만 포함하며 현실의 모든 산책로를 보증하지 않습니다. 이름 없는 길이 대부분이고 종류는 태그 기반 대략값입니다. 수집에 실패한 칸은 누락될 수 있어 `index.json`의 `cellsFetched`와 로그를 확인하세요.
- "벡터화"는 지도용 선형(벡터) 데이터로 해석했습니다. AI 임베딩(Cloudflare Vectorize)과는 무관합니다.

## 2. 해운대 산책로 500m 이내 상세 (신호·횡단·경사)

- 산책로 집합: 큐레이션 코스 + OSM 해운대구의 **이름 있는 길** + **이름 없는 path/track/bridleway(산길)**. 이름 없는 footway·pedestrian·steps(단지 내 보행로 등)는 제외합니다.
- 거리: 점(신호제어기·횡단보도)은 산책로 **선분까지의 최소 거리**, 경사 구간은 양 끝점·중점 3점 중 최소 거리. 500m 이내면 **구간 전체를 보존**하고 절단하지 않습니다.
- 결과(2026-11-27 계산): 산책로 482개, 신호제어기 207/244, 횡단보도 460/503, 경사 구간 12,048/12,677이 500m 이내. 도시 지역이라 대부분이 포함되어 제외되는 양은 적습니다.
- 출력: `public/data/haeundae/trail-context.json`(즉시 사용), `db/pending/20261128_trail_context.sql`(D1 대기). 계산: `node scripts/build-trail-context.mjs`.
- 경사 원본은 다른 세션이 적재한 외부 표(30m 해상도 DEM 기반)라 오차가 크고 최대값은 이상치일 수 있습니다.

## 3. D1 상태와 대기 작업

- 2026-11-27 D1 **무료 일일 쓰기 한도 소진**(code 7500, UTC 자정 = 한국 09:00 초기화). 원인: 전체 재적재 반복과 다른 세션의 경사 적재(3만 행 이상).
- 오늘 못 한 쓰기(내일 순서대로):
  1. `node scripts/db-apply.mjs --pending` — 새 표(`haeundae_trail`, `trail_context_*`) 생성 후 대기 SQL 적용(약 1.3만 행).
  2. (조율 후) `db/pending-manual/20261128_remove_non_haeundae_slope.sql` — 외부 표 `busan_walking_grade10`의 해운대 외 행 삭제. **적재 세션이 멈춘 뒤** 적용. 스냅샷: `db/generated/snapshots/busan_walking_grade10_*.csv`(30,800행).
- 재적재는 전체 삭제·삽입 대신 필요한 표만 하세요. 한도를 다시 넘기면 쓰기가 막힙니다.

## 4. 러닝 날씨 판정 `GET /api/weather?scope=haeundae|region|all`

- 공급자: Open-Meteo(키 불필요, CC BY 4.0). 무료 API는 **비상업 이용 조건**이므로 상용 출시 전 조건 확인이 필요합니다. 기상청 API로 바꾸려면 `src/weather-api.ts`의 공급 부분만 교체하면 됩니다.
- 해운대: 구역 6곳(해변·동백섬·달맞이/문탠로드·센텀/수영강변·송정·반여/장산 자락) 정밀 지점, 나머지 부울경: 시·군·구 대표 좌표(근사).
- 점수(100점에서 감점): 체감온도(적정 5~18°C), 습도(70%↑), 풍속(4m/s↑)·돌풍(12m/s↑), 강수, 초미세먼지·미세먼지. 75점↑ 좋음, 50~74 보통, 50 미만 또는 하드 조건(풍속 12↑·강수 2mm/h↑·체감 33°C↑ 또는 −10°C↓·PM2.5 75↑·PM10 150↑)이면 나쁨. 기준값은 `src/weather.ts` 상수.
- **결측 처리**: 기온(체감)·풍속이 없거나 범위 밖이면 `unknown`(판정 불가)이며 평균·추천에서 제외합니다. 공급자가 지점 수만큼 응답하지 않으면 502, 관측 시각이 3시간 넘게 오래됐거나 없으면 해당 지점은 판정 불가, 전부 판정 불가면 502입니다(Codex가 재현한 "빈 응답이 좋음 100점" 결함을 수정).
- 응답은 15분 캐시, 오류는 `no-store`.