# 안드로이드 앱 인계 문서

2026-11-27 KST 기준. 이 문서만 읽고 앱을 만들 수 있게 정리했습니다. 바로 쓸 지시문은 [android-ai-prompt.md](android-ai-prompt.md).

## 1. 목표와 원칙
- **현재 웹 기능을 그대로 유지**한 채 안드로이드 앱으로 출시한다. 지도·분석 로직을 앱으로 다시 쓰지 않는다.
- 앱 구조: **호스트 앱(WebView 셸) + 네이티브 헬스 브리지**. WebView가 라이브 사이트 `https://dongnam-hackathon.magabus.workers.dev/map/`을 열고, 삼성헬스(Health Connect) 읽기만 네이티브로 처리한다. 웹이 개선되면 앱 업데이트 없이 반영된다.
- **백엔드·D1·배포 설정은 바꾸지 않는다.** 앱은 공개 읽기 API만 쓴다.

## 2. 현재 시스템
| 구성 | 위치 | 비고 |
|---|---|---|
| 지도 웹 | `public/map/index.html` (MapLibre, OpenFreeMap 타일) | 패널·레이어·GPX 분석·날씨·헬스 브리지 구역 |
| API | `src/*.ts` (Cloudflare Worker) | 계약: [openapi.yaml](openapi.yaml), 실제 응답: [samples/](samples/) |
| 정적 데이터 | `public/data/` | `haeundae/trail-context.json`(해운대 산책로 500m 이내 신호·횡단·경사), `trails/`(부울경 산책로 벡터, 수집 중) |
| DB | Cloudflare D1 `dongnam-running` | **앱이 직접 접근하지 않는다**(Worker API만 사용). 무료 쓰기 한도가 있음 |
| 안드로이드 라이브러리 | `android/health-connect` (Codex) | 읽기 전용 Health Connect 모듈. 호스트 앱은 아직 없음 |

## 3. 반드시 유지할 기능 (회귀 체크리스트)
1. 코스 목록(9개)과 선택 시 경로·공공 신호 횡단보도·OSM 횡단·출입구 표시 — `/api/courses`, `/api/courses/{code}/geojson`
2. 확대 시 신호제어기(14단계~)·신호 횡단보도(15단계~) 레이어 — `/api/signals`, `/api/crosswalks`
3. 해운대 산책로 500m 이내 경사(10%↑) 레이어 — `/data/haeundae/trail-context.json`
4. 러닝 날씨 판정(구역별 점수·사유·추천 시간, 판정 불가는 제외) — `/api/weather`
5. **GPX 불러오기**: 파일은 기기 안에서만 분석(서버 전송 없음), 시작·끝 150m 가리기 옵션
6. 삼성헬스 연동 구역(앱에서만 표시): [android-web-bridge.md](android-web-bridge.md)
7. 좁은 화면 레이아웃(하단 시트), 키보드·스크린리더 접근(버튼·`aria-live`)
8. 출처 표기 문구(패널 하단) 유지

## 4. 호스트 앱 작업 범위 (AI가 할 일)
1. 새 모듈 `android/app` 추가(기존 `android/health-connect`·Gradle 설정은 구조를 유지하고 필요한 최소만 수정). 패키지 `kr.dongnam.running`(최종 패키지명은 사용자 확인).
2. `MainActivity`: WebView로 `/map/` 로드. JS·DOM 저장소 허용, 파일 접근(`allowFileAccess`) 끔, 신뢰 도메인 외 이동은 외부 브라우저로, **`onShowFileChooser` 구현**(GPX 선택), 뒤로가기 처리, 오프라인·오류 화면.
3. 브리지 구현: [android-web-bridge.md](android-web-bridge.md)의 `AndroidHealth`·`onAndroidHealth` 규약. 정확한 origin·최상위 프레임을 제한한 WebKit 메시지 채널을 사용한다. `SamsungHealthReader`를 재사용하고 권한 런처는 Activity 등록 단계에서 만든다.
4. `PermissionsRationaleActivity`와 `ViewPermissionUsageActivity` alias, 개인정보 처리방침 화면(Health Connect 필수). 템플릿: `docs/android-health-connect.md`.
5. 앱 이름·아이콘·스플래시, 앱 서명 설정(키는 저장소에 넣지 않음).
6. 단위 테스트(브리지 JSON 변환, 신뢰 origin 검사) 및 빌드 명령 문서화.

