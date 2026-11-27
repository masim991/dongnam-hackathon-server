-- 부울경 러닝 현장 지도: 건널목·신호등 데이터 (Cloudflare D1)
-- 테이블 접두어로 용도를 구분한다: src_ 출처, busan_ 원천 데이터, course 코스, course_ 코스 파생 결과.
-- 적용: npx wrangler d1 execute dongnam-running --remote --file=db/schema.sql

-- 1) 출처 기록: 어떤 공공데이터를 언제 기준으로 가져왔는지
CREATE TABLE IF NOT EXISTS src_dataset (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  provider TEXT,
  url TEXT,
  as_of TEXT,
  license TEXT,
  row_count INTEGER,
  note TEXT
);

-- 2) 원천: 부산 신호등 있는 횡단보도 위치 (신호등 없는 횡단보도는 포함되지 않음)
CREATE TABLE IF NOT EXISTS busan_crosswalk (
  id INTEGER PRIMARY KEY,          -- 원본 순번
  gu TEXT NOT NULL,
  dong TEXT,
  ri TEXT,
  jibun TEXT,
  intersection_name TEXT,          -- 주변 건물명 라벨이며 도로명이 아님
  signal TEXT,                     -- 원본 '신호등존재유무' (현재 전부 '유')
  width_m REAL,
  length_m REAL,
  area_m2 REAL,
  lon REAL NOT NULL,
  lat REAL NOT NULL,
  dataset_id TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_busan_crosswalk_gu_dong ON busan_crosswalk (gu, dong);

-- 3) 코스: 지역·종류로 구분. region/gu로 울산·경남을 나중에 같은 표에 추가
CREATE TABLE IF NOT EXISTS course (
  code TEXT PRIMARY KEY,           -- 예: HD-MOONTAN (HD=해운대)
  name TEXT NOT NULL,
  region TEXT NOT NULL,            -- 부산 / 울산 / 경남
  gu TEXT,
  kind TEXT NOT NULL,              -- trail / riverside / road_proxy(차도로 근사)
  length_m INTEGER,                -- 구성 경로 길이 합(겹침·갈래 포함, 근사)
  way_count INTEGER,
  geometry_source TEXT,
  buffer_m INTEGER,                -- 횡단보도를 코스에 붙인 거리 기준
  matched_points INTEGER,
  unique_crossings INTEGER,        -- 12m 이내 점을 하나로 본 개수
  crossings_per_km REAL,
  note TEXT,
  built_at TEXT
);

CREATE TABLE IF NOT EXISTS course_way (
  course_code TEXT NOT NULL,
  osm_way_id INTEGER NOT NULL,
  name TEXT,
  highway TEXT,
  length_m INTEGER,
  geometry_geojson TEXT NOT NULL,  -- LineString
  PRIMARY KEY (course_code, osm_way_id)
);

-- 4) 파생: 코스와 횡단보도 연결 결과
CREATE TABLE IF NOT EXISTS course_crosswalk (
  course_code TEXT NOT NULL,
  crosswalk_id INTEGER NOT NULL,
  distance_m REAL NOT NULL,
  PRIMARY KEY (course_code, crosswalk_id)
);
CREATE INDEX IF NOT EXISTS idx_course_crosswalk_cw ON course_crosswalk (crosswalk_id);

-- 한 번에 보는 요약
CREATE VIEW IF NOT EXISTS v_course_summary AS
SELECT code, name, region, gu, kind, length_m, matched_points, unique_crossings, crossings_per_km, buffer_m, note
FROM course
ORDER BY region, gu, code;

