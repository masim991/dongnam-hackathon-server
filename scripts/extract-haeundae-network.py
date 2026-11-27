"""해운대구 보행 네트워크를 OSM 추출본(PBF)에서 추출한다. 노드 ID와 원본 좌표를 그대로 두고, 단순화·짧은 길 제거를 하지 않는다.
사용: <venv>/python scripts/extract-haeundae-network.py <입력.pbf> [출력 JSON] [상세 보고서 JSON] [갈맷길 관계 JSON]
  기본 출력: public/data/haeundae/walking-network.json, .wrangler/network/network-report.json, .wrangler/network/galmaetgil-relations.json
연결 규칙: OSM 노드 ID를 공유하는 길만 이어진 것으로 본다. 가까운 끝점은 proposedLinks(verified:false)로만 보고하고 네트워크에는 넣지 않는다.
도로(residential 등)는 walk='unverified'(보행 가능 여부 미확인)로만 표시한다. motorway/trunk는 제외, foot=no·access=private|no는 restricted 로 분리한다.
"""
import json
import math
import os
import sys
import time
from collections import defaultdict

import osmium
from shapely.geometry import LineString, Point
from shapely.ops import polygonize, unary_union
from shapely.prepared import prep
from shapely.strtree import STRtree

BBOX = (129.07, 35.12, 129.32, 35.32)  # 서, 남, 동, 북: 해운대구와 여유를 덮는 넉넉한 사각형(실제 경계는 관계 다각형으로 자른다)
MARGIN_M = 150
LAT0 = 35.2
KLA = 111320.0
KLO = 111320.0 * math.cos(math.radians(LAT0))
WALK_YES = {"footway", "path", "pedestrian", "steps", "track", "cycleway", "bridleway"}
WALK_UNVERIFIED = {"living_street", "residential", "service", "unclassified", "tertiary", "tertiary_link", "secondary", "secondary_link", "primary", "primary_link", "road"}
DROPPED = {"motorway", "motorway_link", "trunk", "trunk_link", "proposed", "construction", "raceway", "bus_guideway", "elevator", "corridor"}
KEEP_TAGS = ["name", "ref", "footway", "sidewalk", "surface", "bridge", "tunnel", "lit", "incline", "foot", "bicycle", "oneway", "service", "access", "layer", "crossing"]
NODE_TAGS = ["highway", "crossing", "crossing:markings", "kerb", "tactile_paving", "traffic_signals", "button_operated", "bicycle", "foot"]
PROXIMITY_M = 30


def xy(lon, lat):
    return ((lon - 129.2) * KLO, (lat - LAT0) * KLA)


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def in_bbox(lon, lat):
    return BBOX[0] <= lon <= BBOX[2] and BBOX[1] <= lat <= BBOX[3]


def pass_relations(src):
    boundaries, gal = [], []
    for r in osmium.FileProcessor(src, osmium.osm.RELATION):
        t = r.tags
        name = t.get("name", "")
        if t.get("boundary") == "administrative" and name == "해운대구":
            boundaries.append({"id": r.id, "adminLevel": t.get("admin_level"), "ways": [m.ref for m in r.members if m.type == "w"]})
        text = " ".join(t.get(k, "") for k in ("name", "name:ko", "ref", "alt_name", "official_name", "description"))
        if "갈맷길" in text:
            gal.append({"id": r.id, "tags": {k.k: k.v for k in t}, "members": [{"type": m.type, "ref": m.ref, "role": m.role} for m in r.members]})
    return boundaries, gal


