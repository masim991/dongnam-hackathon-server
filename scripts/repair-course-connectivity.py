"""시범 코스의 추정 직선 연결을 OSM 원본 네트워크 위의 실제 선형으로 교체한다.
실행: python scripts/repair-course-connectivity.py [로컬 API 주소]
공유 노드만 경유하며 도로 중간 접속은 원본 선분에 0.3m 이내인 경우만 허용한다.
"""
import heapq
import copy
import json
import math
from pathlib import Path
import sys
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


def xy(p):
    return (p[0] * 91000, p[1] * 111320)


def distance(a, b):
    ax, ay = xy(a)
    bx, by = xy(b)
    return math.hypot(ax - bx, ay - by)


def graph_from_network(network):
    nodes = network['nodes']
    graph, edges = {}, []
    for way in network['ways']:
        for a, b in zip(way['nodes'], way['nodes'][1:]):
            a, b = str(a), str(b)
            length = distance(nodes[a], nodes[b])
            if not length:
                continue
            weight = 1 if way['walk'] == 'yes' else 2
            edge = (a, b, length, weight, way['id'], way['walk'])
            edges.append(edge)
            graph.setdefault(a, []).append((b, length * weight, way['id'], way['walk']))
            graph.setdefault(b, []).append((a, length * weight, way['id'], way['walk']))
    return nodes, graph, edges


def network_path(start, finish, network_graph, max_cost=500):
    nodes, base_graph, edges = network_graph
    graph = {n: list(v) for n, v in base_graph.items()}
    coords = dict(nodes)
    for key, point in [('start', start), ('finish', finish)]:
        px, py = xy(point)
        best = None
        for a, b, length, weight, way, walk in edges:
            ax, ay = xy(nodes[a])
            bx, by = xy(nodes[b])
            dx, dy = bx - ax, by - ay
            t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (length * length)))
            error = math.hypot(px - ax - t * dx, py - ay - t * dy)
            if best is None or error < best[0]:
                best = (error, a, b, length, weight, way, walk, t)
        if not best or best[0] > 0.3:
            return None
        error, a, b, length, weight, way, walk, t = best
        coords[key] = [(1 - t) * nodes[a][0] + t * nodes[b][0], (1 - t) * nodes[a][1] + t * nodes[b][1]]
        graph[key] = []
        for n, part in [(a, t * length), (b, (1 - t) * length)]:
            graph[key].append((n, part * weight, way, walk))
            graph[n].append((key, part * weight, way, walk))
        # 양 끝이 같은 원본 선분에 있으면 그 선분 위에서 바로 이동한다.
        if key == 'start':
            start_edge = (a, b, t, length, weight, way, walk)
        elif (a, b) == start_edge[:2]:
            part = abs(t - start_edge[2]) * length * weight
            graph['start'].append(('finish', part, way, walk))
    todo, costs, previous = [(0, 'start')], {'start': 0}, {}
    while todo:
        cost, node = heapq.heappop(todo)
        if cost != costs[node]:
            continue
        if node == 'finish':
            break
        for neighbor, part, way, walk in graph[node]:
            new = cost + part
            if new <= max_cost and new < costs.get(neighbor, math.inf):
                costs[neighbor] = new
                previous[neighbor] = (node, way, walk)
                heapq.heappush(todo, (new, neighbor))
    if 'finish' not in costs:
        return None
    chain, ways, unverified = ['finish'], [], False
    while chain[-1] != 'start':
        node, way, walk = previous[chain[-1]]
        chain.append(node)
        ways.append(way)
        unverified |= walk != 'yes'
    points = [coords[n] for n in reversed(chain)]
    points = [p for i, p in enumerate(points) if not i or distance(points[i - 1], p) > 0.001]
    return {'coordinates': points, 'wayIds': list(dict.fromkeys(reversed(ways))), 'roadAccessUnverified': unverified,
            'lengthM': sum(distance(a, b) for a, b in zip(points, points[1:]))}


