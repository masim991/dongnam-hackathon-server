#!/bin/bash
# 에뮬레이터 위치 바꾸기: ./scripts/geo.sh <위도> <경도>   (기본 해운대)
source "$(dirname "$0")/env.sh"
"$ADB" emu geo fix "${2:-129.1604}" "${1:-35.1587}"
