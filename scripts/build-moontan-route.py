"""문탠로드(해운대 달맞이길) 순서 있는 전체 루프를 OSM 보행 네트워크의 공유 노드만 따라 재구성한다.
실행(프로젝트 루트): python scripts/build-moontan-route.py
입력: public/data/haeundae/walking-network.json (OSM 2026-11-27 추출본, 노드 ID·원본 좌표 보존) 하나뿐. 교체 전 코스와의 비교는 고정 way ID 목록(BASELINE_CURRENT_WAY_IDS)을 쓴다
출력: data/curated/moontan-route.json
규칙
- 직선 연결 없음: 경로의 모든 인접 좌표 쌍은 같은 OSM 길의 연속 노드다(스크립트가 검증한다).
- 공식 거리(2.2km)에 맞추지 않는다. 경유점 순서만 공식 안내(입구→바다전망대→해월정, 달맞이길→오솔길→어울마당→해월정)에서 따르고 길이는 결과 그대로 보고한다.
- 길 종류를 구분한다: 숲길/보행로(walkway), 계단(steps), 도로 곁 보도(sidewalk), 광장 보행로(pedestrian), 도로(service 등 보행 가능 미확인).
표준 라이브러리만 쓴다.
"""
import heapq
import json
import math
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NETWORK = ROOT / "public/data/haeundae/walking-network.json"
OUT = ROOT / "data/curated/moontan-route.json"
KX, KY = 91000.0, 111320.0  # 경도·위도 1도의 미터(35.17°), 다른 스크립트와 같은 근사

# ---- 경유점: OSM 객체와 근거. 노드는 경로가 지나는 네트워크 노드(공유 노드)다. ----
WAYPOINTS = [
    {"key": "ENT", "name": "문탠로드 들머리(입구)", "node": "11304263742",
     "osm": "문텐로드 계단 129603749의 시작 노드, 달맞이길 보도 1219991303과 접속",
     "confidence": "medium",
     "basis": "공식 안내는 입구에서 출발해 입구로 돌아오는 원점회귀이고 '미포·문탠로드입구' 버스 정류장에서 도보 약 10분(비짓부산 283·1169). 미포 오거리에서 보도를 따라 이 들머리까지 약 725m(도보 약 10분)이며 숲길 이름이 시작되는 곳이다. 정확한 공식 입구 좌표는 확인하지 못했다."},
    {"key": "SEA", "name": "바다전망대(추정)", "node": "1429691024",
     "osm": "정자(gazebo) 노드 12200897194가 숲길 1275958472 위 노드에서 약 1m",
     "confidence": "low-medium",
     "basis": "공식 이름 '바다전망대'가 OSM에는 없다. 공식 안내(부산시보 2012, KTO)가 입구와 해월정 사이 숲길의 전망 지점이라고 한 위치에 있는 경로 위 전망 정자를 골랐다. 다른 후보는 candidates 참고."},
    {"key": "AUL", "name": "달맞이어울마당", "node": "12064320063",
     "osm": "극장(amenity=theatre) 노드 10232817569(이름 '달맟이어울마당', 오탈자)가 경로 노드에서 약 14m",
     "confidence": "high",
     "basis": "OSM 이름과 비짓부산 1169(어울마당→사색의 오솔길), 부산시보 2008·2012의 어울마당 경유."},
    {"key": "HAE", "name": "해월정", "node": "12064371179",
     "osm": "정자 건물 way 784850650(이름 '해월정', 중심 129.18158,35.15674)이 광장 보행 고리 1302647229의 노드에서 약 10m",
     "confidence": "high",
     "basis": "OSM 이름, 부산시보 2011·2012와 KTO 안내의 해월정(달맞이동산)."},
]
# 되돌아오는 구간에서 숲길을 다시 쓰지 않도록 막는 길(순환을 만들기 위함). 문텐로드 이름 길 19개와 그 동쪽 연장.
FOREST_CHAIN = {129603749, 1275958477, 1275958468, 1275958464, 1275958472, 1275958465, 1275958474, 1275958480, 1275958482,
                1496516448, 1496516449, 1496516451, 1275958470, 1275958466, 1275958476, 1275958467, 1496516453, 1496516452, 1275958471}
# PBF 에서 읽은 계단 수 태그(없으면 알 수 없음)
STEP_COUNT = {129603749: 19}

