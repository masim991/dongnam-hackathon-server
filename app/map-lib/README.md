# map-lib (EudMapView)
키 없이 쓰는 OSM 지도 독립 모듈 (osmdroid).
다른 프로젝트에서: `settings.gradle.kts`에 `include(":map-lib"); project(":map-lib").projectDir = File("../eudero-gano-app/map-lib")` 후 `implementation(project(":map-lib"))`.
- `EudMapView` : 핀치줌/이동/회전, 현재 위치, 꾹 누르면 + 버튼(`onAddRequested`), `setTrack(points)`로 경로 선.
- Activity/Fragment에서 `onResume()/onPause()` 전달 필요. 위치 권한은 호스트 앱이 요청.
