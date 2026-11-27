"""Run with the optional osmium environment; no real source or output files are modified."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/pbf-trail-cells.py"
XML = '''<osm version="0.6">
<node id="1" lat="35.12" lon="129.12"/><node id="2" lat="35.123" lon="129.125"/>
<way id="1001"><nd ref="1"/><nd ref="2"/><tag k="highway" v="path"/></way>
<way id="1002"><nd ref="1"/><nd ref="2"/><tag k="highway" v="footway"/><tag k="footway" v="crossing"/></way>
<way id="1003"><nd ref="1"/><nd ref="2"/><tag k="highway" v="path"/><tag k="access" v="private"/></way>
</osm>'''


class PbfCellsTest(unittest.TestCase):
    def test_conversion_filters_and_preserves_source(self):
        with tempfile.TemporaryDirectory(prefix="dongnam-pbf-") as tmp:
            root = Path(tmp)
            src, out = root / "sample.osm", root / "cells"
            src.write_text(XML, encoding="utf-8")
            before = hashlib.sha256(src.read_bytes()).hexdigest()
            result = subprocess.run([sys.executable, str(SCRIPT), str(src), str(out)], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads(result.stdout)
            self.assertEqual(summary["waysSeen"], 3)
            self.assertEqual(summary["waysKept"], 1)
            self.assertEqual(summary["cellsWritten"], 266)
            cell = json.loads((out / "35.1_129.1.json").read_text(encoding="utf-8"))
            self.assertEqual([w["id"] for w in cell["ways"]], [1001])
            self.assertEqual(hashlib.sha256(src.read_bytes()).hexdigest(), before)

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory(prefix="dongnam-pbf-") as tmp:
            root = Path(tmp)
            out = root / "cells"
            out.mkdir()
            previous = out / "35.1_129.1.json"
            previous.write_bytes(b'{"previous":true}')
            result = subprocess.run([sys.executable, str(SCRIPT), str(root / "missing.pbf"), str(out)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(previous.read_bytes(), b'{"previous":true}')
            self.assertEqual(len(list(out.iterdir())), 1)


if __name__ == "__main__":
    unittest.main()
