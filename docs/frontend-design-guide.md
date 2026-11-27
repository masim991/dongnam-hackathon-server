# 지도 프론트 디자인 적재 가이드

지도 화면(`/map/`)은 **마크업·모양·지도 색·로직**이 네 파일로 나뉘어 있어서, 디자인을 바꿀 때 로직을 건드릴 필요가 없습니다.

| 파일 | 역할 | 디자이너가 바꾸는 것 |
|---|---|---|
| `public/map/index.html` | 마크업만 | 구조·문구·순서, class 추가 |
| `public/map/styles.css` | 모양(디자인 토큰 + 컴포넌트 클래스) | 색·글자·간격·반경·그림자, 패널 위치 |
| `public/map/theme.js` | **지도 레이어 색**과 지도 설정(타일 주소·시작 위치) | 선·점 색, 경사 색 단계, 타일 서버 |
| `public/map/app.js` | 동작(API 호출, 레이어, 분석, 브리지) | 보통 건드리지 않음 |

## 1. 가장 쉬운 변경
- **전체 색·글자·간격**: `styles.css` 맨 위 `:root`의 변수(`--accent`, `--surface`, `--radius`, `--font`, `--fs-*`, `--space-*`, `--panel-width`)만 바꾸면 패널 전체가 바뀝니다. 시험용 어두운 값은 `<html data-theme="dark">`로 켭니다(예시 값).
- **지도 위 색**(코스 선, 횡단보도 점, 경사 색 단계, 산책로 종류별 색): `theme.js`의 `THEME`. 같은 값이 CSS 변수 `--c-이름`으로도 내보내져서 범례 색이 항상 지도와 일치합니다.
- **타일 서버·시작 위치**: `theme.js`의 `CONFIG`.

## 2. 마크업 규칙
- **`id`는 app.js가 찾는 이름이므로 바꾸지 마세요**(`map`, `panel`, `list`, `info`, `detail`, `coursebox`, `wx`, `wxSummary`, `wxList`, `layers`, `lyTrails`, `lySlope`, `lyIssues`, `myrun`, `gpxFile`, `gpxClear`, `gpxInfo`, `trimEnds`, `health`, `healthConnect`, `healthRead`, `healthInfo`). 모양은 `class`로만 조정합니다.
- 사용 중인 클래스: `.panel`(카드), `.section`(접이식 구역), `.btn`·`.btn-inline`(버튼), `.info`·`.note`·`.detail`, `.layer-option`, `.wx-summary`·`.wx-list`, `.v-good/.v-fair/.v-bad/.v-unknown`(날씨 판정 글자색), `.legend`·`.sw`·`.sw-*`(범례).
- 새 구역을 넣으려면 `<details class="section"><summary>제목</summary>…</details>`를 `#panel` 안에 추가하고 스타일은 `.section`을 그대로 씁니다. 동작이 필요하면 app.js에 해당 id를 찾는 코드를 추가합니다.
- 좁은 화면(≤600px)에서는 패널이 아래쪽 시트로 바뀝니다(`styles.css` 하단 `@media`).

## 3. 지도 레이어 id (app.js의 `initCourses`)
`course-line`(코스), `course-bridge`(점선 연결선), `course-issue`(선형 문제 마커), `course-signal`(코스 위 신호 횡단보도), `course-osm`(OSM 횡단), `course-entrance`(출입구), `course-slope`(코스 주변 경사), `course-ctrl`(코스 주변 신호제어기), `signals`(신호제어기), `xwalk`(횡단보도), `trails`(부울경 산책로 벡터), `slope`(해운대 경사), `trail-issues`(끊김·회전교차로·교차 이상), `myrun-line`/`myrun-xwalk`(내 기록).
색은 `THEME`에서, 선 굵기·반지름은 각 `addLayer`의 `paint`에서 바꿉니다. 확대 단계 기준은 `minzoom` 값입니다.

## 4. 바꾸지 말아야 할 것
- 출처 표기 문구(패널 하단): © OpenStreetMap contributors(ODbL), 부산광역시 교통정보서비스센터, Open-Meteo.
- 데이터 한계 문구(신호제어기 기준일, 경사 오차, "판정 불가는 좋음이 아님" 등)는 사용자를 오도하지 않기 위한 것이므로 디자인을 위해 지우지 마세요.
- 접근성: 버튼·입력에는 이름이 있어야 하고, 상태 문구 영역(`aria-live`)을 유지하며, 포커스 표시(`--focus-ring`)를 없애지 않습니다.

## 5. 시험하는 방법
- 로컬: `npm run dev` 후 `http://127.0.0.1:8787/map/`(포트는 출력 참고).
- 삼성헬스 구역(앱 전용)은 `?mockHealth=1`을 붙이면 가짜 브리지로 화면을 볼 수 있습니다.
- 색만 빠르게 시험: 브라우저 개발자 도구에서 `document.documentElement.style.setProperty('--accent', '#0a7')`.

## 6. 알려진 한계
- UI 문구는 `index.html`과 `app.js`에 한국어로 직접 들어 있습니다(다국어 파일은 아직 없음).
- 지도 라벨은 `app.js`의 `koreanOnly()`가 한글 이름만 쓰도록 바꿉니다. 타일 스타일을 바꾸면 이 함수가 맞는지 확인하세요.
- 분리 전 한 파일 버전은 git 기록에서 볼 수 있습니다.