## 5. 환경과 버전 (Codex가 고정한 값)
Health Connect `androidx.health.connect:connect-client:1.1.0`, AGP 8.11.0, Kotlin 2.1.20, Gradle 8.14.3, JDK 17, compileSdk 36 / minSdk 28. 이 PC에는 안드로이드 SDK가 설치되어 있지 않고 `ANDROID_HOME`이 존재하지 않는 경로를 가리킨다. Android Studio 설치 후 SDK Platform 36·Build-Tools 35를 받고 `android/local.properties`(Git 제외)에 `sdk.dir`을 지정해야 빌드할 수 있다. **지금까지 앱 전체 빌드·실기기 시험은 한 번도 하지 못했다.**

## 6. 삼성헬스 연동의 사실과 한계
- 경로: 삼성헬스 → Health Connect → 앱. 웹이나 Worker가 삼성헬스에 직접 접근할 수는 없다.
- 현재 읽는 것: 최근 1~7일 걸음 수, 총 이동거리, 러닝/트레드밀 세션(시작·종료). **GPS 경로는 읽지 않는다.** 삼성헬스가 경로를 Health Connect에 기록하는지 확인하지 못했다(커뮤니티 글은 "안 된다"는 취지, 공식 확인 없음). 경로가 필요하면 GPX 내보내기(삼성헬스 → 운동 기록 → 더보기 → 경로 저장) + 웹의 GPX 불러오기를 쓴다.
- 대안: Samsung Health Data SDK. 개발자 모드에서는 읽기가 가능하다고 안내되나, **배포하려면 삼성 파트너 승인과 패키지명·서명 SHA-256 등록이 필요**하고 경로 읽기 지원은 공식 확인하지 못했다.
- Android 14+는 Health Connect가 시스템에 포함, 9~13은 별도 앱 설치 필요 → 네이티브 `sdkStatus()`를 웹 어댑터 `status()`로 매핑해 분기.

## 7. 출시 전 사용자가 정해야 할 것 / 미해결
| 항목 | 상태 |
|---|---|
| 최종 패키지명, 앱 서명키 | 미정 |
| 개인정보 처리방침 URL | 없음(Health Connect 선언·Play 데이터 안전 양식에 필요) |
| Play Console의 Health Connect 데이터 접근 선언 | 미작성(사용 목적과 앱 동작·방침이 일치해야 함) |
| 날씨 공급자 Open-Meteo | **무료 API는 비상업 조건** → 상용 출시 전 조건 확인 또는 기상청 API(키 필요)로 교체 |
| 지도 타일 OpenFreeMap 사용 조건 | 상용 출시 전 확인 필요 |
| D1 무료 쓰기 한도 | 데이터 재적재 시 소진 주의(앱은 읽기만 하므로 영향 적음) |
| 데이터 한계 | 신호제어기 기준일 2020-12-31, 경사는 30m DEM 기반 외부 값(오차 큼), 동백섬 코스는 현장 미검증, OSM 산책로는 입력된 길만 |
| 부울경 산책로 벡터 | 수집 진행 중(완료 시 `/data/trails/`로 제공) |

## 8. 하지 말 것
- Worker·D1·`wrangler.jsonc`·`db/`·`scripts/`를 수정하거나 원격에 쓰지 않는다(필요하면 사용자에게 요청).
- 건강 데이터를 서버·분석 도구·로그로 보내지 않는다.
- 키·토큰·`local.properties`·서명키를 커밋하지 않는다.
- 웹 기능을 네이티브로 재구현하지 않는다(WebView로 유지).
- 완료를 선언하기 전에 실제 빌드 결과를 확인한다. 빌드하지 못한 항목은 "미검증"으로 기록한다.

