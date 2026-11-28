"""12개 표시 코스의 선형 범위와 근거를 감사한다. DB/네트워크 쓰기 없음.
실행: python scripts/audit-course-coverage.py
좌표 0.3m 이내는 반올림 접속으로만 집계하며 현장 통행 가능성을 증명하지 않는다.
"""
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
helpers = runpy.run_path(str(ROOT / 'scripts/repair-course-connectivity.py'))
length = runpy.run_path(str(ROOT / 'scripts/build-galmaetgil.py'))['length']


def geometry_metrics(features):
    vertices, grid, graph, edges = {}, defaultdict(list), defaultdict(set), Counter()
    total_length, points, line_count = 0, 0, 0
    def vertex(p):
        x, y = helpers['xy'](p)
        key = (math.floor(x / .3), math.floor(y / .3))
        for i in range(key[0] - 1, key[0] + 2):
            for j in range(key[1] - 1, key[1] + 2):
                for v in grid[i, j]:
                    if helpers['distance'](p, vertices[v]) <= .3:
                        return v
        v = len(vertices)
        vertices[v] = p
        grid[key].append(v)
        return v
    for f in features:
        if f['properties'].get('layer') != 'course' or f['geometry']['type'] != 'LineString':
            continue
        line_count += 1
        coords = f['geometry']['coordinates']
        points += len(coords)
        for a, b in zip(coords, coords[1:]):
            total_length += length(a, b)
            va, vb = vertex(a), vertex(b)
            if va != vb:
                graph[va].add(vb)
                graph[vb].add(va)
                edges[tuple(sorted([va, vb]))] += 1
    remaining, components = set(graph), 0
    while remaining:
        seen, todo = set(), [next(iter(remaining))]
        while todo:
            v = todo.pop()
            if v not in seen:
                seen.add(v)
                todo.extend(graph[v] - seen)
        remaining -= seen
        components += 1
    return {'lengthM': round(total_length), 'lineFeatures': line_count, 'points': points,
        'components': components, 'branchNodes': sum(len(n) > 2 for n in graph.values()),
        'endpoints': sum(len(n) == 1 for n in graph.values()),
        'repeatedEdgeOccurrences': sum(n - 1 for n in edges.values()),
        'method': 'coordinate_adjacency_0.3m_not_access_validation'}


COVERAGE = {
    'HD-BEACHPROM': ('partial_path', '해변 북측 일부 보행로', '해운대 해변 전체가 아닌 OSM에 선택된 북측 보행로입니다.'),
    'HD-BEACHPROM-EXT': ('partial_network', '미포 방면 일부 길 모음', '진입 지선과 연결로가 포함되며 공식 단일 코스의 전체 선형은 아닙니다.'),
    'HD-BEACHROAD': ('road_reference', '차도 중심선 참고', '해운대해변로의 차도 선형입니다. 러닝용 보도 경로로 사용하지 마세요.'),
    'HD-DALMAJI': ('road_reference', '차도 중심선 참고', '달맞이길 도로와 지선입니다. 약 313m 도로 우회를 포함하며 보행 코스는 미확정입니다.'),
    'HD-DONGBAEK': ('derived_loop', '공원 순환도로 전체(안내도 대조)', '안내판과 대조한 OSM 공원 순환도로 한 바퀴입니다. 해안·공원 연결 순환과 구분하며 현장 측량으로 확정한 좌표는 아닙니다.'),
    'HD-DONGBAEK-COAST': ('derived_loop', '해안·공원 연결 순환 전체(OSM)', '원본 해안·공원길을 따라 한 번 도는 경로입니다. 기존 왕복 지선을 제거했으며 짧은 공원 순환도로와 다른 경로입니다.'),
    'HD-MOONTAN': ('candidate_waypoint_loop', '문탠로드 전체 연결 순환(추정 포함)', '숲길·보도·계단을 따라 어울마당·해월정을 거쳐 출발점으로 돌아오는 경로입니다. 공식 2.2km와 동일 노선임을 확정하지 않았으며 입구·바다전망대 위치·명칭은 추정입니다.'),
    'HD-SONGJEONG': ('named_network', '내륙 송정옛길 보행로', '내륙 송정옛길의 OSM 보행로입니다. 송정해변이나 동암해안길 전체 코스를 나타내지 않습니다.'),
    'HD-SUYEONG': ('official_reference', '동천교→민락교 전체(공식 8-2 연계)', '부산시 2024 갈맷길 8-2 GPX 전체 좌표를 보존한 수영강 동편 연속 경로입니다. 현재 현장 변동과 신호는 별도 확인이 필요합니다.'),
}
OFFICIAL = {
    'GM-2-1': (12000, ['송정해수욕장', '청사포', '해운대해수욕장', '누리마루', '민락교'], '해운대·수영구. 2024 공식 GPX 전체와 2026 공사 우회 후보를 구분합니다.'),
    'GM-2-2': (11400, ['민락교', '광안리해수욕장', '동생말', '이기대어울마당', '오륙도'], '수영구·남구의 공식 GPX 전체입니다. 해운대 경계로 잘라내지 않았습니다.'),
    'GM-8-1': (10700, ['상현마을', '부엉산', '땅뫼산', '명장정수사업소 회동지소', '동대교', '동천교'], '금정구·해운대 일원의 공식 GPX 전체입니다.'),
    'GM-8-2': (7600, ['동천교', '원동교', '수영4호교', '좌수영교', 'APEC 나루공원', '민락교'], '수영강 동편을 따라가는 공식 GPX 전체입니다.'),
}


