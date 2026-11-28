# 뛰고보자 | 동남권 이노베이션 해커톤

부산·울산·경남에서 달릴 길을 고르고, 운동을 기록하고, 지도에 사진과 한마디를 남길 수 있는 러닝 앱입니다. CV팀은 지도에 쌓인 후기와 위험 제보가 다음 러너의 코스 선택에 도움이 되도록 만들었습니다.

## 프론트엔드 브랜드 에셋

[![뛰고보자 앱 아이콘과 라이트·다크 모드 워드마크](docs/brand/preview.jpg)](docs/brand/)

[아이콘·로고 원본과 적용 가이드](docs/brand/)

## 팀원 역할

| 팀원 | 담당 |
| --- | --- |
| 곽태원 | Cloudflare Workers API 구축, D1 설계·운영, 횡단보도 등 공공데이터 수집 |
| 고유민 | Android 앱·UX 개발, 기능 통합, 건강 앱 연동·배포 |
| 강희주 |(https://github.com/kanghee-eng) | 프론트엔드 브랜드 디자인, 앱 아이콘·로고 제작, 라이트·다크 에셋 정리 |

<details>
<summary>고유민(앱 개발 및 API 연동)</summary>

- 지도에서 부산·울산·경남의 러닝 코스와 내 위치를 확인할 수 있어요.
- 코스를 누르면 사진과 짧은 소개로 어떤 길인지 미리 볼 수 있어요.
- 출발 전에 경사 그래프를 보고 오르막, 횡단보도, 신호등 위치를 확인할 수 있어요.
- 해운대에서는 내 위치에서 코스 합류점까지 길을 찾고, 이어서 달릴 방향을 고를 수 있어요.
- 완주·거리·시간 중 목표를 정하고, 달리면서 다음 방향과 남은 거리를 볼 수 있어요.
- 달린 경로, 거리, 시간, 페이스가 저장되고, 기록을 목록이나 앨범으로 볼 수 있어요.
- 지도에 사진과 한마디를 남기고, 다른 러너가 올린 버블을 볼 수 있어요.
- 길이 파손됐거나 시설이 고장 난 곳을 사진으로 신고하고, 주변의 제보를 확인할 수 있어요.
- 건강 앱을 연결하면 오늘 걸음 수와 운동 중 평균 심박수를 확인할 수 있어요.
- 운동 기록에 사진을 더해 오운완 카드로 공유할 수 있어요.

</details>

[Android 앱 소스](app/) · [앱 구조도와 수정 기록](app/README.md) · [버전별 패치 내역 및 APK](https://yumin-server.duckdns.org/files/)

## 저장소 구성

| 위치 | 내용 |
| --- | --- |
| [app](app/) | 실제 Android 앱과 지도 모듈 |
| [android/health-connect](android/health-connect/) | 앱에서 사용하는 팀 Health Connect 모듈 |
| [src](src/) · [db](db/) | Cloudflare Workers API와 D1 스키마 |
| [public/map](public/map/) · [public/data](public/data/) | 웹 지도와 공개 코스 데이터 |

<details>
<summary>서버·데이터·웹 지도 문서 펼치기</summary>

아래는 앱을 합치기 전 원본 저장소의 README입니다. 당시의 구현 상태와 검증 기록을 담고 있습니다.

부울경 러닝 지도의 Cloudflare Workers API·D1 스키마·웹 지도와 Android 연동 준비 코드를 관리합니다. 목표는 기존 지도 기능을 유지한 Android 앱입니다. 현재 `android/health-connect` 읽기 전용 라이브러리와 웹 브리지 계약까지 준비돼 있으며, 실행 가능한 `android/app` 호스트와 APK는 아직 없습니다.

Android 작업은 [앱 인계 문서](docs/android-app-handoff.md), [웹 브리지 계약](docs/android-web-bridge.md), [API 명세](docs/openapi.yaml)부터 확인합니다. DB 스냅샷과 원시 데이터를 앱에 포함하지 않고 Worker의 공개 읽기 API를 사용합니다. Android SDK 설치·호스트 구현·기기 연동·출시 검증은 남아 있습니다.

기능은 `src/index.ts`(필요하면 `src/` 하위로 분리)에 추가하고 테스트는 `test/`에, DB·KV 같은 바인딩은 `wrangler.jsonc`에 추가합니다.

## 로컬 실행

Node.js 22.12 이상이 필요합니다.

```powershell
npm ci
npm run dev
```

서버에 표시된 주소의 `/api/health`에서 `{"ok":true,"service":"dongnam-hackathon"}` 응답을 확인합니다. 개발 서버는 로컬 컴퓨터에서만 접근합니다.

## 검증

```powershell
npm run typecheck
npm test
npm run build
```

`build`는 Cloudflare에 업로드하지 않는 배포 사전 검사입니다.

## Claude Code 연결

이 폴더에서 `claude`를 실행해 프로젝트를 확인하고 `.mcp.json` 서버를 승인합니다. 이후 다음 명령으로 Cloudflare OAuth 로그인을 시작합니다.

```powershell
claude mcp login cloudflare
claude mcp list
```

브라우저의 계정 및 권한을 확인하고 직접 승인합니다. 문서 MCP는 `cloudflare-docs`, 계정 API MCP는 `cloudflare`입니다. MCP 로그인과 Wrangler 로그인은 별개입니다.

## Cloudflare 배포 준비

```powershell
npm run cf:login
npm run cf:whoami
```

`cf:login`은 계정 조회와 Workers 스크립트·경로·로그 권한만 요청합니다. DB, 스토리지, 결제 권한은 요청하지 않습니다. 해당 기능을 추가할 때 필요한 권한으로 다시 로그인합니다.
`cf:whoami`에 다른 OAuth 권한이 없다는 경고가 나올 수 있습니다. 요청 범위를 좁힌 결과이며, 현재 로그인 상태와 실제 배포 성공 여부는 구분해서 확인합니다.

배포 전에 `wrangler.jsonc`의 Worker 이름과 대상 계정을 확인합니다. 배포 명령은 `npm run deploy`이며 공개 배포가 필요할 때 실행합니다. 현재 설정은 리소스나 유료 구독을 생성하지 않습니다.

로컬 비밀값은 `.dev.vars.example`을 `.dev.vars`로 복사한 뒤 입력합니다. 운영 비밀값은 `npx wrangler secret put 이름`으로 등록하고 소스에 작성하지 않습니다.

## 배포 현황

- 주소: https://dongnam-hackathon.magabus.workers.dev (Worker `dongnam-hackathon`, 계정 ID `dfe9c409…`)
- 재배포: `npm run typecheck`, `npm test`, `npm run build`를 통과한 뒤 `npm run deploy`
- 계정: 사용자 Cloudflare 계정(ID `dfe9c409…`). CLI와 MCP가 같은 계정(계정 1개)으로 확인됨
- 도메인: 계정 공통 workers.dev 서브도메인 `magabus`가 이미 등록되어 있어 변경하지 않았습니다. 연결된 커스텀 도메인(zone)은 없으며, 해커톤 기본 주소는 위 workers.dev HTTPS 주소입니다. 커스텀 도메인을 쓰려면 사용자가 도메인과 연결할 호스트명을 지정한 뒤 진행합니다.
- 2026-11-27 11:56(KST) 검증(외부 HTTPS): `GET /`와 `GET /api/health` 200·`application/json`·`{"ok":true,"service":"dongnam-hackathon"}`, `HEAD` 200·본문 없음, 없는 경로 404 `{"error":"Not found"}`, `POST` 405 `{"error":"Method not allowed"}`(`Allow: GET, HEAD`)
- 남은 문제: 현재 Wrangler 로그인은 `cf:login`보다 넓은 권한(D1, Pages, AI 등)으로 승인된 상태입니다. 권한을 줄이려면 `npm run cf:login`으로 다시 로그인합니다.
- 상세 결과: `CLOUDFLARE_SETUP_STATUS.md`

## 파일

- `src/index.ts`: API 진입점과 상태 확인
- `test/worker.test.ts`: 정상·오류 응답 검사
- `wrangler.jsonc`: Worker 설정
- `.mcp.json`: 프로젝트 전용 Claude MCP 서버
- `CLAUDE.md`: Claude Code 작업 안내

## 공식 문서

- [Cloudflare MCP](https://github.com/cloudflare/mcp-server-cloudflare)
- [Wrangler 설정](https://developers.cloudflare.com/workers/wrangler/configuration/)
- [Claude Code MCP](https://code.claude.com/docs/en/mcp)

## 데이터 저장소 (Cloudflare D1: dongnam-running)

- 계정 `dfe9c409…`의 D1 `dongnam-running`(ID `1d725a97-b44e-4b81-b921-0d638bb9d813`, APAC). `wrangler.jsonc`에 `DB` 바인딩만 추가했고 Worker 코드는 아직 읽지 않습니다. 이 프로젝트 전용이며 다른 프로젝트의 `hackathon-db`·`CACHE`와 무관합니다.
- 구분: `src_dataset`(출처) · `busan_crosswalk`(원천, 3,812행) · `course`/`course_way`(코스, 5개) · `course_crosswalk`(코스↔횡단보도, 42행) · 뷰 `v_course_summary`.
- 원천: [부산광역시_신호등 설치 횡단보도 위치정보](https://www.data.go.kr/data/3072152/fileData.do)(2025-04-23 기준, 신호등 있는 횡단보도만, 원본 3,815행 중 좌표 없는 3행 제외). 코스 선형은 OpenStreetMap(ODbL).
- 재생성·재적재: `node scripts/build-haeundae-courses.mjs` → `npx wrangler d1 execute dongnam-running --remote --file=db/generated/seed.sql --yes` (스키마는 `db/schema.sql`, 시드 SQL은 Git 제외)
- 조회 예: `npx wrangler d1 execute dongnam-running --remote --command "SELECT * FROM v_course_summary"`
- 한계: 코스 길이는 OSM 선형 합(겹침·갈래 포함), 매칭 기준 20m, 신호 없는 횡단보도는 집계되지 않음. 해운대 해변 산책로와 동백섬은 OSM 선형을 확정하지 못해 아직 코스가 없고, `HD-BEACHROAD`는 차도 기준 근사입니다.

### 지도·API 연결 (2026-11-27 배포)

- Worker가 D1을 **읽기 전용**으로 조회합니다(위 "Worker 코드는 아직 읽지 않습니다"는 이 배포로 바뀌었습니다). 모두 `GET`/`HEAD`만 허용하고 그 외는 405입니다.
  - `GET /api/courses`: 코스 목록과 출처·종류별 횡단 집계
  - `GET /api/courses/{코드}/geojson`: 코스 선, 코스 위 공공 신호 횡단보도, OSM 횡단 노드 (예: `HD-BEACHPROM`)
  - `GET /api/crosswalks?bbox=서,남,동,북`: 신호등 있는 횡단보도 점(폭·높이 0.05도 이하, 최대 1,500개)
- 지도: `/map/`(MapLibre)에 코스 목록 패널을 추가했습니다. 코스를 누르면 선과 횡단보도가 표시되고, 15단계 이상 확대하면 화면 안 신호 횡단보도가 보입니다. 좁은 화면에서는 패널이 아래쪽 시트로 바뀝니다.
- 코스(8개): `HD-BEACHPROM`(해변 산책로 약 1.0km), `HD-BEACHPROM-EXT`(미포 연장, 17.8m 틈), `HD-DONGBAEK`(동백섬, 초안), `HD-MOONTAN`, `HD-SUYEONG`, `HD-GALMAET`, `HD-SONGJEONG`, `HD-BEACHROAD`(차도 기준 근사). 추가 코스는 `scripts/extra-courses.json`에 OSM way ID로 적고 `node scripts/build-haeundae-courses.mjs` 후 시드를 다시 적재합니다.
- 추가 표: `osm_crossing`·`course_osm_crossing`·`course_crossing_summary`(OSM 횡단 노드, 무신호 후보). OSM의 신호 표기는 불완전해(예: 해운대해변로 공공 36곳 대 OSM 신호 8곳) 신호 횟수는 공공 파일 기준입니다.
- D1 무료 한도(일일 읽기·쓰기 행 수)는 확인하지 못했습니다. `/api/crosswalks`는 위도 인덱스를 쓰고 상한을 두었지만, 트래픽이 늘면 대시보드에서 사용량을 확인하세요.

### D1 영역 구분과 안정화 (2026-11-27)

- 같은 D1에 **다른 세션이 적재한 경사 표**(`haeundae_walking_grade10_20261127`, 12,677행)가 있었고, 이름 규칙·출처 기록이 신호등 표와 달라 엉킬 위험이 있었습니다. 경사 표 자체는 읽기·이름 변경·삭제를 하지 않았습니다(행 수 12,677 유지 확인). 대신 다음처럼 분리했습니다.
  - `data_domain`: 모든 표의 영역(`common`/`signal`/`slope`)과 소유(`this-repo`/`external`)를 등록. 등록 안 된 표는 점검에서 실패합니다.
  - 경사는 `v_slope_grade10` 뷰로만 쓰고 출처를 `src_dataset`에 등록했습니다(30m 해상도 DEM을 28m 이하 구간에 적용해 오차가 크고 최대 85.7%는 이상치 가능 → 참고용).
  - 시드는 자기 소유 표와 자기 출처 행만 지웁니다(이전에는 `src_dataset` 전체를 지웠음).
  - 신호등과 경사는 키가 없고 조인하지 않습니다.
- 명령: `npm run db:apply -- --local --dry-run`(읽기 전용 계획) · `npm run db:apply -- --local --apply`(스키마·등록부 적용) · `npm run db:check -- --local`(무결성 점검) · `npm run db:build`(시드 SQL 생성). 원격 대상은 `--remote`로 명시합니다. 실제 쓰기는 `--apply`가 있어야 합니다. 시드 직접 적재는 이 해시·재개 보호를 거치지 않으므로 백업과 로컬 검증이 먼저입니다. [추가 데이터·10만 행 대응 절차](docs/db-growth.md).

### 코스 현황 (9개)

- `HD-DONGBAEK` 동백섬 순환: OSM 보행로 23개 way를 최단 순환으로 잇고 틈 4곳(9·34·11·14m, 합 68m)을 직선으로 이은 폐곡선 v1(`data/curated/dongbaek_loop.geojson`, 약 1.61km, 자기교차 없음). **현장 미검증**이며 서쪽 일부는 해안이 아닌 섬 안쪽 길입니다. 알려진 길이(순환 0.93km + 해안 0.45km, 상업 사이트 수치)보다 약 16% 깁니다.
- `HD-DALMAJI` 달맞이길(차도 기준, 길이 약 6.3km는 way 합): 공공 신호 횡단보도 11곳, OSM 무신호 횡단 후보 23(+무표시 2, 미분류 3).
- `HD-MOONTAN` 문탠로드: 코스 위 신호 횡단보도 0. **출입구 3곳에서 가장 가까운 공공 신호 횡단보도는 486~668m**, OSM 횡단 노드는 155m(무신호)·300m(무표시)·462m(미분류)입니다. 숲길에 들어가려면 달맞이길의 무신호 횡단을 건너야 할 가능성이 큽니다(OSM 기여자 입력 기준, 현장 확인 필요).
- 코스 출입구(다른 길과 이어지지 않은 끝점)는 `course_entrance`에 저장하고 API `geojson`의 `entrance` 레이어, 지도의 흰 원으로 보입니다.

### 두루누비 해파랑길 대조 (인증키 필요)

1. https://www.data.go.kr/data/15101974/openapi.do 에서 활용신청(개발계정은 자동승인)
2. 프로젝트 루트 `.dev.vars`에 `DURUNUBI_SERVICE_KEY=발급키` 저장(Git 제외 파일)
3. `node scripts/durunubi-compare.mjs --save` → 해파랑길 GPX와 해운대 코스를 비교해 30m 이내 비율·최대 이탈을 `course_reference_compare`에 저장
- 키 없이 `--selftest`로 비교 함수는 시험했습니다(통과). **오퍼레이션 경로(`routeList`/`courseList`)와 응답 필드(`gpxpath` 등)는 명세서를 열람하지 못해 가정한 값**입니다. 실패하면 응답 키 목록이 출력되므로 `FIELD` 상수를 고치면 됩니다.

### 산책로 벡터·날씨·500m 정밀화 (2026-11-27)

자세한 규칙·한계는 [docs/trail-context.md](docs/trail-context.md). 요약:
- `public/data/trails/`: 부울경 OSM 산책로·산길 벡터 조각(수집·변환은 `scripts/fetch-trails.mjs`, `fetch-boundaries.mjs`, `build-trails.mjs`). 수집이 오래 걸려 `index.json`의 통계로 완료 여부를 확인합니다.
- `npm run deploy`는 먼저 `npm run data:check`를 실행합니다. 산책로가 없거나 부분 수집·해시 불일치가 있으면 배포를 중단합니다. `npm run build`는 배포하지 않는 Worker 빌드 확인이며 데이터 완성 여부와 별개입니다.
- `public/data/haeundae/trail-context.json`: 해운대 산책로 선분 기준 500m 이내 신호제어기·횡단보도·경사 구간.
- `GET /api/weather`: 해운대 구역별 러닝 날씨 판정(Open-Meteo). 빈·오래된 응답은 판정 불가로 제외.
- D1 새 표(`haeundae_trail`, `trail_context_*`)는 기존 적재 완료 상태입니다. 이후에는 `npm run db:apply -- --remote --pending --dry-run`으로 계획부터 확인하고, 새 버전 SQL·SHA256 검사·명시적 적용 절차를 따릅니다([docs/db-growth.md](docs/db-growth.md)).
- Android·삼성헬스(Health Connect 읽기 전용 모듈)는 Codex가 `android/`, `docs/android-health-connect.md`에서 진행 중입니다.

</details>

## 앱 배포 QR

| 최신 버전 받기 | 버전 선택 |
| :---: | :---: |
| [<img src="app/docs/latest-qr.png" width="148" alt="최신 Android APK 다운로드 QR">](https://yumin-server.duckdns.org/files/eudero-gano.apk) | [<img src="app/docs/download-qr.png" width="148" alt="Android APK 버전 선택 QR">](https://yumin-server.duckdns.org/files/) |
