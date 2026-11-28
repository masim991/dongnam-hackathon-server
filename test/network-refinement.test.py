import json
from pathlib import Path
import runpy
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
repair = runpy.run_path(str(ROOT / 'scripts/repair-course-connectivity.py'))
official = runpy.run_path(str(ROOT / 'scripts/build-galmaetgil.py'))
audit = runpy.run_path(str(ROOT / 'scripts/audit-course-coverage.py'))


class RefinementTests(unittest.TestCase):
    def test_outer_cycle_drops_an_entrance_spur_but_never_invents_an_edge(self):
        nodes = {'a': [129.17, 35.17], 'b': [129.1701, 35.17], 'c': [129.1721, 35.17],
                 'd': [129.1721, 35.172], 'e': [129.1701, 35.172]}
        net = {'nodes': nodes, 'ways': [{'id': 1, 'walk': 'yes', 'nodes': ['a', 'b', 'c', 'd', 'e', 'b', 'a']}]}
        feature = {'type': 'Feature', 'properties': {'layer': 'course', 'sourceWayIds': [1]},
                   'geometry': {'type': 'LineString', 'coordinates': [nodes[n] for n in net['ways'][0]['nodes']]}}
        result = repair['outer_source_cycle']([feature], net)
        self.assertEqual(audit['geometry_metrics'](result)['endpoints'], 0)
        self.assertFalse(any(nodes['a'] in f['geometry']['coordinates'] for f in result))
        feature['geometry']['coordinates'] = [nodes[n] for n in ['b', 'd', 'e', 'b']]
        with self.assertRaisesRegex(ValueError, '원본 OSM 선분'):
            repair['outer_source_cycle']([feature], net)

    def test_course_audit_does_not_connect_separate_or_crossing_lines(self):
        def line(coords, layer='course'):
            return {'properties': {'layer': layer}, 'geometry': {'type': 'LineString', 'coordinates': coords}}
        features = [line([[129.17, 35.17], [129.172, 35.17]]),
                    line([[129.171, 35.169], [129.171, 35.171]]),
                    line([[129.18, 35.18], [129.181, 35.18]], 'archive')]
        metrics = audit['geometry_metrics'](features)
        self.assertEqual(metrics['components'], 2)
        self.assertEqual(metrics['endpoints'], 4)
        self.assertEqual(metrics['lineFeatures'], 2)
        self.assertIn('not_access_validation', metrics['method'])

    def test_road_references_never_claim_pedestrian_geometry(self):
        catalog = json.loads((ROOT / 'public/data/haeundae/courses/index.json').read_text(encoding='utf-8'))
        for course in catalog['courses']:
            if course['kind'] != 'road_proxy':
                continue
            fc = json.loads((ROOT / ('public' + course['geojson'])).read_text(encoding='utf-8'))
            self.assertEqual(fc['metadata']['geometryRole'], 'road_centerline')
            self.assertTrue(all(f['properties']['roadAccessUnverified'] for f in fc['features'] if f['properties'].get('layer') == 'course'))
            self.assertEqual(course['crossings_per_km'], round(course['unique_crossings'] * 1000 / course['length_m'], 2))

    def test_shared_nodes_connect_but_geometric_crossings_do_not(self):
        nodes = {'1': [129.17, 35.17], '2': [129.1701, 35.17], '3': [129.1702, 35.17],
                 '4': [129.1701, 35.1699], '5': [129.1701, 35.1701]}
        net = {'nodes': nodes, 'ways': [{'id': 1, 'walk': 'yes', 'nodes': [1, 2, 3]},
                                       {'id': 2, 'walk': 'yes', 'nodes': [4, 5]}]}
        graph = repair['graph_from_network'](net)
        result = repair['network_path'](nodes['1'], nodes['3'], graph)
        self.assertIsNotNone(result)
        self.assertIsNone(repair['network_path'](nodes['1'], nodes['5'], graph))
        self.assertIsNone(repair['network_path']([129.17, 35.1701], nodes['3'], graph))

    def test_mid_edge_projection_follows_the_source_edge(self):
        net = {'nodes': {'1': [129.17, 35.17], '2': [129.171, 35.17]},
               'ways': [{'id': 1, 'walk': 'yes', 'nodes': [1, 2]}]}
        result = repair['network_path']([129.1702, 35.17], [129.1708, 35.17], repair['graph_from_network'](net))
        self.assertAlmostEqual(result['lengthM'], 54.6, places=2)
        self.assertFalse(result['roadAccessUnverified'])

    def test_gpx_separate_segments_are_not_joined(self):
        xml = '<gpx><trk><trkseg><trkpt lon="129.1" lat="35.1"/><trkpt lon="129.11" lat="35.1"/></trkseg><trkseg><trkpt lon="129.2" lat="35.2"/><trkpt lon="129.21" lat="35.2"/></trkseg></trk></gpx>'
        self.assertEqual(len(official['track_segments'](xml)), 2)

    def test_official_assets_preserve_every_original_gpx_point(self):
        for section in ['2-1', '2-2', '8-1', '8-2']:
            source = official['track_segments']((ROOT / ('data/reference/galmaetgil/' + section + '.gpx')).read_bytes())[0]
            fc = json.loads((ROOT / ('public/data/galmaetgil/' + section + '.geojson')).read_text(encoding='utf-8'))
            archived = [f['geometry']['coordinates'] for f in fc['features'] if f['properties']['highway'] == 'archived_gpx']
            parts = archived or [f['geometry']['coordinates'] for f in fc['features'] if f['properties']['highway'] in ['official_gpx', 'closed_reference']]
            restored = parts[0][:]
            for part in parts[1:]:
                self.assertEqual(restored[-1], part[0])
                restored.extend(part[1:])
            self.assertEqual(source, restored)
            self.assertLess(fc['metadata']['maxStepM'], 32)
        fc = json.loads((ROOT / 'public/data/galmaetgil/2-1.geojson').read_text(encoding='utf-8'))
        self.assertGreater(fc['features'][0]['geometry']['coordinates'][0][1], 35.175)
        self.assertEqual(fc['metadata']['detour']['status'], 'candidate_not_field_verified')

    def test_yacht_detour_avoids_closure_and_discloses_inferred_junction(self):
        fc = json.loads((ROOT / 'public/data/galmaetgil/2-1.geojson').read_text(encoding='utf-8'))
        active = [f for f in fc['features'] if f['properties']['layer'] == 'course']
        before, after, detour = [f['geometry']['coordinates'] for f in active]
        self.assertEqual(before[-1], detour[0])
        self.assertEqual(detour[-1], after[0])
        self.assertEqual(fc['metadata']['detour']['joiningGapM'], [0, 0])
        source = official['track_segments']((ROOT / 'data/reference/galmaetgil/2-1.gpx').read_bytes())[0]
        for p, i in zip([before[-1], after[0]], [987, 1046]):
            self.assertAlmostEqual(official['length'](source[i], p) + official['length'](p, source[i+1]), official['length'](source[i], source[i+1]), places=3)
        net = json.loads((ROOT / 'public/data/haeundae/walking-network.json').read_text(encoding='utf-8'))
        junction = fc['metadata']['detour']['inferredConnector']
        self.assertFalse(junction['sharedOsmNode'])
        self.assertFalse(junction['fieldVerified'])
        self.assertAlmostEqual(junction['lengthM'], 4.25, places=2)
        self.assertTrue(active[-1]['properties']['hasInferredConnector'])
        self.assertEqual(official['near_reference_fraction'](source[988:1048], source[988:1048]), 1)
        self.assertLess(official['near_reference_fraction'](detour, source[988:1048]), 0.2)
        self.assertTrue({820366480, 1337948936}.issubset(fc['metadata']['detour']['sourceWayIds']))
        self.assertFalse({820339540, 820339541, 820339542, 820339543}.intersection(fc['metadata']['detour']['sourceWayIds']))
        route = repair['network_path'](net['nodes']['7660263787'], detour[-1], repair['graph_from_network'](net), 1000)
        detour = detour[3:]
        self.assertFalse(route['roadAccessUnverified'])
        self.assertEqual(len(route['coordinates']), len(detour))
        self.assertTrue(all(repair['distance'](a, b) < 0.001 for a, b in zip(route['coordinates'], detour)))

    def test_remaining_manual_links_are_replaced_and_flagged(self):
        report = json.loads((ROOT / 'public/data/haeundae/courses/connectivity-report.json').read_text(encoding='utf-8'))
        self.assertTrue(all(r['remainingStraightLinks'] == r['curatedManualLinks'] == 0 for r in report))
        dong = json.loads((ROOT / 'public/data/haeundae/courses/HD-DONGBAEK.geojson').read_text(encoding='utf-8'))
        segments = [f for f in dong['features'] if f['properties']['layer'] == 'course']
        self.assertEqual(audit['geometry_metrics'](segments)['endpoints'], 0)
        for a, b in zip(segments, segments[1:] + segments[:1]):
            self.assertLess(repair['distance'](a['geometry']['coordinates'][-1], b['geometry']['coordinates'][0]), 0.001)
            self.assertTrue(a['properties']['sourceWayIds'])
        self.assertTrue(any(f['properties']['roadAccessUnverified'] for f in segments))
        dal = json.loads((ROOT / 'public/data/haeundae/courses/HD-DALMAJI.geojson').read_text(encoding='utf-8'))
        detour = next(r for r in dal['metadata']['restoredConnectors'] if r.get('reviewedLongDetour'))
        self.assertGreater(detour['lengthM'], 310)
        self.assertTrue(detour['roadAccessUnverified'])

    def test_both_dongbaek_routes_are_simple_source_cycles(self):
        for code, low, high in [('HD-DONGBAEK', 950, 1000), ('HD-DONGBAEK-COAST', 1600, 1700)]:
            fc = json.loads((ROOT / ('public/data/haeundae/courses/' + code + '.geojson')).read_text(encoding='utf-8'))
            metrics = audit['geometry_metrics'](fc['features'])
            self.assertEqual((metrics['components'], metrics['endpoints'], metrics['branchNodes'], metrics['repeatedEdgeOccurrences']), (1, 0, 0, 0))
            self.assertTrue(low < metrics['lengthM'] < high)
            segments = [f for f in fc['features'] if f['properties'].get('layer') == 'course']
            for a, b in zip(segments, segments[1:] + segments[:1]):
                self.assertLess(repair['distance'](a['geometry']['coordinates'][-1], b['geometry']['coordinates'][0]), .001)
                self.assertTrue(a['properties']['sourceWayIds'])
            self.assertEqual(fc['metadata']['trafficCoverage'], 'not_analyzed')

    def test_suyeong_keeps_every_point_of_the_complete_official_reference(self):
        fc = json.loads((ROOT / 'public/data/haeundae/courses/HD-SUYEONG.geojson').read_text(encoding='utf-8'))
        reference = official['track_segments']((ROOT / 'data/reference/galmaetgil/8-2.gpx').read_bytes())[0]
        self.assertEqual(fc['features'][0]['geometry']['coordinates'], reference)
        metrics = audit['geometry_metrics'](fc['features'])
        self.assertEqual((metrics['components'], metrics['endpoints'], metrics['branchNodes']), (1, 2, 0))
        self.assertEqual(fc['metadata']['sourceDate'], '2024-10-01')
        self.assertEqual(fc['metadata']['trafficCoverage'], 'not_analyzed')

    def test_moontan_closed_traversal_uses_only_real_shared_node_edges(self):
        route = json.loads((ROOT / 'data/curated/moontan-route.json').read_text(encoding='utf-8'))
        meta = route['metadata']
        net = json.loads((ROOT / 'public/data/haeundae/walking-network.json').read_text(encoding='utf-8'))
        edges = {tuple(sorted((str(a), str(b)))) for w in net['ways'] for a, b in zip(w['nodes'], w['nodes'][1:])}
        ids = meta['nodeIds']
        self.assertEqual(ids[0], ids[-1])
        self.assertTrue(all(tuple(sorted((str(a), str(b)))) in edges for a, b in zip(ids, ids[1:])))
        self.assertEqual(meta['closure']['gapM'], 0)
        self.assertEqual(len([f for f in route['features'] if f['properties']['layer'] == 'waypoint']), 4)
        self.assertGreater(meta['totals']['byClassM']['steps'], 0)
        self.assertGreater(meta['totals']['byClassM']['road_unverified'], 0)
        self.assertIn('공식', str(meta['uncertainty']))

    def test_network_references_are_complete_and_restricted_ways_stay_separate(self):
        net = json.loads((ROOT / 'public/data/haeundae/walking-network.json').read_text(encoding='utf-8'))
        self.assertEqual(len(net['ways']), len({w['id'] for w in net['ways']}))
        allowed = {w['id'] for w in net['ways']}
        self.assertFalse(allowed.intersection(w['id'] for w in net['restricted']))
        self.assertTrue(all(str(n) in net['nodes'] for w in net['ways'] for n in w['nodes']))
        self.assertTrue(any(w.get('footway') == 'crossing' for w in net['ways']))
        self.assertTrue(any(w.get('footway') == 'sidewalk' for w in net['ways']))
        self.assertTrue(any(w['hw'] == 'steps' for w in net['ways']))


if __name__ == '__main__':
    unittest.main()
