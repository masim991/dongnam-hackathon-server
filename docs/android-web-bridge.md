# 웹 ↔ 안드로이드 앱 브리지 규약 (삼성헬스 / Health Connect)

웹 지도(`/map/`)는 이미 이 규약을 구현해 두었습니다(`public/map/app.js`의 `initHealthBridge`·`installMockHealth`). **앱(WebView 호스트)은 아래 객체와 콜백만 구현하면 됩니다.** 웹 화면은 `window.AndroidHealth`가 있을 때만 "삼성헬스 연동" 구역을 보여 줍니다.

## 앱이 주입할 객체: `window.AndroidHealth` (웹 어댑터)

아래는 웹 화면이 사용하는 인터페이스입니다. 건강 데이터 접근 권한이 있는 Java 객체를 `addJavascriptInterface`로 직접 노출하지 않습니다. 호스트 앱은 출처 제한이 있는 AndroidX WebKit 메시지 채널과 JavaScript 어댑터로 이 인터페이스를 제공합니다. 호스트 앱은 아직 구현되지 않았습니다.

| 메서드 | 반환/동작 |
|---|---|
| `status()` | 동기. `"available"` / `"update_required"` / `"unavailable"` — `HealthConnectClient.getSdkStatus` 결과를 매핑 |
| `requestPermissions()` | 비동기. 사용자가 버튼을 눌렀을 때만 호출됨. 권한 요청 화면을 띄우고 결과를 콜백으로 전달 |
| `readRecent(days)` | 비동기. `days`는 1~7 정수(범위 밖이면 오류 콜백). 읽은 요약을 콜백으로 전달 |

## 앱이 호출할 콜백: `window.onAndroidHealth(message)`

`webView.evaluateJavascript("window.onAndroidHealth(" + json + ")", null)` 형태로 호출합니다. `json`은 `org.json`으로 만든 **순수 JSON 객체**여야 하고, 문자열을 직접 이어 붙여 만들지 마세요(스크립트 주입 방지).

```jsonc
{ "type": "permission", "granted": true }
{ "type": "summary", "days": 7, "startMillis": 1795000000000, "endMillis": 1795600000000,
  "steps": 48210,                 // 없으면 null (0으로 바꾸지 말 것)
  "distanceMeters": 35120.5,      // 없으면 null. 전체 활동 합계이며 러닝만의 거리가 아님
  "runningSessions": [ { "id": "…", "startMillis": 0, "endMillis": 0, "title": null } ] }
{ "type": "error", "code": "permission_denied", "message": "(선택)" }
```

오류 `code`는 `permission_denied`, `unavailable`, `update_required`, `failed` 중 하나입니다. `readRecent(days)`의 정수가 1~7 범위를 벗어나면 `failed`로 처리하며, 임의로 1~7로 보정하거나 성공 응답에 원래 값을 돌려주지 않습니다.

필드는 `android/health-connect`의 `SamsungActivitySummary`·`RunningSession`과 1:1로 대응합니다(`steps`, `distanceMeters`, `runningSessions`; 시각은 `Instant` → epoch millis).

## 보안·개인정보 요구
- **신뢰하는 출처의 최상위 프레임에서만** 동작합니다. 문자열 `startsWith` 검사는 위장 호스트를 허용하므로 사용하지 않습니다. HTTPS·정확한 호스트명·기본 포트(443)를 URI로 비교합니다. 사용자 정보가 있는 URL, 다른 포트, HTTP, `file:`, `content:`, `data:`, `javascript:` 탐색은 WebView에서 거부합니다.
- `WebViewCompat.addWebMessageListener`의 `allowedOriginRules`에 정확히 `https://dongnam-hackathon.magabus.workers.dev`만 넣고 `*`를 쓰지 않습니다. 콜백에서도 `sourceOrigin`과 `isMainFrame`을 확인합니다. `webView.url`만 확인하면 호출한 iframe의 출처를 판별할 수 없습니다.
- 권한 요청·건강 조회 시작 시 페이지 세대를 기록하고, 비동기 작업 완료 후에도 같은 신뢰 페이지인지 재검사합니다. 탐색·WebView 파괴·앱 백그라운드 전환으로 더 이상 결과를 표시할 수 없으면 결과를 폐기합니다. 이전 페이지의 건강 데이터를 새 문서에 전달하지 않습니다.
- 건강 데이터는 **서버로 보내지 않습니다**(웹은 요약 문구만 화면에 표시). 로그·분석 도구에도 남기지 않습니다.
- 매 조회 전에 권한을 다시 확인하고, 거절·철회 시 자동 재요청하지 않습니다.
- 삼성헬스 공유가 꺼져 있어 빈 결과가 오면 연결 성공으로 표시하지 말고 `steps/distanceMeters = null`을 그대로 전달합니다.