# 교체 전 HD-MOONTAN 코스(이름 '문텐로드' 숲길 19개 way + 4m 연결선 -1000)의 way ID. 이 스크립트의 출력이 public 코스 파일을 대체하므로,
# 비교 기준을 그 파일에서 읽지 않고 고정해 두어 다시 생성해도 결과가 변하지 않게 한다(재현성).
BASELINE_CURRENT_WAY_IDS = [129603749, 1275958464, 1275958465, 1275958466, 1275958467, 1275958468, 1275958470, 1275958471, 1275958472,
                            1275958474, 1275958476, 1275958477, 1275958480, 1275958482, 1496516448, 1496516449, 1496516451, 1496516452, 1496516453]

# 비교 대상 공식 안내(읽은 것만). 같은 노선 확인 전의 거리 비교용이다.
OFFICIAL = {
    "figuresM": [
        {"m": 2200, "what": "비짓부산·KTO·부산시보 2008/2011: 2.2km, 입구→바다전망대→해월정→입구 원점회귀", "sameRouteConfirmed": False},
        {"m": 2500, "what": "부산시보 2012: 달맞이길~오솔길~어울마당 2.5km(40분 남짓), 민간 사이트 다섯 구간 합 2.5km", "sameRouteConfirmed": False},
    ],
    "orderedWaypointsFromText": ["입구(달맞이길 입구, 달빛 나들목)", "바다전망대", "해월정", "입구로 복귀"],
    "orderedWaypointsFromBusan2026": ["입구", "바다전망대", "어울마당", "해월정", "입구로 복귀"],
    "orderedWaypointsFromText2008": ["해운대해수욕장", "미포", "달맞이길", "달맞이동산 오솔길", "어울마당", "해월정"],
    "sources": [
        {"url": "https://www.busan.go.kr/news/storyreport/view?curPage=17&dataNo=72695", "readBy": "claude(페이지 HTML과 안내도 이미지를 직접 내려받아 확인)", "supports": "부산시보 이야기리포트(2026-04-20): '문탠로드 순환산책로는 입구부터 바다전망대, 어울마당, 해월정을 거쳐 다시 문탠로드 입구로 돌아오는 2.2km 원점회귀형 코스', 어울마당 갈림길에서 그린레일웨이로 연결 가능. 현장 안내도 이미지(총 2.2km)와 어울마당·바다전망대 사진 포함"},
        {"url": "https://www.visitbusan.net/kr/index.do?menuCd=DOM_000000202004001000&uc_seq=283&lang_cd=ko", "readBy": "claude(web-fetch)", "supports": "2.2km 원점회귀, 해월정 중간, 약 1시간"},
        {"url": "https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=526e40b0-7a3c-4cb9-9a28-44c999c89e45", "readBy": "claude(web-fetch)", "supports": "문탠로드 입구~바다전망대~해월정~입구 2.2km 원점회귀, 코스2는 바다전망대에서 철길 방향 청사포"},
        {"url": "https://www.busan.go.kr/news/specnews/view?bbsNo=6&dataNo=23836&srchThemeNo=15", "readBy": "claude(web-fetch)", "supports": "2008 안내: 해운대해수욕장~미포~달맞이길~달맞이동산 오솔길~어울마당~해월정 2.2km"},
        {"url": "https://www.busan.go.kr/news/specnews/view?bbsNo=6&dataNo=34061&srchThemeNo=60", "readBy": "claude(web-fetch)", "supports": "2.2km, 한 바퀴 40분, 달빛 나들목·바다전망대·어울마당·해월정"},
        {"url": "https://www.busan.go.kr/news/specnews/view?bbsNo=6&dataNo=38046&srchThemeNo=92", "readBy": "claude(web-fetch)", "supports": "2012: 달맞이길~오솔길~어울마당 2.5km, 달빛 나들목은 미포 육거리를 지난 뒤 시작, 비탈진 숲길, 해월정은 달맞이언덕 끝"},
        {"url": "https://www.visitbusan.net/index.do?menuCd=DOM_000000202002001000&uc_seq=1169&lang_cd=ko", "readBy": "claude(web-fetch)", "supports": "달맞이어울마당→사색의 오솔길(평평, 약 10분)→달맞이길 순서, 정류장에서 도보 약 10분"},
        {"url": "https://www.haeundae.go.kr/board/view.do?boardId=BBS_0000087&paging=ok&startPage=33&dataSid=28102", "readBy": "claude(web-fetch)", "supports": "문탠로드는 달맞이길 아래 소나무 숲 속 오솔길(흙길), 조명 설치"},
    ],
}