-- 5) OSM 횡단 노드: 공공 파일에 없는 신호 없는 횡단보도 후보 (지도 기여자 입력값, 완전성 미보증)
CREATE TABLE IF NOT EXISTS osm_crossing (
  osm_id INTEGER PRIMARY KEY,
  kind TEXT NOT NULL,              -- signal / signal_junction / uncontrolled / marked / unmarked / unknown
  name TEXT,
  lon REAL NOT NULL,
  lat REAL NOT NULL,
  dataset_id TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS course_osm_crossing (
  course_code TEXT NOT NULL,
  osm_id INTEGER NOT NULL,
  kind TEXT NOT NULL,
  distance_m REAL NOT NULL,
  PRIMARY KEY (course_code, osm_id)
);
-- 코스별 횡단 요약(출처·종류별). source: busan_signal_crosswalk(공공, 12m 병합) / osm
CREATE TABLE IF NOT EXISTS course_crossing_summary (
  course_code TEXT NOT NULL,
  source TEXT NOT NULL,
  kind TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (course_code, source, kind)
);
-- 지도 영역 조회용 (/api/crosswalks?bbox=...)
CREATE INDEX IF NOT EXISTS idx_busan_crosswalk_latlon ON busan_crosswalk (lat, lon);

-- 6) 표 소유·영역 등록부 (신호등 signal / 경사 slope 등 영역이 섞이지 않게 구분)
CREATE TABLE IF NOT EXISTS data_domain (
  table_name TEXT PRIMARY KEY,
  domain TEXT NOT NULL,            -- common / signal / slope
  owner TEXT NOT NULL,             -- this-repo(이 저장소가 만들고 지울 수 있음) / external(읽기 전용)
  source_id TEXT,                  -- src_dataset.id
  note TEXT,
  registered_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
-- 7) 코스 출입구(다른 길과 이어지지 않은 끝점): 가장 가까운 신호 횡단보도·OSM 횡단 노드까지 거리
CREATE TABLE IF NOT EXISTS course_entrance (
  course_code TEXT NOT NULL,
  seq INTEGER NOT NULL,
  lon REAL NOT NULL,
  lat REAL NOT NULL,
  nearest_signal_m INTEGER,
  nearest_osm_m INTEGER,
  nearest_osm_kind TEXT,
  PRIMARY KEY (course_code, seq)
);
-- 8) 외부 기준 경로(예: 두루누비 해파랑길 GPX)와의 비교 결과. 코스가 기준 선에서 30m 안에 있는 비율 등
CREATE TABLE IF NOT EXISTS course_reference_compare (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  course_code TEXT NOT NULL,
  ref_source TEXT NOT NULL,        -- durunubi 등
  ref_name TEXT,
  ref_length_m INTEGER,
  course_length_m INTEGER,
  share_within_30m REAL,           -- 0~1
  max_dev_m REAL,
  compared_at TEXT NOT NULL
);
-- 9) 신호제어기(교차로 신호등) 위치. 현재 해운대구만 적재. 보행 신호등 개별 위치가 아니라 제어기 단위
CREATE TABLE IF NOT EXISTS busan_signal (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  mgmt_no TEXT,                    -- 원본 관리번호(mgrnu). 드물게 중복·오입력 있음
  control_kind TEXT,               -- 원본 name 필드(표기 흔들림 있음)
  control_kind_norm TEXT,          -- 정규화: 전자신호제어 / 일반신호제어 / 기타
  sigungu TEXT NOT NULL,
  dong TEXT,                       -- 주소에서 뽑은 법정동(도로명이 적힌 주소는 NULL)
  address TEXT,
  place TEXT,                      -- 설치장소
  road TEXT,
  road_type TEXT,                  -- 교차로 / 단일로 / 모뎀 / 가변차로
  is_signal INTEGER NOT NULL DEFAULT 1, -- 0: 모뎀 등 신호기가 아닌 장비
  note TEXT,                       -- 중복 관리번호 등 정제 메모
  installed_on TEXT,
  confirmed_on TEXT,               -- 행의 데이터기준일자(오래된 값일 수 있음)
  lon REAL NOT NULL,
  lat REAL NOT NULL,
  dataset_id TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_busan_signal_latlon ON busan_signal (lat, lon);
CREATE INDEX IF NOT EXISTS idx_busan_signal_sigungu ON busan_signal (sigungu);

-- 10) 데이터 품질 점검 기록(어떤 데이터를 언제 어떻게 확인했는지)
CREATE TABLE IF NOT EXISTS data_check (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  subject TEXT NOT NULL,
  metric TEXT NOT NULL,
  value TEXT NOT NULL,
  note TEXT,
  checked_at TEXT NOT NULL
);
-- 11) 횡단보도 정제: 12m 이내 점을 하나의 건널목으로 묶음(대표 점만 is_primary=1)
CREATE TABLE IF NOT EXISTS crosswalk_cluster (
  crosswalk_id INTEGER PRIMARY KEY,
  cluster_id INTEGER NOT NULL,
  is_primary INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_crosswalk_cluster_cid ON crosswalk_cluster (cluster_id);

-- 12) 횡단보도 ↔ 가장 가까운 신호제어기. matched=1 은 100m 이내
CREATE TABLE IF NOT EXISTS crosswalk_signal_link (
  crosswalk_id INTEGER PRIMARY KEY,
  signal_id INTEGER NOT NULL,
  distance_m REAL NOT NULL,
  matched INTEGER NOT NULL
);

-- 13) 마이그레이션 기록(한 번만 실행되는 구조 변경)
CREATE TABLE IF NOT EXISTS schema_migration (
  id TEXT PRIMARY KEY,
  applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- 14) 해운대 산책로 기준 500m 이내 상세(신호제어기·횡단보도·경사). 정의는 docs/trail-context.md
CREATE TABLE IF NOT EXISTS haeundae_trail (
  id INTEGER PRIMARY KEY,              -- OSM way ID(큐레이션 경로는 -1)
  name TEXT,
  kind TEXT,                           -- path/track/footway/curated/course 등
  source TEXT,                         -- osm / curated / course:코드
  length_m INTEGER,
  geometry_geojson TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS trail_context_signal (
  signal_id INTEGER PRIMARY KEY,
  mgmt_no TEXT, place TEXT, kind TEXT,
  lon REAL NOT NULL, lat REAL NOT NULL,
  distance_m INTEGER NOT NULL,         -- 가장 가까운 산책로 선분까지(500 이하)
  nearest_trail_id INTEGER
);
CREATE TABLE IF NOT EXISTS trail_context_crosswalk (
  crosswalk_id INTEGER PRIMARY KEY,
  name TEXT,
  lon REAL NOT NULL, lat REAL NOT NULL,
  distance_m INTEGER NOT NULL,
  nearest_trail_id INTEGER,
  is_primary INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS trail_context_slope (
  segment_id INTEGER PRIMARY KEY,      -- 외부 경사 표의 segment_id
  start_lon REAL NOT NULL, start_lat REAL NOT NULL, end_lon REAL NOT NULL, end_lat REAL NOT NULL,
  grade_pct REAL NOT NULL, length_m REAL NOT NULL,
  distance_m INTEGER NOT NULL,
  nearest_trail_id INTEGER
);
CREATE INDEX IF NOT EXISTS idx_trail_context_slope_latlon ON trail_context_slope (start_lat, start_lon);