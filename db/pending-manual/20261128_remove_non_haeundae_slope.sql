-- 수동 적용 전용(자동 실행 안 됨). 다른 세션이 적재 중인 외부 표 busan_walking_grade10 에서 해운대가 아닌 행을 지운다.
-- 적용 전 조건: (1) 그 표를 적재하는 세션이 멈췄거나 끝났음, (2) 스냅샷 확보
--   node scripts/snapshot-external.mjs  → db/generated/snapshots/busan_walking_grade10_*.csv (2026-11-27 스냅샷: 30,800행)
-- 2026-11-27 D1 무료 쓰기 한도(code 7500) 소진으로 실행하지 못함. UTC 자정(한국 09시) 이후 가능.
-- 실행: npx wrangler d1 execute dongnam-running --remote --file=db/pending-manual/20261128_remove_non_haeundae_slope.sql --yes
DELETE FROM busan_walking_grade10 WHERE district NOT LIKE '해운대%';