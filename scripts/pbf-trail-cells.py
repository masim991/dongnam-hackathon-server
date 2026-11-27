"""OSM 추출본(PBF)에서 산책로·산길을 뽑아 fetch-trails.mjs 와 같은 0.1° 칸 캐시 형식으로 저장한다.
Overpass 로 칸마다 받는 것(시간이 매우 오래 걸림)의 대체 경로. 같은 필터를 쓴다:
  highway=path|footway|pedestrian|steps|track|bridleway, footway≠sidewalk|crossing|traffic_island, access≠private|no
사용: <venv>/python scripts/pbf-trail-cells.py <입력.pbf> <출력 폴더>
한 선은 노드가 하나라도 들어 있는 모든 칸에 기록한다(빌드 단계가 선 ID 로 중복을 제거한다). BBOX/STEP 은 scripts/trail-build-lib.mjs 와 같다.
"""
import json
import math
import os
import sys

import osmium

S, W, N, E, STEP = 34.6, 127.6, 36.0, 129.5, 0.1
HIGHWAYS = {"path", "footway", "pedestrian", "steps", "track", "bridleway"}
SKIP_FOOTWAY = {"sidewalk", "crossing", "traffic_island"}
SKIP_ACCESS = {"private", "no"}


def cell_of(lat, lon):
    return round(math.floor(lat * 10 + 1e-9) / 10, 1), round(math.floor(lon * 10 + 1e-9) / 10, 1)


def main(src, out_dir):
    if os.path.exists(out_dir) and os.listdir(out_dir):
        sys.exit("출력 폴더가 비어 있지 않습니다. 수집본 혼합을 막기 위해 새 폴더를 지정하세요.")
    os.makedirs(out_dir, exist_ok=True)
    cells = {}
    seen = kept = 0
    # 위치 캐시를 쓰려면 노드도 읽어야 한다. 노드는 건너뛰고 선만 처리한다.
    fp = osmium.FileProcessor(src, osmium.osm.NODE | osmium.osm.WAY).with_locations()
    for w in fp:
        if not w.is_way():
            continue
        seen += 1
        t = w.tags
        if t.get("highway") not in HIGHWAYS or t.get("footway") in SKIP_FOOTWAY or t.get("access") in SKIP_ACCESS:
            continue
        try:
            geom = [(round(n.lon, 6), round(n.lat, 6)) for n in w.nodes]
        except osmium.InvalidLocationError:
            continue  # 추출 경계 밖 노드가 빠진 선
        if len(geom) < 2:
            continue
        way = {"id": w.id, "tags": {k.k: k.v for k in t}, "geometry": [[lo, la] for lo, la in geom]}
        keys = {cell_of(la, lo) for lo, la in geom}
        inside = [k for k in keys if S - 1e-9 <= k[0] < N - 1e-9 and W - 1e-9 <= k[1] < E - 1e-9]
        if not inside:
            continue
        kept += 1
        for k in inside:
            cells.setdefault(k, []).append(way)
    written = 0
    s = S
    while s < N - 1e-9:
        wv = W
        while wv < E - 1e-9:
            k = (round(s, 1), round(wv, 1))
            name = f"{k[0]:.1f}_{k[1]:.1f}.json"
            with open(os.path.join(out_dir, name), "w", encoding="utf-8") as f:
                json.dump({"s": k[0], "w": k[1], "step": STEP, "ways": cells.get(k, [])}, f, ensure_ascii=False, separators=(",", ":"))
            written += 1
            wv += STEP
        s += STEP
    print(json.dumps({"waysSeen": seen, "waysKept": kept, "cellsWritten": written, "cellsWithWays": len(cells)}))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("사용: python scripts/pbf-trail-cells.py <입력.pbf> <출력 폴더>")
    main(sys.argv[1], sys.argv[2])
