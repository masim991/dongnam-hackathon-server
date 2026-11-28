#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OpenFreeMap 'positron' 벡터 지도 스타일을 어두운 톤으로 바꿔 assets/style_dark.json 으로 저장.
데이터 출처: OpenFreeMap (© OpenMapTiles, Data from OpenStreetMap). 키 불필요, 상업 이용 가능(출처 표기)."""
import colorsys, json, re, sys, urllib.request
OUT = sys.argv[1]
req = urllib.request.Request("https://tiles.openfreemap.org/styles/positron", headers={"User-Agent": "ddwigobo-hackathon"})
style = json.load(urllib.request.urlopen(req, timeout=30))

def parse(s):
    s = s.strip().lower()
    m = re.match(r"#([0-9a-f]{3,8})$", s)
    if m:
        h = m.group(1)
        if len(h) in (3, 4): h = "".join(c*2 for c in h)
        return int(h[0:2], 16)/255, int(h[2:4], 16)/255, int(h[4:6], 16)/255, (int(h[6:8], 16)/255 if len(h) == 8 else 1.0)
    m = re.match(r"rgba?\(([^)]*)\)", s)
    if m:
        p = [x.strip() for x in m.group(1).split(",")]
        return float(p[0])/255, float(p[1])/255, float(p[2])/255, (float(p[3]) if len(p) > 3 else 1.0)
    m = re.match(r"hsla?\(([^)]*)\)", s)
    if m:
        p = [x.strip().rstrip("%") for x in m.group(1).split(",")]
        r, g, b = colorsys.hls_to_rgb(float(p[0])/360, float(p[2])/100, float(p[1])/100)
        return r, g, b, (float(p[3]) if len(p) > 3 else 1.0)
    return None

def dark(rgba, kind):
    r, g, b, a = rgba
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    if kind == "text":      nl = 0.62 + (1 - l) * 0.30; s *= 0.4
    elif kind == "halo":    nl = 0.06; s = 0.2
    elif kind == "line":    nl = 0.16 + (1 - l) * 0.38; s *= 0.35   # 도로선: 배경보다 밝은 회청색
    else:                   nl = 0.075 + (1 - l) * 0.22; s *= 0.45  # 면(배경/물/공원)
    h = 0.62   # 살짝 푸른 톤
    r, g, b = colorsys.hls_to_rgb(h, min(max(nl, 0), 1), min(max(s, 0), 1))
    return "rgba(%d,%d,%d,%s)" % (round(r*255), round(g*255), round(b*255), round(a, 3))

def walk(v, kind):
    if isinstance(v, str):
        c = parse(v); return dark(c, kind) if c else v
    if isinstance(v, list): return [walk(x, kind) for x in v]
    if isinstance(v, dict): return {k: walk(x, kind) for k, x in v.items()}
    return v

for layer in style["layers"]:
    p = layer.get("paint", {})
    for k in list(p.keys()):
        if k.endswith("color"):
            kind = "halo" if "halo" in k else "text" if k.startswith("text") else "line" if k.startswith("line") else "fill"
            if layer["type"] == "background": kind = "fill"
            p[k] = walk(p[k], kind)
for layer in style["layers"]:   # 한글 이름 우선
    if layer["type"] == "symbol" and "text-field" in layer.get("layout", {}):
        layer["layout"]["text-field"] = ["coalesce", ["get", "name:ko"], ["get", "name"], ["get", "name:latin"]]
style["layers"] = [l for l in style["layers"] if "shield" not in l["id"]]   # 고속도로 번호판 아이콘(흰 네모) 제거
style["name"] = "ddwigobo-dark"
json.dump(style, open(OUT, "w"), ensure_ascii=False, separators=(",", ":"))
print("layers", len(style["layers"]))
