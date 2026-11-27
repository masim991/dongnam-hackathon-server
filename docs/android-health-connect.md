# Android 삼성헬스 연동 준비

확인일: 2026-11-27 KST. 담당: Codex. 기존 Workers·DB·지도 코드는 수정하지 않습니다.

## 현재 범위

`android/health-connect`는 향후 Android 앱에 포함할 읽기 전용 라이브러리입니다. 독립 실행 앱이나 APK가 아니며, 앱 출시·삼성헬스 실기기 연동 완료를 뜻하지 않습니다. 이 저장소에는 기존 Android 앱이 없어 중복 설정을 만들지 않고 별도 모듈로 준비했습니다.

삼성헬스 → Android Health Connect → 앱 순으로 기기 안에서 공유된 기록을 읽습니다. Galaxy Watch 기록은 휴대전화 삼성헬스 동기화 이후 읽을 수 있으며 실시간 수신을 보장하지 않습니다. Workers나 웹페이지가 삼성헬스 기기 저장소에 직접 접근할 수는 없습니다.

- 조회: 최근 1~7일의 삼성헬스 출처 걸음 수·총 이동거리·러닝/트레드밀 운동 세션.
- `steps`·`distanceMeters`가 `null`이면 공유된 측정값이 없는 것입니다. 0으로 바꾸어 운동하지 않았다고 표시하지 않습니다.
- 총 이동거리는 선택 기간의 전체 활동 합계입니다. 러닝 세션별 거리·페이스로 해석하지 않습니다.
- SDK 미지원/업데이트 필요 상태를 구분할 수 있도록 `sdkStatus()`를 제공합니다.
- 매번 조회 전에 권한을 다시 확인합니다. 사용자가 거절·철회하면 읽지 않으며 `SecurityException`을 호출자에게 전달합니다.
- 운동 세션의 모든 페이지를 읽습니다. 네트워크·DB 업로드, 백그라운드 읽기, 건강 기록 쓰기·삭제, 심박·GPS 경로 권한은 추가하지 않았습니다.
- 삼성헬스 공유가 꺼져 있으면 다른 앱의 기록으로 대체하지 않습니다. 빈 결과를 연결 성공으로 표시하지 않아야 합니다.

## 고정 버전과 빌드

| 항목 | 설정 |
|---|---|
| Health Connect | `androidx.health.connect:connect-client:1.1.0` (안정판) |
| Android Gradle Plugin | `8.11.0` |
| Kotlin | `2.1.20` |
| Gradle Wrapper | `8.14.3` |
| JDK | 17 |
| compileSdk / minSdk | 36 / 28 |

SDK 1.1.0에 필요한 compileSdk 36과 호환되는 AGP를 선택했습니다. Android 14 이상은 Health Connect가 시스템에 포함되고, Android 9~13에서는 별도 Health Connect 앱이 필요합니다. 기기별 지원 여부는 반드시 `sdkStatus()`로 판별합니다.

Android SDK Platform 36과 Build Tools 35.0.0이 설치된 환경에서, `android/` 폴더를 Android Studio로 열거나 다음을 실행합니다.

```powershell
.\gradlew.bat :health-connect:assembleDebug :health-connect:testDebugUnitTest :health-connect:lintDebug
```

환경변수 `ANDROID_HOME` 또는 Git 제외 파일 `android/local.properties`의 `sdk.dir`에 **실제 설치된** SDK 경로를 지정합니다. 이 PC의 기존 환경변수는 존재하지 않는 `C:\Users\magab\AppData\Local\Android\Sdk`를 가리켰습니다. 전역 환경설정·SDK 라이선스는 변경하지 않았습니다.

Windows의 기존 한글 프로젝트 경로를 유지하기 위해 `android.overridePathCheck=true`를 이 Android 프로젝트에만 설정했습니다. AGP의 사전 경로 검사만 건너뛴 것이며, 네이티브 도구 등에서 경로 문제가 발생하면 영문 경로의 별도 체크아웃에서 빌드해야 합니다.

### 이번 검증 결과

- Gradle Wrapper 생성 및 Android/Kotlin 플러그인 설정: 성공.
- 조회 기간의 7일 경계·과거 범위 초과·빈 구간·미래 구간: Kotlin 2.1.20으로 `ReadWindow.kt`와 `ReadWindowTest.kt`를 직접 컴파일하고 JUnit 4.13.2 실행, **2개 테스트 통과**. Android Gradle 테스트가 통과했다는 의미는 아닙니다.
- Health Connect 1.1.0 공식 Maven 소스에서 요청 파라미터·페이지 토큰·러닝 타입 상수 확인.
- `assembleDebug`, Android 단위 테스트, lint: **SDK location not found로 작업 의존성 계산 단계에서 중단**. 전체 모듈 컴파일·AAR 산출물은 아직 검증되지 않았습니다.
- 삼성헬스 동기화·권한 화면·실기기 실행·스토어 출시: 미검증.

