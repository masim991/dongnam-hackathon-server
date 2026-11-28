#!/bin/bash
# 한 줄 개발: 에뮬레이터 창 띄우기(없으면) → 빌드 → 설치 → 권한 → 위치(해운대) → 실행
#   ./scripts/dev.sh          빌드+설치+실행
#   ./scripts/dev.sh --fresh  앱 데이터 초기화 후 실행 (첫 실행 상태)
#   ./scripts/dev.sh --nobuild  빌드 없이 설치된 앱만 다시 실행
set -e
source "$(dirname "$0")/env.sh"; cd "$PROJECT_DIR"

if ! "$ADB" devices | grep -q "emulator-.*device$"; then
  echo "▶ 에뮬레이터 창을 띄웁니다 ($AVD_NAME)…"
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -gpu auto -no-audio -no-snapshot -no-boot-anim > .screens/emulator.log 2>&1 &
  for i in $(seq 1 90); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 3
  done
fi

if [ "$1" != "--nobuild" ]; then
  echo "▶ 빌드…"; ./gradlew :app:assembleRelease -q 2>&1 | grep -v "^Warning\|^w:" || true
  APK=app/build/outputs/apk/release/app-release.apk
  [ -f "$APK" ] || { echo "빌드 실패 (위 오류 확인)"; exit 1; }
  echo "▶ 설치…"; "$ADB" install -r "$APK" | tail -1
fi
[ "$1" = "--fresh" ] && "$ADB" shell pm clear $APP_ID >/dev/null
for p in ACCESS_FINE_LOCATION POST_NOTIFICATIONS; do "$ADB" shell pm grant $APP_ID android.permission.$p 2>/dev/null || true; done
"$ADB" emu geo fix 129.1604 35.1587 >/dev/null 2>&1 || true   # 해운대 해수욕장
"$ADB" logcat -c
"$ADB" shell am start -n $APP_ID/.ui.MainActivity >/dev/null
echo "✓ 실행 완료. 화면 캡처: ./scripts/shot.sh   로그: ./scripts/log.sh"
