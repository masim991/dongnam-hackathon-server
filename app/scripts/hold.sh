#!/bin/bash
# 화면 길게 누르기(핑 휠 확인): ./scripts/hold.sh <x> <y> [밀리초]   그동안 캡처하려면: ./scripts/hold.sh 540 900 3000 & sleep 1.5; ./scripts/shot.sh
source "$(dirname "$0")/env.sh"
"$ADB" shell input swipe "$1" "$2" "$1" "$2" "${3:-2500}"