def main(src, out_json, out_report, out_gal):
    t0 = time.time()
    log("1/4 관계(해운대구 경계, 갈맷길) 찾는 중")
    boundaries, gal = pass_relations(src)
    if not boundaries:
        sys.exit("해운대구 행정경계 관계를 PBF에서 찾지 못했습니다.")
    boundaries.sort(key=lambda b: (b["adminLevel"] != "6", b["id"]))
    boundary = boundaries[0]
    log(f"경계 관계 {boundary['id']} (admin_level={boundary['adminLevel']}, 후보 {len(boundaries)}개), 갈맷길 관계 {len(gal)}개")
    wanted = set(boundary["ways"]) | {m["ref"] for g in gal for m in g["members"] if m["type"] == "w"}

    log("2/4 길·표시 노드 읽는 중(전체 파일 1회)")
    ways, special, node_tags = {}, {}, {}
    seen = 0
    fp = osmium.FileProcessor(src, osmium.osm.NODE | osmium.osm.WAY).with_locations()
    for o in fp:
        if o.is_node():
            if len(o.tags) and o.location.valid() and in_bbox(o.location.lon, o.location.lat):
                tags = {k: o.tags[k] for k in NODE_TAGS if k in o.tags}
                if tags and (tags.get("highway") in ("crossing", "traffic_signals") or "crossing" in tags or "kerb" in tags):
                    node_tags[o.id] = {"loc": (o.location.lon, o.location.lat), **tags}
            continue
        seen += 1
        hw = o.tags.get("highway")
        need_special = o.id in wanted
        if not need_special and (hw is None or not (hw in WALK_YES or hw in WALK_UNVERIFIED)):
            continue
        try:
            nodes = [(n.ref, n.lon, n.lat) for n in o.nodes]
        except osmium.InvalidLocationError:
            continue
        if not need_special and not any(in_bbox(lon, lat) for _, lon, lat in nodes):
            continue
        rec = {"id": o.id, "hw": hw, "tags": {k: o.tags[k] for k in KEEP_TAGS if k in o.tags}, "nodes": nodes}
        if need_special:
            special[o.id] = nodes
        if hw in WALK_YES or hw in WALK_UNVERIFIED:
            ways[o.id] = rec
    log(f"읽은 길 {seen:,}개, 후보 길 {len(ways):,}개, 표시 노드 {len(node_tags):,}개 ({int(time.time() - t0)}초)")

    log("3/4 해운대구 경계 만들고 자르는 중")
    lines = [LineString([xy(lon, lat) for _, lon, lat in special[w]]) for w in boundary["ways"] if w in special]
    polys = list(polygonize(unary_union(lines)))
    if not polys:
        sys.exit("경계 길을 닫힌 다각형으로 만들지 못했습니다(경계 길 일부가 PBF에 없음).")
    gu = unary_union(polys)
    gu_p, margin_p = prep(gu), prep(gu.buffer(MARGIN_M))
    log(f"경계 다각형 면적 {gu.area / 1e6:.1f} km², 여유 {MARGIN_M} m")

    net, restricted = [], []
    dropped = defaultdict(int)
    for w in ways.values():
        ln = LineString([xy(lon, lat) for _, lon, lat in w["nodes"]])
        if gu_p.intersects(ln):
            inside = True
        elif margin_p.intersects(ln):
            inside = False
        else:
            continue
        tags = w["tags"]
        blocked = tags.get("foot") == "no" or (tags.get("access") in ("private", "no") and tags.get("foot") not in ("yes", "designated", "permissive"))
        rec = {"id": w["id"], "hw": w["hw"], "walk": "yes" if w["hw"] in WALK_YES else "unverified", "in": inside, "nodes": [n for n, _, _ in w["nodes"]]}
        rec.update({k: v for k, v in tags.items() if k not in ("access",) or blocked})
        (restricted if blocked else net).append((rec, ln, w["nodes"]))

    # 네트워크 구성
    nodes_out, node_deg = {}, defaultdict(int)
    for rec, ln, nodes in net:
        for i, (nid, lon, lat) in enumerate(nodes):
            nodes_out[nid] = [lon, lat]
        for nid in (rec["nodes"][0], rec["nodes"][-1]):
            node_deg[nid] += 1
    node_use = defaultdict(int)
    for rec, _, _ in net:
        for nid in rec["nodes"]:
            node_use[nid] += 1
    node_tags_out = {str(nid): {k: v for k, v in t.items() if k != "loc"} for nid, t in node_tags.items() if nid in nodes_out}

    # 연결 분석: 노드 ID를 공유하는 길끼리만 연결(합집합-찾기)
    parent = {}
    def find(a):
        while parent.setdefault(a, a) != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb
    for rec, _, _ in net:
        first = rec["nodes"][0]
        find(first)
        for nid in rec["nodes"][1:]:
            union(first, nid)
    comp_ways = defaultdict(list)
    for rec, _, _ in net:
        comp_ways[find(rec["nodes"][0])].append(rec)
    comps = sorted(comp_ways.values(), key=len, reverse=True)
    core_only = defaultdict(int)  # 도로 없이 보행 길만으로 본 연결 요소
    parent2 = {}
    def find2(a):
        while parent2.setdefault(a, a) != a:
            parent2[a] = parent2[parent2[a]]
            a = parent2[a]
        return a
    for rec, _, _ in net:
        if rec["walk"] != "yes":
            continue
        for nid in rec["nodes"][1:]:
            ra, rb = find2(rec["nodes"][0]), find2(nid)
            if ra != rb:
                parent2[ra] = rb
    core_comp = defaultdict(int)
    for rec, _, _ in net:
        if rec["walk"] == "yes":
            core_comp[find2(rec["nodes"][0])] += 1

    # 끊김 후보: 보행 길의 끝점이 다른 길의 노드를 공유하지 않는데 30 m 안에 다른 길이 있는 곳(보고만 함)
    log("4/4 끊긴 끝점 후보 계산 중")
    lines_idx = [ln for _, ln, _ in net]
    tree = STRtree(lines_idx)
    way_index = {id(ln): i for i, ln in enumerate(lines_idx)}
    dangling, proposed = [], []
    nodeways = defaultdict(set)
    for i, (rec, _, _) in enumerate(net):
        for nid in rec["nodes"]:
            nodeways[nid].add(i)
    for i, (rec, ln, nodes) in enumerate(net):
        if rec["walk"] != "yes" or not rec["in"]:
            continue
        if rec["nodes"][0] == rec["nodes"][-1]:
            continue
        for nid, (lon, lat) in ((rec["nodes"][0], nodes[0][1:]), (rec["nodes"][-1], nodes[-1][1:])):
            if len(nodeways[nid]) > 1:
                continue
            dangling.append({"node": nid, "way": rec["id"], "lon": lon, "lat": lat})
            p = Point(xy(lon, lat))
            best = None
            for j in tree.query(p.buffer(PROXIMITY_M)):
                j = int(j)
                if j == i or nid in net[j][0]["nodes"]:
                    continue
                d = net[j][1].distance(p)
                if d <= PROXIMITY_M and (best is None or d < best[0]):
                    best = (d, j)
            if best:
                d, j = best
                tgt = net[j][0]
                proposed.append({"fromNode": nid, "fromWay": rec["id"], "toWay": tgt["id"], "toHw": tgt["hw"], "distM": round(d, 1), "sameComponent": find(rec["nodes"][0]) == find(tgt["nodes"][0]), "verified": False, "lon": lon, "lat": lat})

    def length_km(items):
        return round(sum(ln.length for _, ln, _ in items) / 1000, 1)
    by_hw = defaultdict(int)
    for rec, _, _ in net:
        by_hw[rec["hw"]] += 1
    counts = {
        "ways": len(net), "waysInGu": sum(1 for r, _, _ in net if r["in"]), "waysMarginOnly": sum(1 for r, _, _ in net if not r["in"]),
        "walkYes": sum(1 for r, _, _ in net if r["walk"] == "yes"), "walkUnverified": sum(1 for r, _, _ in net if r["walk"] == "unverified"),
        "restricted": len(restricted), "nodes": len(nodes_out), "taggedNodes": len(node_tags_out), "byHighway": dict(sorted(by_hw.items(), key=lambda x: -x[1])),
        "lengthKmWalkYes": length_km([x for x in net if x[0]["walk"] == "yes"]), "lengthKmRoadUnverified": length_km([x for x in net if x[0]["walk"] == "unverified"]),
    }
    meta = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "source": {"file": os.path.basename(src), "bytes": os.path.getsize(src), "geofabrikLastModified": "Fri, 27 Nov 2026 03:45:13 GMT (HTTP header when downloaded)"},
        "boundary": {"relationId": boundary["id"], "name": "해운대구", "adminLevel": boundary["adminLevel"], "marginM": MARGIN_M, "areaKm2": round(gu.area / 1e6, 1)},
        "counts": counts,
        "rules": {"walkYes": sorted(WALK_YES), "walkUnverified": sorted(WALK_UNVERIFIED), "dropped": sorted(DROPPED), "connect": "OSM 노드 ID 공유만", "simplification": "없음", "minLength": "없음"},
        "limits": ["OSM 입력 기준이며 현장 검증이 아님", "walk='unverified'(도로)는 보행 가능 여부를 확인하지 않음", "끊긴 끝점 후보(proposedLinks)는 보고용이며 연결로 쓰지 않음", "in=false 는 구 경계 밖 여유 구간(연결 확인용)"],
    }
    os.makedirs(os.path.dirname(out_json), exist_ok=True)
    os.makedirs(os.path.dirname(out_report), exist_ok=True)
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump({"meta": meta, "nodes": {str(k): v for k, v in nodes_out.items()}, "nodeTags": node_tags_out,
                   "ways": [r for r, _, _ in net], "restricted": [r for r, _, _ in restricted]}, f, ensure_ascii=False, separators=(",", ":"))
    top = [len(c) for c in comps[:10]]
    report = {
        "meta": meta, "components": {"all": len(comps), "largestWays": top, "singletons": sum(1 for c in comps if len(c) == 1),
                                     "walkYesOnly": len(core_comp), "walkYesOnlyLargest": sorted(core_comp.values(), reverse=True)[:10]},
        "danglingWalkEnds": len(dangling), "proposedLinks": {"count": len(proposed), "le5m": sum(1 for p in proposed if p["distM"] <= 5), "le15m": sum(1 for p in proposed if 5 < p["distM"] <= 15), "le30m": sum(1 for p in proposed if 15 < p["distM"] <= 30),
                                                           "diffComponent": sum(1 for p in proposed if not p["sameComponent"]), "toRoad": sum(1 for p in proposed if p["toHw"] in WALK_UNVERIFIED)},
        "proposedLinksList": proposed, "danglingList": dangling[:2000],
    }
    with open(out_report, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, separators=(",", ":"))

    gal_out = []
    for g in gal:
        geom = {m["ref"]: [[lon, lat] for _, lon, lat in special[m["ref"]]] for m in g["members"] if m["type"] == "w" and m["ref"] in special}
        in_net = [m["ref"] for m in g["members"] if m["type"] == "w" and m["ref"] in {r["id"] for r, _, _ in net}]
        gal_out.append({**g, "wayGeometry": geom, "memberWays": sum(1 for m in g["members"] if m["type"] == "w"), "memberWaysWithGeometry": len(geom), "memberWaysInNetwork": len(in_net)})
    os.makedirs(os.path.dirname(out_gal), exist_ok=True)
    with open(out_gal, "w", encoding="utf-8") as f:
        json.dump({"found": len(gal_out), "relations": gal_out}, f, ensure_ascii=False, separators=(",", ":"))
    log(f"끝: 길 {len(net):,}개 / 제한 {len(restricted)}개 / 노드 {len(nodes_out):,}개 / 갈맷길 관계 {len(gal_out)}개 ({int(time.time() - t0)}초)")
    print(json.dumps({"counts": counts, "components": report["components"], "danglingWalkEnds": report["danglingWalkEnds"], "proposedLinks": report["proposedLinks"], "galmaetgilRelations": [{"id": g["id"], "name": g["tags"].get("name"), "ref": g["tags"].get("ref"), "memberWays": g["memberWays"], "inNetwork": g["memberWaysInNetwork"]} for g in gal_out]}, ensure_ascii=False))


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    # osmium 은 한글이 들어간 절대 경로를 열지 못하므로 PBF 는 현재 폴더 기준 상대 경로로 둔다(프로젝트 루트에서 실행).
    src = sys.argv[1] if len(sys.argv) > 1 else ".wrangler/osm/south-korea-latest.osm.pbf"
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(root, "public/data/haeundae/walking-network.json")
    rep = sys.argv[3] if len(sys.argv) > 3 else os.path.join(root, ".wrangler/network/network-report.json")
    galp = sys.argv[4] if len(sys.argv) > 4 else os.path.join(root, ".wrangler/network/galmaetgil-relations.json")
    main(src, out, rep, galp)