def outer_source_cycle(features, network):
    """기존 경유 경로에서 면적이 가장 큰 실제 순환을 고르고 왕복 지선을 제거한다."""
    chain = []
    for feature in features:
        for point in feature['geometry']['coordinates']:
            key = tuple(round(v, 7) for v in point)
            if not chain or key != chain[-1][0]:
                chain.append((key, feature['properties']))
    stack, incoming, where, cycles = [], [], {}, []
    for point, props in chain:
        if point in where:
            cut = where[point]
            points = stack[cut:] + [point]
            origin = xy(points[0])
            local = [(xy(p)[0] - origin[0], xy(p)[1] - origin[1]) for p in points]
            area = abs(sum(a[0] * b[1] - a[1] * b[0] for a, b in zip(local, local[1:]))) / 2
            if area > 1:
                cycles.append((area, points, incoming[cut:] + [props]))
            for old in stack[cut + 1:]:
                where.pop(old)
            stack, incoming = stack[:cut + 1], incoming[:cut]
        else:
            if stack:
                incoming.append(props)
            where[point] = len(stack)
            stack.append(point)
    if not cycles or max(c[0] for c in cycles) < 10000:
        raise ValueError('동백섬 전체를 도는 원본 순환 경로를 확인하지 못했습니다')
    _, points, properties = max(cycles, key=lambda c: c[0])
    ways = {w['id']: w for w in network['ways']}
    def on_segment(point, a, b):
        px, py = xy(point); ax, ay = xy(a); bx, by = xy(b)
        dx, dy = bx - ax, by - ay
        t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy))) if dx or dy else 0
        return math.hypot(px - ax - t * dx, py - ay - t * dy) < .03
    result = []
    for a, b, props in zip(points, points[1:], properties):
        matched = []
        for way_id in props['sourceWayIds']:
            way = ways[way_id]
            for v, w in zip(way['nodes'], way['nodes'][1:]):
                c, d = network['nodes'][str(v)], network['nodes'][str(w)]
                if on_segment(a, c, d) and on_segment(b, c, d):
                    matched.append(way_id)
                    break
        if not matched:
            raise ValueError('순환선에 원본 OSM 선분으로 설명되지 않는 연결이 있습니다')
        unverified = any(ways[w]['walk'] != 'yes' for w in matched)
        if result and result[-1]['properties']['sourceWayIds'] == matched and result[-1]['properties']['roadAccessUnverified'] == unverified:
            result[-1]['geometry']['coordinates'].append(list(b))
        else:
            result.append({'type': 'Feature', 'properties': {**props, 'sourceWayIds': matched, 'roadAccessUnverified': unverified},
                'geometry': {'type': 'LineString', 'coordinates': [list(a), list(b)]}})
    for feature in result:
        coords = feature['geometry']['coordinates']
        feature['properties']['lengthM'] = round(sum(distance(a, b) for a, b in zip(coords, coords[1:])), 2)
    return result


def dongbaek_park_loop(network):
    way = next(w for w in network['ways'] if w['id'] == 239279807)
    coords = [network['nodes'][str(n)] for n in way['nodes']]
    # 이 way의 시작 진입 지선 뒤에서 같은 노드가 반복되며 실제 공원 순환이 닫힌다.
    feature = {'type': 'Feature', 'properties': {'layer': 'course', 'code': 'HD-DONGBAEK',
        'name': '동백공원 순환도로', 'highway': 'network_connector', 'sourceWayIds': [239279807],
        'roadAccessUnverified': True}, 'geometry': {'type': 'LineString', 'coordinates': coords}}
    return outer_source_cycle([feature], network)