## 향후 Android 앱에서 연결할 부분

호스트 앱의 Gradle 설정에 이 모듈을 포함하고 `implementation(project(":health-connect"))`를 추가합니다. 모듈 Manifest의 `READ_STEPS`, `READ_DISTANCE`, `READ_EXERCISE` 및 Health Connect 패키지 조회 선언이 호스트 앱에 병합됩니다.

Activity에서 권한 요청 런처는 `onCreate` 이전 등록 단계에서 만들고, 사용자에게 용도를 설명한 뒤 버튼 조작으로 요청합니다.

```kotlin
private val requestHealthPermissions = registerForActivityResult(
    SamsungHealthReader.permissionContract()
) { granted ->
    val connected = granted.containsAll(SamsungHealthReader.READ_PERMISSIONS)
    // connected 값에 따라 화면 상태만 바꾸고, 거절 시 자동으로 재요청하지 않습니다.
}

// HealthConnectClient.SDK_AVAILABLE인 경우에만 사용자 버튼에서 호출합니다.
requestHealthPermissions.launch(SamsungHealthReader.READ_PERMISSIONS)

// 화면이 활성화된 동안 lifecycleScope 등에서 실행합니다.
val summary = SamsungHealthReader(applicationContext).readRecentSummary(days = 7)
```

`SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`이면 설치·업데이트 안내를 표시하고, `SDK_UNAVAILABLE`이면 해당 기기에서 연동을 제공하지 않습니다. 조회 호출자는 `SecurityException`, 기기 서비스/입출력 오류를 화면 오류 상태로 처리하되 coroutine 취소는 전파해야 합니다. 권한 승인만으로 삼성헬스의 공유 설정까지 켜졌다고 가정하지 않습니다.

**호스트 앱을 만들 때 반드시 추가할 Manifest 항목:** 아래 Activity를 실제로 구현하여 Health Connect 권한 화면에서 개인정보 처리방침을 열 수 있게 해야 합니다. 라이브러리에는 미구현 Activity를 등록하지 않았습니다.

```xml
<application>
    <activity android:name=".PermissionsRationaleActivity" android:exported="true">
        <intent-filter>
            <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
        </intent-filter>
    </activity>
    <activity-alias
        android:name="ViewPermissionUsageActivity"
        android:exported="true"
        android:targetActivity=".PermissionsRationaleActivity"
        android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
        <intent-filter>
            <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
            <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
        </intent-filter>
    </activity-alias>
</application>
```

## 기기 연동·출시 때 남은 작업

1. 휴대전화 삼성헬스 설정 → Health Connect에서 걸음 수·거리·운동 공유를 사용자가 허용합니다. Android 버전별 메뉴는 다를 수 있습니다.
2. 호스트 앱의 개인정보 처리방침 Activity, 연동 버튼, 설치·미지원·거절·철회·공유 없음·서비스 오류 상태를 구현합니다. 앱 서명·패키지명은 아직 정하지 않았습니다.
3. 실제 Android 13/14 이상에서 동기화된 삼성헬스 기록, 권한 일부 거절·철회, 빈 데이터, 운동 여러 페이지를 확인합니다. Galaxy Watch가 있다면 휴대전화 동기화 지연도 확인합니다.
4. Google Play의 건강 앱/Health Connect 데이터 접근 선언과 실제 사용 목적·개인정보 처리방침을 일치시킵니다. 라이브러리를 추가했다고 스토어 승인이나 삼성 파트너 승인이 완료되는 것은 아닙니다.

현재 사용 범위는 기기 내 읽기입니다. 향후 건강 기록을 서버에 저장하려면 사용자 동의·인증·개인별 접근 제어·보존 및 삭제 기능을 먼저 설계해야 합니다.

## 확인한 공식 문서

- [Samsung Health Connect FAQ](https://developer.samsung.com/health/health-connect-faq.html): 삼성헬스·워치·휴대전화 동기화 관계.
- [Android Health Connect 시작](https://developer.android.com/health-and-fitness/health-connect/get-started): SDK 상태·권한 계약·Manifest 및 개인정보 처리방침 Activity.
- [Health Connect 릴리스](https://developer.android.com/jetpack/androidx/releases/health-connect): 안정판 1.1.0 선택.
- [AGP 8.11 호환성](https://developer.android.com/build/releases/agp-8-11-0-release-notes): compileSdk 36, Gradle 8.13 이상, JDK 17.
- [집계·원시 데이터 읽기](https://developer.android.com/health-and-fitness/health-connect/read-data): 누적 걸음 수는 원시 레코드 수동 합산 대신 aggregate 사용.
- [AggregateRequest](https://developer.android.com/reference/kotlin/androidx/health/connect/client/request/AggregateRequest): 출처별 필터.
- [ReadRecordsRequest](https://developer.android.com/reference/kotlin/androidx/health/connect/client/request/ReadRecordsRequest): 세션 조회와 페이지 토큰.
