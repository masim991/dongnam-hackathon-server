-- 경사 영역 등록. haeundae_walking_grade10_20261127 표가 이미 있을 때만 적용한다(scripts/db-apply.mjs가 확인).
-- 이 표는 다른 세션이 적재한 외부 데이터이며 여기서 만들거나 지우거나 바꾸지 않는다.
-- 신호등(signal) 표와는 키가 없고 조인하지 않는다. 앱·API는 안정된 뷰 이름(v_slope_grade10)만 사용한다.
INSERT OR REPLACE INTO src_dataset (id, name, provider, url, as_of, license, row_count, note) VALUES (
  'slope-haeundae-grade10-20261127',
  '해운대구 보행 구간 경사 10% 이상',
  'OpenStreetMap contributors + Copernicus DEM GLO-30 (다른 세션이 가공·적재)',
  NULL, '2026-11-27', 'OSM ODbL; Copernicus DEM 이용조건·표기는 확인 필요', NULL,
  '짧은 구간(28m 이하)에 30m 해상도 DEM을 적용한 값이라 경사 오차가 큼. 최대 85.7%는 이상치 가능. grade_pct는 부호 있는 값(오르막 +, 내리막 -). 확정 지표가 아니라 참고용.'
);
INSERT OR REPLACE INTO data_domain (table_name, domain, owner, source_id, note) VALUES (
  'haeundae_walking_grade10_20261127', 'slope', 'external', 'slope-haeundae-grade10-20261127',
  '읽기 전용. 이름 변경·삭제 금지(적재한 세션이 이 이름을 쓸 수 있음). 사용은 v_slope_grade10 뷰로.'
);
CREATE VIEW IF NOT EXISTS v_slope_grade10 AS
SELECT segment_id, start_lat, start_lon, end_lat, end_lon, grade_pct, length_m
FROM haeundae_walking_grade10_20261127;
