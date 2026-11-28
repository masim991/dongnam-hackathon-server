#!/usr/bin/env python3
"""해운대구 도로/길 + 신호등·횡단보도 + 경사 + 샘플 글/사진 데이터 생성 (OSM Overpass + Open-Meteo 고도).
사용: python3 tools/build_data.py out_dir
결과: roads_haeundae.json.gz, posts_haeundae.json, img/*.jpg  -> 서버 /files/data/ 에 올린다."""
import gzip, json, math, os, random, sys, time, urllib.request, urllib.parse

OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
os.makedirs(OUT + "/img", exist_ok=True)
UA = {"User-Agent": "eudero-gano-hackathon/1.0"}
MIRRORS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
           "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]
AREA = 'area["name"="해운대구"]["boundary"="administrative"]->.a;'
HW = "footway|path|pedestrian|cycleway|steps|track|living_street|residential|unclassified|tertiary|secondary|primary|trunk"

def overpass(q):
    for attempt in range(6):
        for m in MIRRORS:
            try:
                req = urllib.request.Request(m, data=urllib.parse.urlencode({"data": q}).encode(), headers=UA)
                return json.load(urllib.request.urlopen(req, timeout=120))
            except Exception as e:
                print("overpass fail", m, str(e)[:60]); time.sleep(5)
    raise SystemExit("overpass 실패")

print("ways...")
ways = overpass(f'[out:json][timeout:180];{AREA}way(area.a)["highway"~"^({HW})$"]["access"!~"private|no"];out tags geom;')["elements"]
print(len(ways), "ways")
print("nodes...")
nodes = overpass(f'[out:json][timeout:120];{AREA}(node(area.a)["highway"="traffic_signals"];node(area.a)["highway"="crossing"];node(area.a)["crossing"];);out;')["elements"]
print("relations...")
rels = overpass(f'[out:json][timeout:120];{AREA}rel(area.a)["route"~"hiking|foot|running|bicycle"];out body;')["elements"]
alias = {}
for r in rels:
    nm = r.get("tags", {}).get("name")
    if nm:
        for m in r.get("members", []):
            if m["type"] == "way": alias.setdefault(m["ref"], nm)

def hav(a, b):
    R = 6371000; p1, p2 = math.radians(a[0]), math.radians(b[0])
    dl = math.radians(b[1] - a[1]); dp = p2 - p1
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*R*math.asin(math.sqrt(h))

recs = []
for w in ways:
    g = [(round(p["lat"], 5), round(p["lon"], 5)) for p in w.get("geometry", [])]
    if len(g) < 2: continue
    t = w.get("tags", {})
    name = t.get("name:ko") or t.get("name")
    al = t.get("alt_name") or t.get("loc_name") or alias.get(w["id"])
    disp = al or name or ""        # 별칭(갈맷길 등)이 있으면 우선
    L = sum(hav(g[i], g[i+1]) for i in range(len(g)-1))
    recs.append({"id": w["id"], "n": disp, "n0": name or "", "hw": t["highway"], "len": L, "pts": g})

# 고도 격자 (약 250m)
lats = [p[0] for r in recs for p in r["pts"]]; lons = [p[1] for r in recs for p in r["pts"]]
la0, la1, lo0, lo1 = min(lats)-.002, max(lats)+.002, min(lons)-.002, max(lons)+.002
dla, dlo = 250/111000, 250/(111000*math.cos(math.radians(35.17)))
ny, nx = int((la1-la0)/dla)+2, int((lo1-lo0)/dlo)+2
grid = [[None]*nx for _ in range(ny)]
cells = [(i, j) for i in range(ny) for j in range(nx)]
print("elevation grid", ny, nx, len(cells))
for k in range(0, len(cells), 100):
    chunk = cells[k:k+100]
    u = "https://api.open-meteo.com/v1/elevation?latitude=%s&longitude=%s" % (
        ",".join("%.5f" % (la0+i*dla) for i, j in chunk), ",".join("%.5f" % (lo0+j*dlo) for i, j in chunk))
    for attempt in range(5):
        try:
            el = json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=30))["elevation"]; break
        except Exception as e:
            print("elev retry", str(e)[:50]); time.sleep(3 + attempt*3)
    else: el = [0]*len(chunk)
    for (i, j), z in zip(chunk, el): grid[i][j] = z
    time.sleep(0.3)

def elev(p):
    fy, fx = (p[0]-la0)/dla, (p[1]-lo0)/dlo
    i, j = min(int(fy), ny-2), min(int(fx), nx-2); ty, tx = fy-i, fx-j
    return (grid[i][j]*(1-ty)*(1-tx) + grid[i+1][j]*ty*(1-tx) + grid[i][j+1]*(1-ty)*tx + grid[i+1][j+1]*ty*tx)

def slope(pts):
    # ~120m 간격으로 샘플해 평균 경사(%)
    samples = [pts[0]]; acc = 0
    for i in range(len(pts)-1):
        acc += hav(pts[i], pts[i+1])
        if acc >= 120: samples.append(pts[i+1]); acc = 0
    if samples[-1] != pts[-1]: samples.append(pts[-1])
    gs = []
    for a, b in zip(samples, samples[1:]):
        d = hav(a, b)
        if d > 30: gs.append(abs(elev(b)-elev(a))/d*100)
    return round(min(sum(gs)/len(gs), 30), 1) if gs else None

