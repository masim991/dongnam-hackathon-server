"""부산시 2024-10-01 GPX 원본의 2·8코스를 좌표 손실 없이 지도 파일로 만든다.
실행: python scripts/build-galmaetgil.py (네트워크·D1 쓰기 없음)
"""
import hashlib
import json
import math
import runpy
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'data/reference/galmaetgil'
OUTPUT = ROOT / 'public/data/galmaetgil'
SOURCE_URL = 'https://www.busan.go.kr/galmaetgil/notice03/1653637'
NOTICE_URL = 'https://www.busan.go.kr/galmaetgil/notice01/1745240'
SECTIONS = {
    '2-1': ('송정해수욕장 → 민락교', 12000),
    '2-2': ('민락교 → 오륙도 진입데크', 11400),
    '8-1': ('상현마을 → 동천교', 10700),
    '8-2': ('동천교 → 민락교', 7600),
}


def length(a, b):
    return math.hypot((b[0] - a[0]) * 111320 * math.cos(math.radians((a[1] + b[1]) / 2)), (b[1] - a[1]) * 111320)


def near_reference_fraction(points, reference, radius=30):
    """5m 간격 표본으로 통제된 옛 트랙을 재사용하는 잘못된 우회를 거부한다."""
    def segment_distance(p, a, b):
        px, py = (p[0] - a[0]) * 91000, (p[1] - a[1]) * 111320
        dx, dy = (b[0] - a[0]) * 91000, (b[1] - a[1]) * 111320
        t = max(0, min(1, (px * dx + py * dy) / (dx * dx + dy * dy))) if dx or dy else 0
        return math.hypot(px - t * dx, py - t * dy)
    near, total = 0, 0
    for a, b in zip(points, points[1:]):
        count = max(1, math.ceil(length(a, b) / 5))
        for i in range(count):
            t = (i + 0.5) / count
            p = [a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])]
            near += min(segment_distance(p, c, d) for c, d in zip(reference, reference[1:])) < radius
            total += 1
    return near / total if total else 1


