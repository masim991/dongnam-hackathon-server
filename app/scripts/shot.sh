#!/bin/bash
# 에뮬레이터 화면 캡처 → .screens/latest.png (인자로 이름을 주면 .screens/<이름>.png 도 저장)
source "$(dirname "$0")/env.sh"; mkdir -p "$PROJECT_DIR/.screens"
"$ADB" exec-out screencap -p > "$PROJECT_DIR/.screens/latest.png"
[ -n "$1" ] && cp "$PROJECT_DIR/.screens/latest.png" "$PROJECT_DIR/.screens/$1.png"
echo "$PROJECT_DIR/.screens/latest.png"