SIGNBOARD = {
    "source": "https://www.busan.go.kr/news/storyreport/view?curPage=17&dataNo=72695 (이미지 '문탠로드안내도 01.jpg', 640x489, 해운대구 관광문화과 안내판)",
    "printedTotalKm": 2.2,
    "sectionNamesAndKmAsRead": {"달빛 꽃잠길": 0.6, "달빛 가온길": 0.6, "달빛 바투길": 0.9, "달빛 함께길": 0.3, "달빛 만남길": 0.7},
    "sectionSumKm": 3.1,
    "caution": "저해상도 이미지에서 읽은 숫자라 확실하지 않다. 읽은 구간 길이 합(3.1km)이 인쇄된 총 2.2km와 맞지 않는다(숫자 오독이거나 구간이 겹치는 표기일 수 있음). 민간 사이트의 0.4/0.4/0.7/0.5/0.5(합 2.5km)와도 다르다. 이 숫자는 경로 계산에 쓰지 않았다.",
    "schematic": "안내판은 '현위치'(입구)에서 시작하는 고리 모양 개략도이며 해월정·어울마당·바다전망대 아이콘이 있다. 축척과 좌표가 없어 선형 근거로는 쓰지 않았고, 순서는 기사 본문 문장을 따랐다.",
    "seaViewpointPhoto": "기사 사진(어울마당 상, 바다전망대 하): 바다전망대는 지붕 있는 정자가 얹힌 넓은 목재 전망 데크. OSM의 정자(gazebo)와 전망점 노드가 후보이며 사진만으로 위치를 특정하지 못했다.",
}


def dist(a, b):
    return math.hypot((a[0] - b[0]) * KX, (a[1] - b[1]) * KY)


net = json.loads(NETWORK.read_text(encoding="utf-8"))
NODES = net["nodes"]
WAYS = {w["id"]: w for w in net["ways"]}


def way_length(w):
    return sum(dist(NODES[str(a)], NODES[str(b)]) for a, b in zip(w["nodes"], w["nodes"][1:]))


def classify(w):
    hw, fw = w["hw"], w.get("footway")
    if hw == "steps":
        return "steps"
    if hw == "pedestrian":
        return "pedestrian_plaza"
    if hw in ("path", "track") or (hw == "footway" and fw not in ("sidewalk", "crossing")):
        return "forest_or_walkway"
    if hw == "footway" and fw == "sidewalk":
        return "sidewalk"
    if hw == "footway" and fw == "crossing":
        return "crossing"
    return "road_unverified"  # service, residential 등: 보행 가능 여부 미확인


COST = {"forest_or_walkway": 1.0, "sidewalk": 1.0, "pedestrian_plaza": 1.0, "crossing": 1.2, "steps": 1.3, "road_unverified": 3.0}
ADJ = {}
for w in net["ways"]:
    kind = classify(w)
    for a, b in zip(w["nodes"], w["nodes"][1:]):
        a, b = str(a), str(b)
        length = dist(NODES[a], NODES[b])
        if not length:
            continue
        ADJ.setdefault(a, []).append((b, length, w["id"], kind))
        ADJ.setdefault(b, []).append((a, length, w["id"], kind))


def shortest(start, goal, allow_road=True, ban=frozenset()):
    heap, best, prev = [(0.0, start)], {start: 0.0}, {}
    while heap:
        cost, u = heapq.heappop(heap)
        if cost > best[u]:
            continue
        if u == goal:
            break
        for v, length, wid, kind in ADJ.get(u, []):
            if wid in ban or (kind == "road_unverified" and not allow_road):
                continue
            nc = cost + length * COST[kind]
            if nc < best.get(v, math.inf) - 1e-12:
                best[v] = nc
                prev[v] = (u, length, wid, kind)
                heapq.heappush(heap, (nc, v))
    if goal not in best:
        raise SystemExit("경로 없음: %s -> %s" % (start, goal))
    steps, u = [], goal
    while u != start:
        p, length, wid, kind = prev[u]
        steps.append((p, u, length, wid, kind))
        u = p
    steps.reverse()
    return steps


