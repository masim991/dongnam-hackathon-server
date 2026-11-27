# 부울경 산책로 벡터 배포 기록

2026-11-27 한국시간 20:18:36 배포. Worker `dongnam-hackathon`의 정적 에셋이며 D1 적재가 아닙니다.

| 항목 | 값 |
|---|---|
| 지도 | https://dongnam-hackathon.magabus.workers.dev/map/ |
| manifest | https://dongnam-hackathon.magabus.workers.dev/data/trails/index.json |
| Worker 버전 | `1722339c-779f-47aa-b591-e289350ba4ab` |
| 이전 Worker 버전 | `e4482cd7-2f6a-46c3-b5e8-0c91b20744ff` |
| 데이터 버전 | `f5696efd6ff97d28f7602a54dc296a044b4161c528e8098642af8bedbb37b285` |
| 수집 범위 | 기대 셀 199/199, 누락·손상 0, `partial:false` |
| 길 구간 | 53,451개: 부산 7,575 / 울산 4,674 / 경남 41,202 |
| 산출물 | 176개 조각 + index.json, 조각 합계 13,205,422 bytes |

원천은 OpenStreetMap contributors (ODbL), Geofabrik South Korea 추출본입니다. 다운로드 당시 원본의 Last-Modified는 `2026-11-27 03:45:13 GMT`, 파일 크기는 292,885,954 bytes, MD5는 `9fa7e48b18a3032108b5fc67041cb5f6`입니다. [배포 원천 주소](https://download.geofabrik.de/asia/south-korea-latest.osm.pbf)는 갱신되므로 미래에 다시 다운로드하면 같은 데이터가 아닐 수 있습니다. 원본 PBF와 가상환경은 Git에 포함하지 않습니다.

## 수집과 빌드

공개 Overpass API의 셀별 수집을 기다리는 대신 PBF 전체 추출본을 같은 필터로 변환하는 대체 경로를 추가했습니다. 선택 기능이며 앱 실행에는 Python이 필요하지 않습니다.

```powershell
python -m venv .wrangler/osm/venv
./.wrangler/osm/venv/Scripts/python.exe -m pip install -r scripts/requirements-trails.txt
# 원본은 읽기만 합니다. 출력은 매 수집마다 비어 있는 별도 폴더를 지정하세요.
./.wrangler/osm/venv/Scripts/python.exe scripts/pbf-trail-cells.py .wrangler/osm/south-korea-latest.osm.pbf .wrangler/osm/cells-new
node scripts/build-trails.mjs --cells .wrangler/osm/cells-new --source "OpenStreetMap contributors (ODbL), Geofabrik South Korea extract 2026-11-27" --dry-run
# 검증 후 같은 명령에서 --dry-run을 빼면 public/data/trails를 교체합니다.
npm run data:check
```

PBF 변환기는 기존 출력 폴더가 비어 있지 않으면 중단합니다. 서로 다른 추출본이 섞인 캐시를 완료된 수집으로 오인하지 않도록 하기 위해서입니다. 중간 실패 산출물도 재사용하지 말고 새 빈 폴더에서 다시 변환합니다. 최종 벡터 빌드는 기존 staging·검증·백업 복원 절차를 유지합니다. 날짜·출처 문구는 실제 다운로드한 원본에 맞춰 지정합니다.

## 검증과 표시

```powershell
npm test
npm run typecheck
npm run build
./.wrangler/osm/venv/Scripts/python.exe test/pbf-trail-cells.test.py
```

배포 후 176개 조각 전부의 HTTP 200·SHA256·바이트 수를 로컬과 대조했습니다. 전역 길 ID 중복은 0개였습니다. 지도 파일 4개와 해운대 상세 파일 2개도 운영/로컬 바이트가 일치했고, 기존 9개 코스 API가 정상 응답했습니다. 브라우저에서 산책로 레이어를 켜고 해운대 중심(129.17, 35.17)을 14단계로 확대해 렌더링된 피처 428개를 확인했습니다. 같은 길이 화면 타일 경계에서 나뉘므로 이 화면 피처 수를 고유 길 수로 해석하지 않습니다.

지도에서 **지도 레이어 → 부울경 산책로·산길**을 켜고 12단계 이상 확대합니다. 일부 지역은 OSM 입력이 없을 수 있습니다. 길의 중간 꼭짓점 또는 시작점으로 지역을 분류하므로 행정경계에서 선을 정확히 절단하는 기능은 아닙니다. 4m 단순화·20m 미만 제거가 적용되며 현실의 모든 산책로 또는 현장 통행 가능성을 보증하지 않습니다.

이번 배포에는 별도 PC의 부산 경사 약 14만 행과 Supabase 분리 조회 실험은 포함하지 않았습니다. 롤백이 필요하면 위 이전 Worker 버전을 대상으로 진행하며 D1 데이터를 삭제·재적재하지 않습니다.