## 호스트 구현 순서 (미구현)

1. `WebViewFeature.WEB_MESSAGE_LISTENER` 지원 여부를 확인합니다. 미지원 WebView에서는 업데이트 안내를 표시하고 헬스 브리지를 제공하지 않습니다.
2. 신뢰 origin에 제한한 메시지 리스너를 페이지 로드 전에 등록합니다. 앱이 `reader.sdkStatus()`로 확인한 상태와 `window.AndroidHealth` 어댑터를 페이지 초기화 전에 제공합니다. 동기 `status()`는 이 상태를 반환하고, 나머지 두 메서드는 메시지를 전송합니다. 문서 시작 스크립트 사용 시 해당 WebView 기능 지원 여부도 확인합니다.
3. 모든 메시지의 출처·최상위 프레임·요청 종류·기간을 검증하고 UI 작업은 메인 스레드에서 실행합니다. 권한 런처는 Activity 등록 단계에서 만듭니다.
4. `readRecentSummary(days.toLong())` 결과를 `JSONObject`·`JSONArray`로 직렬화합니다. `null`은 `JSONObject.NULL`, `Instant`는 epoch millis로 변환합니다. coroutine 취소는 다시 던집니다.
5. 같은 페이지에만 메시지 응답을 전달하고, 어댑터가 `window.onAndroidHealth`를 호출합니다. `evaluateJavascript`를 사용하는 경우에도 전달 직전에 페이지 세대를 확인합니다. 원시 오류·건강 기록을 로그에 쓰지 않습니다.

실제 구현에는 앱의 Activity·수명주기·WebView 설정과 함께 빌드 및 기기 검증이 필요합니다. 문서의 계약은 실행 가능한 네이티브 브리지 구현이 아닙니다.

근거: [Android WebView 네이티브 브리지 보안](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges), [WebViewCompat 메시지 리스너](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set,androidx.webkit.WebViewCompat.WebMessageListener)).

## 웹의 동작 보강

- 웹은 `status()`가 `"available"`이 아니면 두 버튼(권한 요청·불러오기)을 비활성화하고 상태 문구만 보여 줍니다. 네이티브에서도 매 요청마다 지원 상태와 권한을 검증해야 합니다.
- 요약 메시지의 `days`는 앱이 **검증된 실제 조회 일수**로 채워 보내야 합니다. 웹도 1~7 정수, 유효한 걸음 수·거리, 기록 배열인지 확인하고 잘못된 요약은 오류로 표시합니다.
- 브리지 상태 조회·호출 예외는 화면에 오류로 표시하며 지도·날씨 초기화를 막지 않습니다. 지도 로딩 실패 시 GPX 처리도 오류 안내로 종료합니다.

## 화면 시험 (기기 없이)
브라우저에서 `https://dongnam-hackathon.magabus.workers.dev/map/?mockHealth=1` 을 열면 가짜 브리지가 주입되어 같은 화면과 흐름을 시험할 수 있습니다(실제 건강 데이터 아님).
이미 네이티브 브리지가 있으면 모의 브리지로 덮어쓰지 않습니다. 모의 요약에는 `[모의 데이터]`를 표시합니다. 위 보강은 로컬 변경이며 운영 반영에는 별도 배포가 필요합니다.

## 아직 제공하지 않는 것
GPS 경로(운동 경로)는 읽지 않습니다. 경로 분석은 웹의 **GPX 불러오기**(기기 안에서만 분석)로 합니다. Health Connect 경로 읽기는 별도 데이터 지원·동의 흐름을 확인한 뒤 추가해야 하며, 삼성헬스가 해당 경로를 공유하는지는 이번 작업에서 확인하지 못했습니다.