def pack(steps):
    """연속 노드 쌍을 같은 길 단위 구간으로 묶는다."""
    segs = []
    for a, b, length, wid, kind in steps:
        if segs and segs[-1]["wayId"] == wid:
            segs[-1]["nodes"].append(b)
            segs[-1]["lengthM"] += length
        else:
            w = WAYS[wid]
            segs.append({"wayId": wid, "hw": w["hw"], "class": kind, "name": w.get("name"), "surface": w.get("surface"),
                         "footway": w.get("footway"), "stepCount": STEP_COUNT.get(wid), "nodes": [a, b], "lengthM": length})
    for s in segs:
        s["lengthM"] = round(s["lengthM"], 2)
    return segs


def build_loop(order, legs_spec):
    legs, node_seq = [], []
    for name, s, t, opts in legs_spec:
        steps = shortest(s, t, **opts)
        segs = pack(steps)
        legs.append({"name": name, "from": s, "to": t, "lengthM": round(sum(x["lengthM"] for x in segs), 1), "segments": segs})
        for a, b, *_ in steps:
            node_seq.append(a)
        last = t
    node_seq.append(last)
    return legs, node_seq


def totals(legs, node_seq):
    by_class, total = Counter(), 0.0
    edge_use = Counter()
    for leg in legs:
        for s in leg["segments"]:
            by_class[s["class"]] += s["lengthM"]
            total += s["lengthM"]
    for a, b in zip(node_seq, node_seq[1:]):
        edge_use[tuple(sorted((a, b)))] += 1
    repeated = sum(dist(NODES[a], NODES[b]) for (a, b), n in edge_use.items() if n > 1 for _ in range(n - 1))
    return {"lengthM": round(total), "byClassM": {k: round(v) for k, v in sorted(by_class.items())},
            "repeatedTraversalM": round(repeated), "uniqueLengthM": round(total - repeated)}


def validate(node_seq):
    """모든 인접 좌표 쌍이 같은 OSM 길의 연속 노드인지 확인(직선 연결 금지)."""
    ok = 0
    for a, b in zip(node_seq, node_seq[1:]):
        if not any(((str(w["nodes"][i]), str(w["nodes"][i + 1])) in ((a, b), (b, a))) for wid in {x[2] for x in ADJ[a] if x[0] == b} for w in [WAYS[wid]] for i in range(len(w["nodes"]) - 1)):
            raise SystemExit("직선 연결 의심: %s -> %s" % (a, b))
        ok += 1
    return ok


