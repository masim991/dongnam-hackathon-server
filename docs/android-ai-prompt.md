# 다른 AI에게 줄 지시문 (그대로 붙여넣기)

아래 블록 전체를 복사해서 안드로이드 개발을 맡길 AI에게 주세요. 저장소 폴더도 함께 열어 주어야 합니다.

```text
당신은 안드로이드 개발자입니다. 이 저장소(동남권 러닝 지도)의 기존 웹 기능을 그대로 유지한 채 안드로이드 앱(WebView 셸 + 삼성헬스 Health Connect 브리지)으로 만드는 일을 맡았습니다.

먼저 아래 문서를 순서대로 읽으세요(모두 저장소의 docs/ 폴더).
1) docs/android-app-handoff.md  — 목표·범위·수락 기준·하지 말 것(가장 중요)
2) docs/android-web-bridge.md   — 웹과 앱이 주고받는 JS 브리지 규약
3) docs/android-health-connect.md — 이미 만들어진 android/health-connect 라이브러리 설명
4) docs/openapi.yaml, docs/samples/ — 서버 API 계약과 실제 응답 예시

해야 할 일
- android/app 모듈을 새로 만들어 https://dongnam-hackathon.magabus.workers.dev/map/ 을 WebView로 엽니다. 웹 기능을 네이티브로 다시 만들지 마세요.
- android-web-bridge.md의 window.AndroidHealth / window.onAndroidHealth 규약을 구현하고, 기존 android/health-connect의 SamsungHealthReader를 재사용합니다.
- GPX 파일 선택(onShowFileChooser), 신뢰 도메인 제한, 오류·오프라인 화면, Health Connect 개인정보 처리방침 Activity를 구현합니다.
- 건강 브리지는 정확한 origin과 최상위 프레임을 검증하는 AndroidX WebKit 메시지 리스너로 연결합니다. Java 객체를 모든 프레임에 노출하거나 URL startsWith 검사만으로 보호하지 않습니다. 비동기 조회 중 탐색한 경우 결과를 폐기합니다.
- 단위 테스트와 빌드 방법을 문서화합니다.

반드시 지킬 것
- Worker, D1, wrangler 설정, db/, scripts/ 는 수정하지 마세요. 필요하면 저에게 요청하세요.
- 건강 데이터를 서버·로그·분석 도구로 보내지 마세요. 키·서명키·local.properties를 커밋하지 마세요.
- 빌드·실행을 해 보지 않은 것을 "완료"라고 하지 마세요. 확인하지 못한 항목은 "미검증"으로 적으세요.
- 안드로이드 SDK가 설치되어 있지 않을 수 있습니다. 설치·라이선스 동의 같은 시스템 변경은 하기 전에 저에게 물어보세요.

개발 기본값: 임시 패키지명 kr.dongnam.running, 앱 이름 부울경 러닝 지도, Android 기본 디버그 서명으로 구현을 시작하세요. 개인정보 처리방침은 실제 동작에 맞는 로컬 화면 초안을 만들고, 공개 URL·출시 서명키·최종 패키지명은 출시 전 미해결 항목으로 남기세요. 미정인 출시 정보 때문에 되돌릴 수 있는 구현을 멈추지 마세요. 앱 게시·실제 건강 데이터 전송은 별도 요청 없이 진행하지 마세요.
작업이 끝나면 다음을 보고하세요: 만든 파일 목록, 실제로 실행한 빌드·테스트 명령과 결과, 수락 기준(handoff 9장)별 통과/미검증 표, 남은 문제.
```

## 참고: 사람이 직접 확인할 것
- 이 PC에는 안드로이드 SDK가 없습니다. Android Studio 설치와 SDK 라이선스 동의는 사용자가 직접 해야 합니다.
- Play Console의 Health Connect 데이터 접근 선언·개인정보 처리방침은 사용자가 작성해야 합니다.