out_ways = []
for r in recs:
    out_ways.append({"id": r["id"], "n": r["n"], "n0": r["n0"], "hw": r["hw"],
                     "s": slope(r["pts"]) if r["len"] >= 60 else None, "p": [list(p) for p in r["pts"]]})
out_nodes = []
for n in nodes:
    t = n.get("tags", {})
    kind = "s" if t.get("highway") == "traffic_signals" or t.get("crossing") == "traffic_signals" else "c"
    out_nodes.append([round(n["lat"], 5), round(n["lon"], 5), kind])
data = {"region": "busan", "area": "haeundae", "generated": int(time.time()*1000), "ways": out_ways, "nodes": out_nodes}
with gzip.open(OUT + "/roads_haeundae.json.gz", "wt", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
print("roads saved", len(out_ways), "ways", len(out_nodes), "nodes")

# ---- 샘플 글 (길을 따라 3개씩: 사람2 + 경고1) ----
named = sorted([r for r in recs if r["n"] and r["len"] > 250], key=lambda r: -r["len"])
seen, picks = set(), []
for r in named:
    if r["n"] in seen: continue
    seen.add(r["n"]); picks.append(r)
    if len(picks) >= 10: break

def at(pts, frac):
    tot = sum(hav(pts[i], pts[i+1]) for i in range(len(pts)-1)); target = tot*frac; acc = 0
    for i in range(len(pts)-1):
        d = hav(pts[i], pts[i+1])
        if acc + d >= target and d > 0:
            t = (target-acc)/d; return pts[i][0]+(pts[i+1][0]-pts[i][0])*t, pts[i][1]+(pts[i+1][1]-pts[i][1])*t
        acc += d
    return pts[-1]

PERSON = ["여기 야경 진짜 예뻐요! 저녁 러닝 추천", "오늘 컨디션 최고, 바닷바람 시원해요", "초보도 뛰기 좋은 평지예요",
          "아침에 사람 적고 공기 좋아요", "벤치랑 화장실 가까워서 편해요"]
WARN = ["⚠ 노면 균열 있어요. 발목 조심", "⚠ 파도 심한 날은 물이 길까지 올라와요", "⚠ 야간에 가로등이 꺼져 있어요",
        "⚠ 공사로 길이 좁아졌어요"]
random.seed(7)
posts = []
now = int(time.time()*1000)
for k, r in enumerate(picks):
    for j, (frac, typ) in enumerate([(.2, "person"), (.5, "person"), (.8, "warn")]):
        la, lo = at(r["pts"], frac)
        img = ("selfie%d" % (random.randint(1, 2))) if typ == "person" else random.choice(["collapse1", "flood1", "collapse2", "flood2"])
        posts.append({"id": "s%d_%d" % (k, j), "lat": round(la, 5), "lon": round(lo, 5), "type": typ, "road": r["n"],
                      "author": random.choice(["달리는곰", "부산갈매기", "해운대러너", "새벽조깅", "광안파도"]),
                      "text": random.choice(PERSON if typ == "person" else WARN), "img": "img/%s.jpg" % img,
                      "created": now - random.randint(1, 48)*3600*1000})
json.dump({"generated": now, "posts": posts}, open(OUT + "/posts_haeundae.json", "w"), ensure_ascii=False, indent=1)

# ---- 샘플 이미지 (직접 그린 일러스트, 라이선스 무관) ----
from PIL import Image, ImageDraw
def canvas(bg): im = Image.new("RGB", (640, 420), bg); return im, ImageDraw.Draw(im)
def selfie(n):
    im, d = canvas((150, 200, 240) if n == 1 else (250, 200, 150))
    d.rectangle([0, 300, 640, 420], fill=(90, 170, 110) if n == 1 else (220, 190, 130))
    d.ellipse([220, 80, 420, 280], fill=(255, 220, 180)); d.ellipse([260, 150, 290, 180], fill=(40, 40, 40)); d.ellipse([350, 150, 380, 180], fill=(40, 40, 40))
    d.arc([270, 180, 370, 250], 10, 170, fill=(160, 50, 50), width=8); d.pieslice([210, 50, 430, 190], 180, 360, fill=(60, 40, 30))
    d.rectangle([250, 280, 390, 420], fill=(255, 90, 31)); return im
def collapse(n):
    im, d = canvas((170, 190, 210)); d.polygon([(0, 420), (200, 200), (440, 200), (640, 420)], fill=(110, 110, 115))
    d.ellipse([230, 260, 420, 340], fill=(25, 20, 20))
    for a, b in [((300, 260), (260, 215)), ((330, 258), (350, 205)), ((240, 300), (170, 330)), ((410, 300), (500, 340))]: d.line([a, b], fill=(30, 30, 30), width=5)
    d.polygon([(520, 330), (550, 330), (535, 280)], fill=(255, 120, 0)); return im
def flood(n):
    im, d = canvas((140, 160, 190)); d.rectangle([0, 220, 640, 300], fill=(170, 150, 110))
    for i in range(0, 640, 40): d.arc([i, 250 + (n*7), i+80, 330], 180, 360, fill=(255, 255, 255), width=3)
    d.rectangle([0, 270, 640, 420], fill=(40, 110, 170)); d.rectangle([0, 270, 640, 290], fill=(70, 140, 190)); return im
for n in (1, 2): selfie(n).save(f"{OUT}/img/selfie{n}.jpg", quality=85); collapse(n).save(f"{OUT}/img/collapse{n}.jpg", quality=85); flood(n).save(f"{OUT}/img/flood{n}.jpg", quality=85)
print("done", len(posts), "posts")