def main():
    w = {x["key"]: x for x in WAYPOINTS}
    ENT, SEA, AUL, HAE = (w[k]["node"] for k in ("ENT", "SEA", "AUL", "HAE"))
    legs_spec = [
        ("1 입구→바다전망대(숲길)", ENT, SEA, {"allow_road": False}),
        ("2 바다전망대→어울마당(숲길 동쪽)", SEA, AUL, {"allow_road": False}),
        ("3 어울마당→해월정(달맞이길 보도)", AUL, HAE, {"allow_road": True, "ban": frozenset(FOREST_CHAIN)}),
        ("4 해월정→입구(달맞이길 보도)", HAE, ENT, {"allow_road": True, "ban": frozenset(FOREST_CHAIN)}),
    ]
    legs, node_seq = build_loop(None, legs_spec)
    checked = validate(node_seq)
    tot = totals(legs, node_seq)
    coords = [NODES[n] for n in node_seq]

    # 대안(채택하지 않음): 같은 계산으로 길이만 비교
    def length_of(spec):
        lg, ns = build_loop(None, spec)
        return round(sum(x["lengthM"] for x in lg))
    alt_out_and_back = length_of([
        ("a", ENT, SEA, {"allow_road": False}), ("b", SEA, ENT, {"allow_road": False}),
        ("c", ENT, HAE, {"allow_road": True, "ban": frozenset(FOREST_CHAIN)}), ("d", HAE, ENT, {"allow_road": True, "ban": frozenset(FOREST_CHAIN)})])
    alt_no_hae = length_of([("a", ENT, SEA, {"allow_road": False}), ("b", SEA, AUL, {"allow_road": False}), ("c", AUL, ENT, {"allow_road": True, "ban": frozenset(FOREST_CHAIN)})])
    alt_from_mipo = None
    MIPO = "436833273"
    pre = round(sum(s["lengthM"] for s in pack(shortest(MIPO, ENT))))

    # 바다전망대 후보와 경로까지의 최소 거리
    def seg_d(p, a, b):
        px, py, ax, ay, bx, by = p[0] * KX, p[1] * KY, a[0] * KX, a[1] * KY, b[0] * KX, b[1] * KY
        dx, dy = bx - ax, by - ay
        l2 = dx * dx + dy * dy
        t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / l2)) if l2 else 0
        return math.hypot(px - ax - t * dx, py - ay - t * dy)
    route_xy = list(zip(coords, coords[1:]))
    cum = [0.0]
    for a, b in route_xy:
        cum.append(cum[-1] + dist(a, b))
    def to_route(p):
        return round(min(seg_d(p, a, b) for a, b in route_xy), 1)
    def along(p):
        """경로 시작(입구)부터 점에서 가장 가까운 지점까지의 누적 거리(m). 같은 거리면 처음 지나는 위치."""
        best = (math.inf, 0.0)
        for i, (a, b) in enumerate(route_xy):
            d = seg_d(p, a, b)
            if d < best[0] - 1e-9:
                ax, ay, bx, by = a[0] * KX, a[1] * KY, b[0] * KX, b[1] * KY
                dx, dy = bx - ax, by - ay
                l2 = dx * dx + dy * dy
                t = max(0, min(1, ((p[0] * KX - ax) * dx + (p[1] * KY - ay) * dy) / l2)) if l2 else 0
                best = (d, cum[i] + t * math.sqrt(l2))
        return round(best[1])
    candidates = [
        {"osm": "node 12200897194 (gazebo)", "lonlat": [129.18103, 35.15584], "toRouteM": to_route((129.18103, 35.15584)), "alongRouteM": along((129.18103, 35.15584)), "chosen": True},
        {"osm": "node 13708102776 (viewpoint)", "lonlat": [129.18274, 35.15623], "toRouteM": to_route((129.18274, 35.15623)), "alongRouteM": along((129.18274, 35.15623)), "chosen": False},
        {"osm": "node 12062982621 '해월전망대'(viewpoint, 해운대블루라인 철길 쪽)", "lonlat": [129.18281, 35.15572], "toRouteM": to_route((129.18281, 35.15572)), "alongRouteM": along((129.18281, 35.15572)), "chosen": False},
        {"osm": "node 4943784522 (viewpoint, 해월정 옆)", "lonlat": [129.18173, 35.15678], "toRouteM": to_route((129.18173, 35.15678)), "alongRouteM": along((129.18173, 35.15678)), "chosen": False},
        {"osm": "node 13708102763 (viewpoint, 어울마당 쪽)", "lonlat": [129.18452, 35.15797], "toRouteM": to_route((129.18452, 35.15797)), "alongRouteM": along((129.18452, 35.15797)), "chosen": False},
    ]
    for wp in WAYPOINTS:
        n = NODES[wp["node"]]
        wp["lonlat"] = n
        wp["alongRouteM"] = along(tuple(n)) if wp["key"] not in ("HAE",) else None
    # 해월정은 곁길 끝이라 처음 도달하는 누적 거리를 구간 합으로 계산한다
    wp_hae = next(x for x in WAYPOINTS if x["key"] == "HAE")
    wp_hae["alongRouteM"] = round(sum(l["lengthM"] for l in legs[:3]))
    next(x for x in WAYPOINTS if x["key"] == "ENT")["alongRouteM"] = 0
    next(x for x in WAYPOINTS if x["key"] == "AUL")["alongRouteM"] = round(sum(l["lengthM"] for l in legs[:2]))
    next(x for x in WAYPOINTS if x["key"] == "SEA")["alongRouteM"] = round(legs[0]["lengthM"])

    # 교체 전 현재 코스(고정 way ID 기준)와 비교
    cur_ids = list(BASELINE_CURRENT_WAY_IDS)
    route_way_len = Counter()
    for leg in legs:
        for s_ in leg["segments"]:
            route_way_len[s_["wayId"]] += s_["lengthM"]
    cur_total = sum(way_length(WAYS[i]) for i in cur_ids if i in WAYS)
    cur_used = sum(min(way_length(WAYS[i]), route_way_len[i]) for i in cur_ids if i in route_way_len)
    cur_unused = {i: round(way_length(WAYS[i])) for i in cur_ids if i in WAYS and i not in route_way_len}
    cur_partial = {i: {"wayM": round(way_length(WAYS[i])), "usedByRouteM": round(route_way_len[i])} for i in cur_ids if i in WAYS and i in route_way_len and route_way_len[i] < way_length(WAYS[i]) - 1}
    new_only = {i: round(v) for i, v in route_way_len.items() if i not in cur_ids}

    route_way_ids = set(route_way_len)
    node_pos = {}
    run = 0.0
    for i, n in enumerate(node_seq):
        if i:
            run += dist(NODES[node_seq[i - 1]], NODES[n])
        node_pos.setdefault(n, round(run))
    branches = []
    for n, pos in sorted(node_pos.items(), key=lambda x: x[1]):
        side = [(wid, k) for v, l, wid, k in ADJ.get(n, []) if wid not in route_way_ids]
        side = sorted({(wid, WAYS[wid]["hw"], WAYS[wid].get("name"), round(way_length(WAYS[wid]))) for wid, k in side})
        keep = [{"wayId": a, "hw": b, "name": c, "lengthM": d} for a, b, c, d in side if b in ("steps", "path", "footway", "pedestrian", "track") and d >= 15]
        if keep:
            branches.append({"node": n, "alongRouteM": pos, "lonlat": NODES[n], "ways": keep})
    # 공유 노드는 없지만 경로 노드와 25m 안에 끝점이 있는 보행 길(연결 후보, 이어 붙이지 않음)
    near_miss = []
    route_nodes = set(node_pos)
    for w in net["ways"]:
        if w["id"] in route_way_ids or w["hw"] not in ("steps", "path", "footway", "pedestrian", "track") or any(str(n) in route_nodes for n in w["nodes"]):
            continue
        for end in (w["nodes"][0], w["nodes"][-1]):
            best = min(((dist(NODES[str(end)], NODES[rn]), rn) for rn in route_nodes), key=lambda x: x[0])
            if best[0] <= 25:
                near_miss.append({"wayId": w["id"], "hw": w["hw"], "lengthM": round(way_length(w)), "endNode": str(end), "routeNode": best[1], "gapM": round(best[0], 1),
                                  "alongRouteM": node_pos[best[1]], "linked": False, "note": "공유 노드 없음, 직선으로 잇지 않음"})
    near_miss.sort(key=lambda x: x["gapM"])
    features = []
    order = 0
    for li, leg in enumerate(legs, 1):
        for s in leg["segments"]:
            order += 1
            features.append({"type": "Feature", "properties": {"layer": "course", "code": "HD-MOONTAN", "order": order, "leg": li, "legName": leg["name"],
                             "wayId": s["wayId"], "sourceWayIds": [s["wayId"]], "highway": s["hw"], "class": s["class"], "name": s["name"], "surface": s["surface"],
                             "stepCount": s["stepCount"], "lengthM": s["lengthM"], "roadAccessUnverified": s["class"] == "road_unverified", "fieldVerified": False},
                             "geometry": {"type": "LineString", "coordinates": [NODES[n] for n in s["nodes"]]}})
    for wp in WAYPOINTS:
        features.append({"type": "Feature", "properties": {"layer": "waypoint", "code": "HD-MOONTAN", "key": wp["key"], "name": wp["name"], "node": wp["node"],
                         "alongRouteM": wp["alongRouteM"], "confidence": wp["confidence"], "fieldVerified": False},
                         "geometry": {"type": "Point", "coordinates": wp["lonlat"]}})

    metadata = {
        "meta": {
            "generator": "scripts/build-moontan-route.py", "network": "public/data/haeundae/walking-network.json (OSM extract 2026-11-27)",
            "kind": "ordered_loop_from_shared_osm_nodes", "fieldVerified": False,
            "rules": ["직선 연결 없음(모든 인접 쌍이 같은 OSM 길의 연속 노드, 검증 %d쌍)" % checked, "공식 거리에 맞추지 않음", "도로·계단·보도 구분, 도로는 보행 가능 미확인"],
        },
        "waypoints": WAYPOINTS,
        "legs": legs,
        "coordinates": coords,
        "nodeIds": node_seq,
        "closure": {"startNode": node_seq[0], "endNode": node_seq[-1], "closed": node_seq[0] == node_seq[-1], "gapM": round(dist(coords[0], coords[-1]), 3)},
        "totals": tot,
        "officialComparison": {
            "official": OFFICIAL,
            "distanceOnly": {"routeOver2200": round(tot["lengthM"] / 2200, 3), "routeOver2500": round(tot["lengthM"] / 2500, 3),
                             "note": "거리 비교일 뿐 같은 노선이라는 증명이 아니다. 거리에 맞추려고 구간을 넣거나 빼지 않았다."},
        },
        "alternatives": {
            "chosen": {"what": "입구→바다전망대→어울마당(숲길 동쪽)→해월정(달맞이길 보도)→입구", "lengthM": tot["lengthM"]},
            "outAndBackNoAulMadang": {"what": "입구→바다전망대→입구→해월정→입구(어울마당 제외, 공식 KTO 문구에 가까움)", "lengthM": alt_out_and_back},
            "loopWithoutHaewoljeongSpur": {"what": "해월정 곁길을 뺀 순환", "lengthM": alt_no_hae},
            "fromMipoOgeori": {"what": "미포 오거리(달맞이길 시작)에서 들머리까지 보도 한쪽 길이", "oneWayM": pre, "note": "미포 오거리부터 출발하면 왕복 약 %d m가 더해진다" % (2 * pre)},
        },
        "seaViewpointCandidates": candidates,
        "currentGeometryComparison": {
            "baselineCourseWayIds": cur_ids, "baselineSource": "교체 전 HD-MOONTAN 코스(이름 문텐로드 way 19개)의 고정 way ID. public 코스 파일은 읽지 않는다", "currentLengthM": round(cur_total), "currentLengthUsedByRouteM": round(cur_used),
            "currentWaysNotInRoute": cur_unused, "currentWaysPartiallyUsed": cur_partial, "currentUnusedLengthM": round(cur_total - cur_used), "routeOnlyWays(lengthM)": new_only,
            "note": "현재 코스는 숲길 한 줄(약 1.45km)이며 되돌아오는 보도 구간과 어울마당·해월정 접속이 없다.",
        },
        "uncertainty": {
            "pending": "현장 확인 없음(fieldVerified false)",
            "seaViewpointIdentity": "OSM에 '바다전망대' 이름이 없어 경로 위 전망 정자를 추정함(후보 5개 중 경로까지 거리 표 참고)",
            "entranceLocation": "공식 입구 좌표를 확인하지 못함. 들머리는 숲길 이름이 시작되는 보도 접속 노드(미포 오거리에서 약 %d m)" % pre,
            "roadClassUnverified": "service 도로 구간은 보행 가능 여부 미확인(totals.byClassM.road_unverified)",
            "spur": "해월정은 보도에서 광장 고리로 들어가는 곁길이라 오가는 구간이 반복된다(repeatedTraversalM)",
            "surface/steps": "계단 개수는 PBF 태그가 있는 129603749(19개)만 알려져 있음",
            "dataDate": "OSM 2026-11-27 추출본, 이후 수정 미반영",
        },
        "branches": branches,
        "nearMissesNotLinked": near_miss,
        "signboard": SIGNBOARD,
    }
    # 이전 판의 최상위 키(meta·waypoints·legs·route·coordinates·nodeIds·closure·totals 등)를 그대로 유지해 기존 통합 코드가 깨지지 않게 하고,
    # 같은 내용을 FeatureCollection(features, metadata)으로도 제공한다.
    out = {"type": "FeatureCollection", "features": features, "metadata": metadata}
    out.update(metadata)
    out["route"] = {"type": "FeatureCollection", "features": [f for f in features if f["properties"]["layer"] == "course"]}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({"out": str(OUT.relative_to(ROOT)), "lengthM": tot["lengthM"], "byClassM": tot["byClassM"], "repeatedTraversalM": tot["repeatedTraversalM"],
                      "closed": metadata["closure"]["closed"], "legs": [(l["name"], l["lengthM"]) for l in legs], "validatedPairs": checked,
                      "alternatives": {k: v.get("lengthM") for k, v in metadata["alternatives"].items()}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
