#!/usr/bin/env python3
"""코스별 고도 프로파일 생성 (팀 API 코스 선 + Open-Meteo 고도). 앱 assets/profiles.json 으로 들어간다.
사용: python3 tools/build_profiles.py app/src/main/assets/profiles.json"""
import json, math, sys, time, urllib.request
OUT = sys.argv[1]; API = "https://dongnam-hackathon.magabus.workers.dev"; UA = {"User-Agent": "ddwigobo-hackathon/1.3"}
def get(u):
    for a in range(6):
        try: return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=40))
        except Exception as e: print("retry", u[:70], str(e)[:40]); time.sleep(3 + 4*a)
    raise SystemExit("fail " + u)
def hav(a, b):
    R=6371000; p1,p2=math.radians(a[0]),math.radians(b[0]); dl=math.radians(b[1]-a[1]); dp=p2-p1
    h=math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2; return 2*R*math.asin(math.sqrt(h))
courses = get(API + "/api/courses")["courses"]
res = {}
for c in courses:
    g = get(f"{API}/api/courses/{c['code']}/geojson")
    allines = []
    for f in g["features"]:
        if f.get("properties", {}).get("layer", "course") != "course": continue
        geom = f["geometry"]
        lines = geom["coordinates"] if geom["type"] == "MultiLineString" else ([geom["coordinates"]] if geom["type"] == "LineString" else [])
        for ln in lines: allines.append([(p[1], p[0]) for p in ln])
    def plen(l): return sum(hav(a, b) for a, b in zip(l, l[1:]))
    def near_used(p, used): return any(hav(p, q) < 18 for q in used[::3])
    todo = sorted(allines, key=plen, reverse=True)
    pts = list(todo.pop(0))
    while todo:
        end = pts[-1]
        cand = min(todo, key=lambda l: min(hav(end, l[0]), hav(end, l[-1])))
        todo.remove(cand)
        if min(hav(end, cand[0]), hav(end, cand[-1])) > 80: continue          # 멀리 떨어진 선은 건너뜀
        seg = cand if hav(end, cand[0]) <= hav(end, cand[-1]) else cand[::-1]
        if near_used(seg[len(seg)//2], pts): continue                       # 겹치는 선(양방향 등) 제외
        pts += seg
    if len(pts) < 2: continue
    # 40m 간격 재표본
    samp = [pts[0]]; dist = [0.0]; acc = 0.0; total = 0.0
    for a, b in zip(pts, pts[1:]):
        d = hav(a, b)
        if d == 0: continue
        n = 0
        while acc + d >= 40:
            t = (40 - acc) / d; a = (a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t); total += 40; samp.append(a); dist.append(total); d -= 40 - acc; acc = 0; n += 1
        acc += d
    el = []
    for k in range(0, len(samp), 100):
        ch = samp[k:k+100]
        u = "https://api.opentopodata.org/v1/srtm30m?locations=" + "|".join("%.5f,%.5f" % p for p in ch)   # SRTM 30m (1초 1회 제한)
        el += [r["elevation"] if r["elevation"] is not None else 0 for r in get(u)["results"]]; time.sleep(1.3)
    res[c["code"]] = {"len": round(dist[-1]), "pts": [[round(d), round(e, 1), round(p[0], 5), round(p[1], 5)] for d, e, p in zip(dist, el, samp)]}
    print(c["code"], res[c["code"]]["len"], len(el))
json.dump(res, open(OUT, "w"), separators=(",", ":"))