def track_segments(data):
    root = ET.fromstring(data)
    segments = []
    for element in root.iter():
        if element.tag.split('}')[-1] not in ('trkseg', 'rte'):
            continue
        points = []
        for point in element:
            if point.tag.split('}')[-1] not in ('trkpt', 'rtept'):
                continue
            lon, lat = float(point.attrib['lon']), float(point.attrib['lat'])
            if not (128 < lon < 130 and 34 < lat < 36):
                raise ValueError('부산 범위를 벗어난 GPX 좌표')
            points.append([lon, lat])
        if len(points) >= 2:
            segments.append(points)
    if not segments:
        raise ValueError('GPX에 유효한 경로가 없습니다')
    return segments


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((SOURCE / 'source.json').read_text(encoding='utf-8'))
    courses = []
    for section, (label, official_length) in SECTIONS.items():
        data = (SOURCE / (section + '.gpx')).read_bytes()
        if hashlib.sha256(data).hexdigest() != manifest['files'][section + '.gpx']:
            raise ValueError(section + ': 원본 파일 변경, 날짜·우회 구간 기준점을 다시 검토하세요')
        segments = track_segments(data)
        steps = [length(a, b) for line in segments for a, b in zip(line, line[1:])]
        if max(steps) > 80:
            raise ValueError(section + ': 80m 초과 점프, 원본 검토 필요')
        alert = ('수영만요트경기장 공사로 2026.10~2028.5(예정) 우회 운영 중입니다. 이 선은 2024년 기준 트랙이며 현재 우회선은 공식 공지를 확인하세요.' if section == '2-1' else '')
        metadata = {'section': section, 'sourceUrl': SOURCE_URL, 'sourceDate': '2024-10-01',
                    'sha256': hashlib.sha256(data).hexdigest(), 'officialPage': 'https://www.busan.go.kr/galmaetgil/tour' + section.replace('-', '0').zfill(4),
                    'pointCount': sum(map(len, segments)), 'segmentCount': len(segments), 'lengthM': round(sum(steps)),
                    'maxStepM': round(max(steps), 2), 'trafficCoverage': 'not_analyzed',
                    'alert': alert, 'noticeUrl': NOTICE_URL if alert else None}
        features = [{'type': 'Feature', 'properties': {'layer': 'course', 'code': 'GM-' + section,
                     'name': '갈맷길 ' + section, 'highway': 'official_gpx', 'referenceDate': '2024-10-01'},
                     'geometry': {'type': 'LineString', 'coordinates': line}} for line in segments]
        if section == '2-1':
            # 공식 안내도의 외곽 보도만 사용한다. 최단 경로는 통제된 내부 보도를 고를 수 있다.
            network = json.loads((ROOT / 'public/data/haeundae/walking-network.json').read_text(encoding='utf-8'))
            helpers = runpy.run_path(str(ROOT / 'scripts/repair-course-connectivity.py'))
            graph = helpers['graph_from_network'](network)
            joins = [[129.14261447732753, 35.15912150138699], [129.14014516620315, 35.16220928791954]]
            service_end = [129.14257858775096, 35.15924966021173]
            sidewalk_start = network['nodes']['7660263787']
            approach = helpers['network_path'](joins[0], service_end, graph, 100)
            candidate = helpers['network_path'](sidewalk_start, joins[1], graph, 1000)
            expected = [820366480, 1337948936, 820366479, 820339533, 820339537, 820339538]
            if not approach or approach['wayIds'] != [469945926, 1126184777] or not candidate or candidate['roadAccessUnverified'] or candidate['wayIds'] != expected:
                raise ValueError('외곽 보도 우회 경로 변경: 공식 안내도 및 접속부 재검토 필요')
            alignment = [approach['coordinates'][-1], candidate['coordinates'][0]]
            alignment_m = helpers['distance'](*alignment)
            if alignment_m > 5:
                raise ValueError('안내도 기반 추정 접속이 5m를 초과합니다: 현장 검토 필요')
            detour_coords = approach['coordinates'] + candidate['coordinates']
            line = segments[0]
            closed_proximity = near_reference_fraction(detour_coords, line[988:1048])
            if closed_proximity > 0.2:
                raise ValueError('우회선이 통제된 옛 트랙에 지나치게 가깝습니다: 안내도 재검토 필요')
            features = []
            # 원본은 별도 보존하고, 운영 선형은 보도 교차점에서만 잘라 붙인다.
            features.append({'type': 'Feature', 'properties': {'layer': 'archive', 'highway': 'archived_gpx',
                'code': 'GM-' + section, 'referenceDate': '2024-10-01'},
                'geometry': {'type': 'LineString', 'coordinates': line}})
            for coords, status, layer in [(line[:988] + [approach['coordinates'][0]], 'official_gpx', 'course'),
                    (line[988:1048], 'closed_reference', 'reference'),
                    ([candidate['coordinates'][-1]] + line[1047:], 'official_gpx', 'course')]:
                features.append({'type': 'Feature', 'properties': {'layer': layer, 'code': 'GM-' + section,
                    'highway': status, 'name': '갈맷길 2-1 (2024 원본 기반)'}, 'geometry': {'type': 'LineString', 'coordinates': coords}})
            features.append({'type': 'Feature', 'properties': {'layer': 'course', 'code': 'GM-' + section,
                'highway': 'detour_candidate', 'name': '요트경기장 외곽 우회 후보(4.3m 추정 접속 포함)',
                'wayIds': approach['wayIds'] + candidate['wayIds'], 'fieldVerified': False, 'hasInferredConnector': True},
                'geometry': {'type': 'LineString', 'coordinates': detour_coords}})
            metadata['detour'] = {'noticeUrl': NOTICE_URL, 'status': 'candidate_not_field_verified',
                'closedOriginalIndices': [988, 1047], 'gpxIntersectionSegments': [987, 1046],
                'joiningCoordinates': joins, 'lengthM': round(approach['lengthM'] + candidate['lengthM'] + alignment_m, 2),
                'joiningGapM': [0, 0], 'sourceWayIds': approach['wayIds'] + candidate['wayIds'],
                'within30mOfClosedFraction': round(closed_proximity, 3),
                'inferredConnector': {'coordinates': alignment, 'lengthM': round(alignment_m, 2), 'sharedOsmNode': False, 'fieldVerified': False,
                    'basis': '공식 우회 안내도의 연속 접속; OSM 보도와 서비스도로 간 누락 접점 추정', 'sourceUrl': NOTICE_URL},
                'method': '공식 안내도 외곽 보도 + 원본 서비스도로 + 안내도 기반 4.3m 추정 접속(공유 OSM 노드 없음)'}
            metadata['operationalLengthM'] = round(sum(length(a, b) for f in features if f['properties']['layer'] == 'course' for a, b in zip(f['geometry']['coordinates'], f['geometry']['coordinates'][1:])))
            alert = '수영만요트경기장 공사(2026.10~2028.5 예정): 옛 트랙은 회색 점선입니다. 공식 안내도와 대조한 외곽 우회 후보 약 445m를 주황 점선으로 표시합니다. 접속 4.3m는 안내도 기반 추정이며 OSM 공유 노드가 없습니다. 진입 서비스도로 약 15m와 현장 보행 가능 여부는 미확인입니다. 안내 거리는 2024 GPX 기준입니다.'
            metadata['alert'] = alert
        fc = {'type': 'FeatureCollection', 'metadata': metadata, 'features': features}
        (OUTPUT / (section + '.geojson')).write_text(json.dumps(fc, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
        courses.append({'code': 'GM-' + section, 'name': '갈맷길 ' + section + ' · ' + label, 'kind': 'official',
                        'length_m': round(sum(steps)), 'official_length_m': official_length, 'geojson': '/data/galmaetgil/' + section + '.geojson',
                        'note': '부산시 공식 GPX(2024-10-01), 원본 좌표·트랙 구분 보존. 현장 통행 가능 여부는 별도 확인.',
                        'sourceUrl': SOURCE_URL, 'alert': alert, 'noticeUrl': metadata['noticeUrl'], 'crossings': []})
    (OUTPUT / 'index.json').write_text(json.dumps({'sourceDate': '2024-10-01', 'sourceUrl': SOURCE_URL, 'courses': courses}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'courses': len(courses), 'lengthsM': {c['code']: c['length_m'] for c in courses}}, ensure_ascii=True))


if __name__ == '__main__':
    main()