def main():
    source_path = ROOT / 'data/reference/haeundae-course-sources.json'
    raw = json.loads(source_path.read_text(encoding='utf-8-sig'))
    evidence = {c['code']: c for c in raw['courses']}
    if len(evidence) != len(raw['courses']):
        raise ValueError('HD 코스의 근거 기록이 중복됩니다')
    evidence['HD-DONGBAEK-COAST'] = evidence['HD-DONGBAEK']
    if set(COVERAGE) - evidence.keys():
        raise ValueError('HD 8개 코스의 근거 기록을 중복 없이 확인해야 합니다')
    courses = []
    for folder in ['galmaetgil', 'haeundae/courses']:
        catalog = json.loads((ROOT / ('public/data/' + folder + '/index.json')).read_text(encoding='utf-8'))
        for course in catalog['courses']:
            code = course['code']
            if code == 'HD-GALMAET':
                continue
            fc = json.loads((ROOT / ('public' + course['geojson'])).read_text(encoding='utf-8'))
            metrics = geometry_metrics(fc['features'])
            item = {'code': code, 'name': course['name'], 'metrics': metrics, 'fieldVerified': False,
                'geometryReferenceDate': fc.get('metadata', {}).get('sourceDate', '2026-11-27'), 'findings': []}
            if code in OFFICIAL:
                official_m, waypoints, summary = OFFICIAL[code]
                section = code[3:]
                item.update({'coverage': 'official_track', 'coverageLabel': '부산시 공식 GPX 전체(2024)', 'summary': summary,
                    'officialDistanceM': official_m, 'officialWaypoints': waypoints,
                    'sources': [{'url': 'https://www.busan.go.kr/galmaetgil/tour' + section.replace('-', '0').zfill(4),
                        'title': '부산시 갈맷길 ' + section + ' 공식 안내', 'authority': '부산광역시', 'readStatus': 'read',
                        'supports': '공식 구간·거리·경유점', 'doesNotProve': '현재 현장 통행 및 원본 GPX의 실측 정확도'},
                        {'url': 'https://www.busan.go.kr/galmaetgil/notice03/1653637', 'title': '부산시 2024 GPX 원본',
                        'authority': '부산광역시', 'readStatus': 'read', 'supports': '배포된 원본 좌표', 'doesNotProve': '2026 현장 변동'}]})
                if code == 'GM-2-1':
                    item['coverage'] = 'official_track_with_detour'
                    item['coverageLabel'] = '공식 GPX(2024) + 공사 우회 후보'
                    item['findings'].append('공사 우회 후보 약 445m 중 접속 4.25m는 안내도 기반 추정입니다. OSM 공유 노드와 현장 검증이 없습니다.')
                    item['sources'].append({'url': 'https://www.busan.go.kr/galmaetgil/notice01/1745240',
                        'title': '수영만요트경기장 공사 우회 공지', 'authority': '부산광역시', 'readStatus': 'read',
                        'supports': '공사 통제·외곽 우회 안내도', 'doesNotProve': '추정 접속부의 실측 좌표'})
            else:
                status, label, summary = COVERAGE[code]
                found = evidence.get(code, {})
                reference = found.get('officialDistanceM') or {}
                selected_urls = found.get('uiEvidenceLinks')
                sources = []
                for source in found.get('sources', []):
                    if selected_urls and source['url'] not in selected_urls:
                        continue
                    source = dict(source)
                    source['verificationMethod'] = source['readStatus']
                    if source['readStatus'] == 'read_by_fetch_agent_http200':
                        source['readStatus'] = 'read'
                        source['checkedBy'] = 'Claude'
                    sources.append(source)
                item.update({'coverage': status, 'coverageLabel': label, 'summary': summary,
                    'referenceDistance': reference, 'officialDistanceM': None,
                    'officialWaypoints': found.get('officialWaypoints', []), 'sources': sources})
                if code in ('HD-DONGBAEK', 'HD-DONGBAEK-COAST'):
                    item['sources'].append({'url': 'https://www.busan.go.kr/news/hotissue/view?dataNo=70951',
                        'title': '동백공원 순환산책로 안내판과 해안길(부산시보)',
                        'authority': '부산시보·해운대 관광시설관리사업소 안내판 사진', 'readStatus': 'read',
                        'checkedBy': 'Codex', 'checkedDate': '2026-11-28', 'verificationMethod': 'Aside 원문 및 안내판 이미지 직접 대조',
                        'supports': '공원 순환도로와 해안길 구분·주요 경유점', 'doesNotProve': '현재 실측 좌표와 통행 상태'})
                if code == 'HD-SUYEONG':
                    item['sources'].append({'url': 'https://www.busan.go.kr/galmaetgil/tour0802',
                        'title': '수영강 동편 전체 선형의 기준: 부산시 갈맷길 8-2', 'authority': '부산광역시',
                        'readStatus': 'read', 'checkedBy': 'Codex', 'checkedDate': '2026-11-28',
                        'supports': '동천교→민락교 공식 구간과 원본 GPX', 'doesNotProve': '2026 현장 변동'})
                if code == 'HD-MOONTAN':
                    totals = fc['metadata'].get('terrainTotals', {})
                    classes = totals.get('byClassM', {})
                    item['findings'].append('숲길 ' + str(classes.get('forest_or_walkway', 0)) + 'm · 보도 ' + str(classes.get('sidewalk', 0)) + 'm · 계단 ' + str(classes.get('steps', 0)) + 'm · 보행 미확인 도로 ' + str(classes.get('road_unverified', 0)) + 'm. 해월정 곁길 왕복 약 ' + str(totals.get('repeatedTraversalM', 0)) + 'm를 포함합니다.')
                    item['sources'].append({'url': 'https://www.busan.go.kr/news/storyreport/view?dataNo=72695',
                        'title': '문탠로드 순환 경유점과 현장 안내도(부산시보)', 'authority': '부산광역시 부산시보',
                        'readStatus': 'read', 'checkedBy': 'Claude', 'checkedDate': '2026-11-28',
                        'supports': '입구·바다전망대·어울마당·해월정 경유 및 원점회귀', 'doesNotProve': '전망대의 정확한 OSM 객체와 전체 실측 좌표'})
                if metrics['branchNodes']:
                    item['findings'].append('경유지에 들어갔다 나오는 곁길을 포함하며 표시 거리에는 그 왕복이 포함됩니다.' if fc.get('metadata', {}).get('routeKind') else '선형에 지선이 포함됩니다. 표시 거리는 선형의 합계이며 완주 거리로 보장하지 않습니다.')
                if metrics['repeatedEdgeOccurrences']:
                    item['findings'].append('일부 곁길을 왕복합니다. 코스 거리는 경유 순서에 따른 전체 이동 거리입니다.' if fc.get('metadata', {}).get('routeKind') else '중복·왕복 선분이 포함됩니다. 선형 합계와 중복을 뺀 도보 거리는 다를 수 있습니다.')
            for source in item['sources']:
                if code in OFFICIAL:
                    source.update({'checkedBy': 'Codex', 'checkedDate': '2026-11-28', 'verificationMethod': 'Aside 원문·공식 GPX/공지 이미지 대조'})
                if not source['url'].startswith('https://'):
                    raise ValueError(code + ': 근거 링크는 HTTPS 원문이어야 합니다')
            courses.append(item)
    if {c['code'] for c in courses} != set(OFFICIAL) | set(COVERAGE) or len(courses) != len(OFFICIAL) + len(COVERAGE):
        raise ValueError('표시 코스와 근거·범위 목록이 중복 없이 일치해야 합니다')
    report = {'checkedDate': '2026-11-28', 'networkDate': '2026-11-27',
        'limits': '좌표 연결 통계는 통행·공식 전체 범위 검증이 아니다. 공식 거리와 선형 합계를 혼동하지 않는다.', 'courses': courses}
    target = ROOT / 'public/data/haeundae/course-review.json'
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'courses': len(courses), 'officialTracks': sum(c['coverage'].startswith('official_track') for c in courses),
        'disconnected': [c['code'] for c in courses if c['metrics']['components'] > 1],
        'sourceCounts': {c['code']: len(c['sources']) for c in courses}}, ensure_ascii=True))


if __name__ == '__main__':
    main()
