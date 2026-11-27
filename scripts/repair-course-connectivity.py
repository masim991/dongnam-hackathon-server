"""시범 코스의 추정 직선 연결을 OSM 원본 네트워크 위의 실제 선형으로 교체한다.
실행: python scripts/repair-course-connectivity.py [로컬 API 주소]
공유 노드만 경유하며 도로 중간 접속은 원본 선분에 0.3m 이내인 경우만 허용한다.
"""
import heapq
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


def main(base):
    network = json.loads((ROOT / 'public/data/haeundae/walking-network.json').read_text(encoding='utf-8'))
    network_graph = graph_from_network(network)
    def get(path):
        with urlopen(base.rstrip('/') + path, timeout=15) as response:
            return json.load(response)
    catalog = get('/api/courses')
    out = ROOT / 'public/data/haeundae/courses'
    out.mkdir(parents=True, exist_ok=True)
    report = []
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
            fc['features'] = [f for f in fc['features'] if f['properties'].get('layer') != 'course'] + rebuilt
            restored = [{'method': 'all_curated_segments_on_shared_osm_nodes', 'originalManualLinks': 4,
                'lengthM': round(sum(f['properties']['lengthM'] for f in rebuilt), 2),
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
        fc['metadata'] = {'trafficCoverage': 'baseline_before_repair' if restored else 'baseline', 'source': 'local D1 baseline + OSM network 2026-11-27',
                          'restoredConnectors': restored, 'unresolvedStraightLinks': remaining,
                          'fieldVerified': False, 'preservedCuratedGeometry': False, 'curatedManualLinks': 0,
                          'replacedCuratedManualLinks': 4 if course['code'] == 'HD-DONGBAEK' else 0}
        name = course['code'] + '.geojson'
        (out / name).write_text(json.dumps(fc, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
        course['length_m'] = round(sum(distance(a, b) for f in fc['features'] if f['properties'].get('layer') == 'course' for a, b in zip(f['geometry']['coordinates'], f['geometry']['coordinates'][1:])))
        course['geojson'] = '/data/haeundae/courses/' + name
        course['note'] = ('원본 OSM 길로 연결 ' + str(len(restored)) + '곳 복원. '
                          + ('동백섬 수동 직선 4곳을 원본 길로 교체했습니다. 원래 경유점을 유지하여 일부 왕복이 포함됩니다. ' if course['code'] == 'HD-DONGBAEK' else '미확인 직선 연결 ' + str(remaining) + '곳. ')
                          + ('짧은 틈 대신 실제 도로 약 313m 우회를 포함합니다. ' if any(x.get('reviewedLongDetour') for x in restored) else '')
                          + ('횡단보도 수는 경로 보완 전 집계입니다. 추가 연결로의 신호는 재산출 전입니다. ' if restored else '기존 경로의 신호 집계를 유지합니다. ')
                          + '도로 보행 가능 여부는 현장 미확인입니다.')
        report.append({'code': course['code'], 'restored': len(restored), 'remainingStraightLinks': remaining,
                       'roadAccessUnverified': any(x['roadAccessUnverified'] for x in restored), 'curatedManualLinks': 0,
                       'replacedCuratedManualLinks': 4 if course['code'] == 'HD-DONGBAEK' else 0})
    (out / 'index.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding='utf-8')
    (out / 'connectivity-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8788')