def main(base):
    network = json.loads((ROOT / 'public/data/haeundae/walking-network.json').read_text(encoding='utf-8'))
    network_graph = graph_from_network(network)
    def get(path):
        with urlopen(base.rstrip('/') + path, timeout=15) as response:
            return json.load(response)
    catalog = get('/api/courses')
    out = ROOT / 'public/data/haeundae/courses'
    out.mkdir(parents=True, exist_ok=True)
    report, additions = [], []
    for course in catalog['courses']:
        fc = get('/api/courses/' + course['code'] + '/geojson')
        restored, restored_ends = [], []
        if course['code'] == 'HD-DONGBAEK':
            # 기존 큐레이션의 모든 인접 점을 원본 공유 노드 경로로 재구성한다.
            original = next(f for f in fc['features'] if f['properties'].get('layer') == 'course')
            rebuilt = []
            for a, b in zip(original['geometry']['coordinates'], original['geometry']['coordinates'][1:]):
                result = network_path(a, b, network_graph, max_cost=500)
                if not result:
                    raise ValueError('동백섬 원본 네트워크 접속을 확인하지 못했습니다')
                rebuilt.append({'type': 'Feature', 'properties': {**original['properties'],
                    'highway': 'network_connector', 'sourceWayIds': result['wayIds'],
                    'roadAccessUnverified': result['roadAccessUnverified'], 'lengthM': round(result['lengthM'], 2)},
                    'geometry': {'type': 'LineString', 'coordinates': result['coordinates']}})
            rebuilt = outer_source_cycle(rebuilt, network)
            fc['features'] = rebuilt
            restored = [{'method': 'all_curated_segments_on_shared_osm_nodes', 'originalManualLinks': 4,
                'lengthM': round(sum(f['properties']['lengthM'] for f in rebuilt), 2), 'removedBacktracking': True,
                'roadAccessUnverified': any(f['properties']['roadAccessUnverified'] for f in rebuilt)}]
        for feature in fc['features']:
            if feature['properties'].get('highway') != 'gap_bridge':
                continue
            points = feature['geometry']['coordinates']
            result = network_path(points[0], points[-1], network_graph, max_cost=min(500, max(50, distance(*points) * 15)))
            # 이 접속만 검토한 실제 도로 우회로 허용한다. 전역 탐색 한도는 늘리지 않는다.
            if not result and course['code'] == 'HD-DALMAJI' and distance(points[0], [129.196045, 35.179544]) < 0.1 and distance(points[-1], [129.195975, 35.179617]) < 0.1:
                result = network_path(points[0], points[-1], network_graph, max_cost=650)
                if not result or result['wayIds'] != [1304018143, 1304018144, 37399936] or result['lengthM'] > 315:
                    raise ValueError('달맞이 검토 우회 경로 변경: 원본 길 재검토 필요')
                result['reviewedLongDetour'] = True
            if result:
                feature['geometry']['coordinates'] = result['coordinates']
                feature['properties'].update({'highway': 'network_connector', 'lengthM': round(result['lengthM'], 2),
                    'sourceWayIds': result['wayIds'], 'roadAccessUnverified': result['roadAccessUnverified']})
                restored.append(result)
                restored_ends.extend(points)
        # 복원한 직선의 옛 설명만 제거하고 미해결 연결의 표시는 남긴다.
        fc['features'] = [f for f in fc['features'] if not (f['properties'].get('layer') == 'issue' and f['properties'].get('type') == 'gap' and any(distance(f['geometry']['coordinates'], p) < 1 for p in restored_ends))]
        remaining = sum(f['properties'].get('highway') == 'gap_bridge' for f in fc['features'])
        if course['kind'] == 'road_proxy':
            for feature in fc['features']:
                if feature['properties'].get('layer') == 'course':
                    feature['properties']['roadAccessUnverified'] = True
        fc['metadata'] = {'trafficCoverage': 'baseline_before_repair' if restored else 'baseline', 'source': 'local D1 baseline + OSM network 2026-11-27',
                          'restoredConnectors': restored, 'unresolvedStraightLinks': remaining,
                          'fieldVerified': False, 'preservedCuratedGeometry': False, 'curatedManualLinks': 0,
                          'replacedCuratedManualLinks': 4 if course['code'] == 'HD-DONGBAEK' else 0}
        if course['kind'] == 'road_proxy':
            fc['metadata']['geometryRole'] = 'road_centerline'
        if course['code'] == 'HD-DONGBAEK':
            fc['metadata'].update({'trafficCoverage': 'not_analyzed', 'routeKind': 'loop',
                'geometryRole': 'osm_complete_loop', 'removedBacktracking': True,
                'completenessBasis': '기존 해안·공원 경유선의 원본 OSM 최대 면적 순환; 단순화·새 직선 없이 왕복 지선 제거'})
            coast = copy.deepcopy(fc)
            coast['metadata']['geometryRole'] = 'osm_coastal_loop'
            coast['metadata']['variantOf'] = 'HD-DONGBAEK'
            coast_code = 'HD-DONGBAEK-COAST'
            for f in coast['features']:
                f['properties'].update({'code': coast_code, 'name': '동백섬 해안·공원 연결 순환'})
            coast_course = {**course, 'code': coast_code, 'name': '동백섬 해안·공원 연결 순환(OSM)',
                'way_count': len({w for f in coast['features'] for w in f['properties']['sourceWayIds']}),
                'length_m': round(sum(distance(a, b) for f in coast['features'] for a, b in zip(f['geometry']['coordinates'], f['geometry']['coordinates'][1:]))),
                'geojson': '/data/haeundae/courses/' + coast_code + '.geojson', 'unique_crossings': None,
                'matched_points': None, 'crossings_per_km': None, 'crossings': [],
                'note': 'OSM 원본 해안·공원길을 연결한 단일 순환입니다. 이전 왕복 지선을 제거했습니다. 짧은 공원 순환도로와 다른 경로이며, 현장 통행은 별도 확인이 필요합니다.'}
            (out / (coast_code + '.geojson')).write_text(json.dumps(coast, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
            additions.append(coast_course)
            fc['features'] = dongbaek_park_loop(network)
            restored = [{'method': 'official_guide_matched_osm_park_road_cycle', 'wayIds': [239279807],
                'lengthM': round(sum(f['properties']['lengthM'] for f in fc['features']), 2), 'roadAccessUnverified': True}]
            fc['metadata'].update({'geometryRole': 'osm_park_road_loop',
                'restoredConnectors': restored,
                'completenessBasis': '부산시보 동백공원 순환산책로 안내도와 원본 OSM 공원 순환도로 대조',
                'guideUrl': 'https://www.busan.go.kr/news/hotissue/view?dataNo=70951'})
        if course['code'] == 'HD-SUYEONG':
            source = json.loads((ROOT / 'public/data/galmaetgil/8-2.geojson').read_text(encoding='utf-8'))
            fc = {'type': 'FeatureCollection', 'metadata': {**source['metadata'],
                'trafficCoverage': 'not_analyzed', 'routeKind': 'linear', 'geometryRole': 'official_reference_line',
                'referenceSection': 'GM-8-2', 'fieldVerified': False,
                'previousNamedNetworkLengthM': 12530, 'previousNamedNetworkComponents': 3}, 'features': []}
            for feature in source['features']:
                if feature['properties'].get('layer') == 'course':
                    feature['properties'].update({'code': course['code'], 'name': '수영강 동편 산책로(동천교→민락교)'})
                    fc['features'].append(feature)
            restored, remaining = [], 0
        if course['code'] == 'HD-MOONTAN':
            route = json.loads((ROOT / 'data/curated/moontan-route.json').read_text(encoding='utf-8'))
            detail = route['metadata']
            if not detail['closure']['closed'] or detail['closure']['gapM'] != 0:
                raise ValueError('문탠로드 원점회귀 선형이 폐합되지 않았습니다')
            fc = {'type': 'FeatureCollection', 'features': copy.deepcopy(route['features']), 'metadata': {
                'source': '공식 경유점 안내도 대조 + OSM 2026-11-27 원본 공유 노드',
                'generator': detail['meta']['generator'], 'closure': detail['closure'],
                'nodePairCount': len(detail['nodeIds']) - 1, 'uncertainty': detail['uncertainty'],
                'sourceUrls': [s['url'] for s in detail['officialComparison']['official']['sources']],
                'trafficCoverage': 'not_analyzed', 'routeKind': 'loop_with_pavilion_spur',
                'geometryRole': 'osm_waypoint_loop', 'terrainTotals': detail['totals'],
                'fieldVerified': False, 'sourceDate': '2026-11-27', 'officialRouteIdentityConfirmed': False}}
            restored, remaining = [], 0
        name = course['code'] + '.geojson'
        course['length_m'] = round(sum(distance(a, b) for f in fc['features'] if f['properties'].get('layer') == 'course' for a, b in zip(f['geometry']['coordinates'], f['geometry']['coordinates'][1:])))
        course['way_count'] = len({w for f in fc['features'] if f['properties'].get('layer') == 'course' for w in f['properties'].get('sourceWayIds', [f['properties'].get('wayId')]) if w is not None}) or len([f for f in fc['features'] if f['properties'].get('layer') == 'course'])
        course['crossings_per_km'] = round(course['unique_crossings'] * 1000 / course['length_m'], 2) if course['length_m'] else 0
        course['geojson'] = '/data/haeundae/courses/' + name
        course['note'] = ('원본 OSM 길로 연결 ' + str(len(restored)) + '곳 복원. '
                          + ('동백섬 수동 직선 4곳을 원본 길로 교체했습니다. 원래 경유점을 유지하여 일부 왕복이 포함됩니다. ' if course['code'] == 'HD-DONGBAEK' else '미확인 직선 연결 ' + str(remaining) + '곳. ')
                          + ('짧은 틈 대신 실제 도로 약 313m 우회를 포함합니다. ' if any(x.get('reviewedLongDetour') for x in restored) else '')
                          + ('횡단보도 수는 경로 보완 전 집계입니다. 추가 연결로의 신호는 재산출 전입니다. ' if restored else '기존 경로의 신호 집계를 유지합니다. ')
                          + '도로 보행 가능 여부는 현장 미확인입니다.')
        if course['kind'] == 'road_proxy':
            course['note'] = '보행 코스가 아닌 차도 중심선 참고 자료입니다. ' + course['note']
        if course['code'] == 'HD-SONGJEONG':
            course['name'] = '송정옛길(OSM 보행로)'
            course['note'] = '내륙 송정옛길입니다. 송정해변 전체나 동암해안길을 나타내지 않습니다. ' + course['note']
        if course['code'] == 'HD-SUYEONG':
            course['name'] = '수영강 동편 산책로(동천교→민락교)'
            course['note'] = '부산시 갈맷길 8-2 공식 GPX(2024-10-01)의 동천교→민락교 전체 선형입니다. 과거 3개 길 묶음 대신 연속 구간을 표시합니다. 현장 변동·신호는 별도 확인이 필요합니다.'
            course['sourceUrl'] = source['metadata']['officialPage']
        if course['code'] == 'HD-DONGBAEK':
            course['name'] = '동백공원 순환도로(안내도 대조)'
            course['note'] = '부산시보의 동백공원 순환산책로 안내도와 대조한 OSM 공원 순환도로 전체입니다. 약 1.65km 해안·공원 연결 순환과 구분합니다. 현장 통행과 신호는 별도 확인이 필요합니다.'
        if course['code'] == 'HD-MOONTAN':
            course['name'] = '문탠로드 원점회귀(어울마당·해월정 경유)'
            course['note'] = '들머리→바다전망대 대응지점→어울마당→해월정→들머리로 돌아오는 OSM 전체 연결 경로입니다. 공식 2.2km와 동일 노선으로 확정하지 않았으며 입구·전망대 위치는 추정입니다. 해월정 곁길 왕복을 포함하며 현장 통행·신호는 별도 확인이 필요합니다.'
        if course['code'] in ('HD-DONGBAEK', 'HD-SUYEONG', 'HD-MOONTAN'):
            course.update({'unique_crossings': None, 'matched_points': None, 'crossings_per_km': None, 'crossings': []})
        (out / name).write_text(json.dumps(fc, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
        report.append({'code': course['code'], 'restored': len(restored), 'remainingStraightLinks': remaining,
                       'roadAccessUnverified': any(f['properties'].get('roadAccessUnverified') for f in fc['features'] if f['properties'].get('layer') == 'course'), 'curatedManualLinks': 0,
                       'replacedCuratedManualLinks': 4 if course['code'] == 'HD-DONGBAEK' else 0})
    catalog['courses'].extend(additions)
    for extra in additions:
        report.append({'code': extra['code'], 'restored': 0, 'remainingStraightLinks': 0,
            'roadAccessUnverified': True, 'curatedManualLinks': 0, 'removedBacktracking': True})
    (out / 'index.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding='utf-8')
    (out / 'connectivity-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8788')