## 9. 수락 기준
1. `./gradlew :app:assembleDebug` 성공, 에뮬레이터/실기기에서 `/map/`이 열리고 코스 선택·날씨·레이어가 웹과 동일하게 동작.
2. GPX 선택 창이 열리고 샘플 GPX(`docs/samples/` 또는 직접 생성)를 분석해 횡단보도 개수가 표시됨.
3. Health Connect가 있는 기기에서 권한 요청 → 최근 7일 요약 표시, 권한 거절·철회·공유 없음(null)·SDK 미지원 상태가 각각 올바른 문구로 표시됨.
4. 위장 도메인·다른 포트·외부 iframe에서는 브리지가 아무것도 반환하지 않고, 조회 도중 탐색하면 결과를 폐기함(단위 테스트·기기 시험).
5. 건강 데이터가 네트워크 요청에 포함되지 않음(요청 로그 확인).

## 10. 담당
- 웹·API·DB·배포: Claude(`public/`, `src/`, `db/`, `scripts/`, `docs/` 일부)
- `android/health-connect`, `docs/android-health-connect.md`, `docs/codex-handoff.md`: Codex
- 호스트 앱(`android/app`): 이 문서를 받는 AI/개발자

## 11. 시스템 코드 검토·커밋 기준 (2026-11-27 KST)

- 사용자 범위: 시스템 코드·API 계약·Android 인계만 커밋합니다. 부울경 전체 데이터 수집, DB 재적재·동기화, Cloudflare 재배포, 앱 게시를 이번 검토의 완료 조건에 포함하지 않습니다.
- GitHub 비교: `git fetch origin --prune`로 확인한 `origin/master`는 `7ff4dce5f23f2dabce83c8cd453989b3deac973a`(최초 서버 준비)이며, 검토 시작 시 로컬 `e3ac18c3cb9e262ef2c99366ca1c3095bfc76e6d`가 4커밋 앞서 있었습니다. 현재 GitHub에는 그 이후의 지도·API·Health Connect 코드가 아직 반영되지 않은 상태였습니다. 커밋과 원격 푸시는 별개입니다.
- Cloudflare 비교: 17:36 KST 공개 `/map/`은 200이지만 `initHealthBridge`와 산책로 위상 레이어가 없는 이전 버전이었습니다. 로컬에는 두 기능이 있습니다. 로컬의 `trail-context.json`도 배포본과 파일 해시가 달랐고, `/data/haeundae/trail-issues.json`과 `/data/trails/index.json`은 404였습니다. 이 차이를 해소하기 위한 재배포·데이터 업로드는 수행하지 않습니다.
- 배포 조회에서 확인한 최신 Worker 버전: `e4482cd7-2f6a-46c3-b5e8-0c91b20744ff`(2026-11-27 16:52:45 KST). 공개 응답·에셋을 비교했으며 Worker 번들 원본과 로컬 소스의 바이트 일치까지 검증한 것은 아닙니다.
- 수정한 결함: OpenAPI의 쉼표 포함 설명이 YAML 객체로 잘못 해석되던 형식 오류, 안전하지 않은 Android 브리지 예제, 회전교차로를 호로 줄인 뒤 연결선이 30m를 초과할 수 있던 위상 처리 오류.
- 확인: 타입 검사, Node 테스트 18개, Worker 배포 dry-run, 지도 inline JavaScript 문법 검사, OpenAPI 3.0 명세 검증 통과. 실제 Android 앱 빌드·기기 실행·Play 출시는 미검증입니다.
- 생성 데이터 `public/data/haeundae/trail-issues.json`, DB 비교 결과·스냅샷·시드·원시 수집 파일은 이번 커밋 대상으로 삼지 않습니다. `docs/samples/`는 공개 API에서 줄인 계약 예시이며 건강 기록이나 DB 전체 덤프가 아닙니다.
