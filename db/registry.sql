-- 표 소유·영역 등록부. 영역(domain): common / signal(횡단보도·신호등·코스) / slope(경사, 외부 적재)
-- 규칙: 시드·초기화는 owner='this-repo' 인 표만 지운다. 다른 영역의 표는 읽기 전용으로 취급한다.
INSERT OR REPLACE INTO data_domain (table_name, domain, owner, source_id, note) VALUES
  ('src_dataset', 'common', 'this-repo', NULL, '출처 기록. 시드는 자기 소유 행(id)만 지움'),
  ('data_domain', 'common', 'this-repo', NULL, '이 등록부'),
  ('busan_crosswalk', 'signal', 'this-repo', 'busan-crosswalk-20250423', '부산 신호등 있는 횡단보도 위치(공공)'),
  ('osm_crossing', 'signal', 'this-repo', 'osm-haeundae-crossings', 'OSM 횡단·신호기 노드(참고)'),
  ('course', 'signal', 'this-repo', NULL, '코스 메타·집계'),
  ('course_way', 'signal', 'this-repo', NULL, '코스 경로 선(GeoJSON)'),
  ('course_crosswalk', 'signal', 'this-repo', NULL, '코스↔공공 횡단보도 매칭'),
  ('course_osm_crossing', 'signal', 'this-repo', NULL, '코스↔OSM 횡단 노드 매칭'),
  ('course_crossing_summary', 'signal', 'this-repo', NULL, '코스별 횡단 요약'),
  ('course_entrance', 'signal', 'this-repo', NULL, '코스 출입구와 가장 가까운 횡단까지 거리'),
  ('course_reference_compare', 'signal', 'this-repo', NULL, '외부 기준 경로(두루누비 등)와의 비교 결과'),
  ('busan_signal', 'signal', 'this-repo', 'busan-signal-ctrl-20250826', '부산 전역 신호제어기 위치(교차로 단위)'),
  ('data_check', 'common', 'this-repo', NULL, '데이터 품질 점검 기록'),
  ('crosswalk_cluster', 'signal', 'this-repo', 'busan-crosswalk-20250423', '12m 이내 횡단보도 점 묶음(고유 건널목)'),
  ('crosswalk_signal_link', 'signal', 'this-repo', NULL, '횡단보도↔가장 가까운 신호제어기'),
  ('schema_migration', 'common', 'this-repo', NULL, '적용된 마이그레이션 기록'),
  ('course_issue', 'signal', 'this-repo', NULL, '코스 선형 정리 기록(회전교차로·끊김·교차 문제)'),
  ('haeundae_trail', 'signal', 'this-repo', NULL, '해운대 산책로(OSM 이름 있는 길·산길·코스)'),
  ('trail_context_signal', 'signal', 'this-repo', 'busan-signal-ctrl-20250826', '산책로 500m 이내 신호제어기'),
  ('trail_context_crosswalk', 'signal', 'this-repo', 'busan-crosswalk-20250423', '산책로 500m 이내 횡단보도'),
  ('trail_context_slope', 'slope', 'this-repo', 'slope-haeundae-grade10-20261127', '산책로 500m 이내 경사 구간(외부 표에서 복사한 부분집합)');
