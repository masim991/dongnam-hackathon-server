#!/bin/bash
# 앱 로그 확인. 기본: 크래시/오류만. 인자 all 이면 앱 전체 로그 (최근 300줄)
source "$(dirname "$0")/env.sh"
PID=$("$ADB" shell pidof $APP_ID | tr -d '\r')
if [ "$1" = "all" ]; then "$ADB" logcat -d --pid="$PID" | tail -300
else "$ADB" logcat -d AndroidRuntime:E System.err:W *:S | tail -80; fi
