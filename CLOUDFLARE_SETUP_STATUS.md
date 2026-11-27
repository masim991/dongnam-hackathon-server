# Cloudflare 기본 환경 상태

검증 시각: 2026-11-27 11:56 (KST)

## 단계
- 전체 목적은 해커톤 서비스 개발입니다. 이 문서는 **개발 착수 전 Cloudflare 서버 기반 준비 단계**의 결과이며, 이 단계는 완료됐습니다.
- 검증된 Workers·HTTPS·배포 환경이 후속 서비스 개발의 출발점입니다. 기능은 `src/index.ts`, 테스트는 `test/`, 바인딩은 `wrangler.jsonc`에 추가합니다.
- 서비스 요구(기능·DB 스키마·인증·프론트)가 정해지면 그에 맞춰 구현합니다. 현재는 추측으로 구현하지 않았습니다.

## 요약
- Worker `dongnam-hackathon`이 사용자 계정에 배포되어 있고 외부 HTTPS 응답이 정상입니다.
- 이번 작업에서 Worker 코드·설정은 바꾸지 않았고 재배포하지 않았습니다. 변경한 문서는 README.md, CLAUDE.md와 이 파일입니다.
- Git 커밋·푸시는 하지 않았습니다(첫 커밋 없음).

## 사실 (직접 확인)
| 항목 | 결과 |
|---|---|
| 대상 계정 | 사용자 Cloudflare 계정, ID `dfe9c40978c7cc88bae59830f95dd3cc` |
| CLI(Wrangler) 계정 | 위 계정과 일치 (`wrangler whoami`) |
| MCP 계정 | 위 계정과 일치. MCP 인증 사용자와 일치, 접근 가능 계정은 1개 (`GET /accounts`) |
| workers.dev 서브도메인 | `magabus` (이미 등록됨, 변경하지 않음) |
| 커스텀 도메인(zone) 조회 | 성공. 계정에 zone 0개 |
| Worker 목록 | `dongnam-hackathon` 1개 (수정 2026-11-27 02:52 UTC). 이 Worker의 workers.dev 활성화됨 |
| Git 제외 | `.env*`, `.dev.vars*`(예제 제외), `.wrangler/`, `dist/`, `node_modules/`, `*.log` 제외됨 |
| 비밀값 | `.dev.vars.example`은 주석뿐이며 실제 값 없음 |

## HTTPS 응답 검증 (https://dongnam-hackathon.magabus.workers.dev)
| 요청 | 상태 | Content-Type | 본문·헤더 |
|---|---|---|---|
| GET / | 200 | application/json | `{"ok":true,"service":"dongnam-hackathon"}`, Cache-Control: no-store |
| GET /api/health | 200 | application/json | 위와 동일 |
| HEAD /api/health | 200 | application/json | 본문 없음 (길이 0) |
| GET /nope, GET /api/health/x | 404 | application/json | `{"error":"Not found"}` |
| POST /api/health, POST / | 405 | application/json | `{"error":"Method not allowed"}`, `Allow: GET, HEAD` |

## 명령
- 로컬: `npm ci`, `npm run dev` (127.0.0.1)
- 검사: `npm run typecheck`, `npm test`, `npm run build`(dry-run)
- 재배포: 검사 통과 후 `npm run deploy`

## 미확인
- 이 세션에서 `npm run typecheck`, `npm test`, `npm run build`를 다시 실행하지 않았습니다(이전 실행 때 통과, 이후 코드 변경 없음).
- 로컬 서버(8788) 현재 실행 여부는 확인하지 않았습니다.

## 남은 항목
- 커스텀 도메인: 계정에 zone이 없습니다. 쓰려면 도메인과 연결할 호스트명을 지정하고 추가 승인이 필요합니다(도메인 구매·이전은 이번 범위 밖).
- Wrangler OAuth 권한이 `cf:login`이 요청하는 범위보다 넓습니다(D1, Pages, AI, SSL 등 포함). 줄이려면 `npm run cf:login`으로 재로그인합니다.
- 같은 계정의 다른 리소스(D1 `hackathon-db`, KV `CACHE` 등)는 이번에 조회·변경하지 않았습니다.

## 추가 승인 필요 여부
- 현재 기본 환경에는 필요 없음.
- 커스텀 도메인 연결, DB·스토리지·인증 추가 시 별도 승인 필요.

## 추가: 건널목·신호등 데이터 저장 (2026-11-27)
- 계정 `dfe9c409…`에 D1 `dongnam-running`을 새로 만들고 스키마·시드를 적재했습니다(원격 조회로 행 수 확인: 횡단보도 3,812, 코스 5, 코스 구성 경로 62, 코스↔횡단보도 42).
- 변경 파일: `wrangler.jsonc`(D1 바인딩 추가), `.gitignore`(`db/generated/`), `db/schema.sql`, `scripts/build-haeundae-courses.mjs`, `data/raw/*`, README.md, CLAUDE.md.
- Worker는 재배포하지 않았습니다. 배포된 Worker에는 아직 이 바인딩이 없습니다.
- 기존 `hackathon-db`(D1)와 `CACHE`(KV)는 다른 프로젝트 리소스라 변경하지 않았습니다.

## 추가: 지도·API 배포 (2026-11-27)
- Worker `dongnam-hackathon`을 재배포했습니다(버전 `9fec058b…`). D1 바인딩 `DB`(dongnam-running)가 연결되었고 `/api/courses`, `/api/courses/{코드}/geojson`, `/api/crosswalks`가 공개 HTTPS에서 동작합니다.
- 검증: 200 응답(health·courses·geojson·bbox), 잘못된 코드 400, 없는 코스 404, 너무 넓은 bbox 400, `POST` 405(`Allow: GET, HEAD`), `HEAD` 200, `/map/` 200. 타입 검사와 테스트 7개 통과.
- 변경 파일: `src/index.ts`, `src/courses.ts`(신규), `tsconfig.json`(`allowImportingTsExtensions`), `test/worker.test.ts`(테스트 4개 추가), `public/map/index.html`(코스 패널·레이어 추가), `db/schema.sql`, `scripts/*`.
- 미확인: D1 무료 한도, 실제 모바일 단말에서의 화면. 커밋·푸시는 하지 않았습니다.

## 추가: D1 안정화·동백섬·달맞이길 (2026-11-27)
- 경사 표(외부 적재)와 신호등 표를 영역별로 등록·분리하고 점검 스크립트를 추가했습니다(`db:check` 11/11 통과, 경사 표 12,677행 유지).
- 동백섬 순환 코스(폐곡선 v1)와 달맞이길 차도 코스를 추가하고 출입구 분석을 D1에 저장했습니다. API·지도를 재배포했습니다(버전 `f29f05c6…`, 코스 9개 응답 확인).
- 두루누비 비교는 인증키가 없어 실행하지 못했습니다(비교 함수는 자체 시험 통과).
- 한계: 동백섬은 현장 미검증, 달맞이길 길이는 way 합(겹침 포함), OSM 무신호 횡단은 기여자 입력값.

## 추가: 산책로 벡터·날씨·정밀화 (2026-11-27)
- D1 무료 일일 쓰기 한도 소진(code 7500)으로 D1 쓰기를 중단했습니다. 해운대 외 데이터 정리·새 표 적재는 대기 SQL로 준비(`db/pending`, `db/pending-manual`).
- 산책로 벡터는 D1이 아니라 Worker 정적 에셋(`public/data/trails`)으로 제공합니다. 수집 진행 상태는 `public/data/trails/index.json` 참고.
- `/api/weather` 추가(Open-Meteo). 결측·오래된 응답은 판정 불가로 처리. 재배포 전이면 이 항목은 로컬 코드 상태입니다.