#!/usr/bin/env python3
"""뛰고보자 버블(글·사진·위치) 저장 서버. 표준 라이브러리 + sqlite만 사용.
실행: BUBBLE_DIR=~/bubble-server python3 bubble_server.py   (127.0.0.1:5301, Caddy가 /bubble/* 를 넘겨줌)
DB를 바꾸고 싶으면 Store 클래스만 바꾸면 된다 (앱은 HTTP 규약만 안다)."""
import base64, json, os, re, sqlite3, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.path.expanduser(os.environ.get("BUBBLE_DIR", "~/bubble-server"))
IMG = os.path.join(BASE, "img"); os.makedirs(IMG, exist_ok=True)
RUNIMG = os.path.join(BASE, "runimg"); os.makedirs(RUNIMG, exist_ok=True)
KEY = open(os.path.join(BASE, "client.key")).read().strip()
FILES_DATA = "https://yumin-server.duckdns.org/files/data/"   # 샘플 이미지 위치
TYPES = {"person", "warn", "ask", "facility"}
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")

class Store:
    def __init__(self):
        self.db = sqlite3.connect(os.path.join(BASE, "bubbles.db"), check_same_thread=False)
        self.lock = threading.Lock()
        self.db.execute("""CREATE TABLE IF NOT EXISTS bubbles(
            id TEXT PRIMARY KEY, area TEXT, lat REAL, lon REAL, type TEXT, text TEXT, road TEXT, author TEXT,
            created INTEGER, img TEXT, device TEXT, received INTEGER)""")
        cols = [r[1] for r in self.db.execute("PRAGMA table_info(bubbles)")]
        if "visibility" not in cols: self.db.execute("ALTER TABLE bubbles ADD COLUMN visibility TEXT DEFAULT 'public'")
        self.db.execute("""CREATE TABLE IF NOT EXISTS runs(
            id TEXT PRIMARY KEY, start_ms INTEGER, duration_sec INTEGER, distance_m REAL, avg_hr INTEGER, ascent_m INTEGER,
            title TEXT, device TEXT, pts TEXT, png TEXT, received INTEGER)""")
        self.db.commit()
    def add_run(self, r):
        with self.lock:
            self.db.execute("INSERT OR REPLACE INTO runs VALUES(?,?,?,?,?,?,?,?,?,?,?)", (r["id"], r["start"], r["dur"], r["dist"], r["hr"], r["asc"], r["title"], r["device"], r["pts"], r["png"], int(time.time()*1000)))
            self.db.commit()
    def add(self, b):
        with self.lock:
            cur = self.db.execute("INSERT OR IGNORE INTO bubbles(id,area,lat,lon,type,text,road,author,created,img,device,received,visibility) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (b["id"], b["area"], b["lat"], b["lon"], b["type"], b["text"], b["road"], b["author"], b["created"], b["img"], b["device"], int(time.time()*1000), b.get("visibility", "public")))
            self.db.commit(); return cur.rowcount
    def list(self, area):
        with self.lock:
            rows = self.db.execute("SELECT id,lat,lon,type,text,road,author,created,img,visibility FROM bubbles WHERE area=? ORDER BY created DESC LIMIT 2000", (area,)).fetchall()
        out = []
        for id_, lat, lon, t, text, road, author, created, img, vis in rows:
            url = (FILES_DATA + img[len("files:"):]) if img.startswith("files:") else (("/bubble/img/" + img) if img else "")
            out.append({"id": id_, "lat": lat, "lon": lon, "type": t, "text": text, "road": road, "author": author, "created": created, "img": url, "visibility": vis or "public"})
        return out

store = Store()
hits = {}  # ip -> [timestamps]

def seed():
    p = os.path.join(BASE, "seed_posts.json")
    if os.path.exists(p):
        for b in json.load(open(p))["posts"]:
            store.add({"id": b["id"], "area": "haeundae", "lat": b["lat"], "lon": b["lon"], "type": b["type"], "text": b["text"],
                       "road": b.get("road", ""), "author": b["author"], "created": b["created"], "img": "files:" + b["img"], "device": "seed"})
seed()

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, code, obj=None, ctype="application/json", raw=None):
        body = raw if raw is not None else json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        path = self.path.split("?")[0]
        if path.endswith("/api/posts"):
            area = re.search(r"area=([a-z]+)", self.path); return self._send(200, {"posts": store.list(area.group(1) if area else "haeundae")})
        m = re.search(r"/img/([A-Za-z0-9_-]{1,40})\.jpg$", path)
        if m and os.path.exists(os.path.join(IMG, m.group(1) + ".jpg")):
            return self._send(200, raw=open(os.path.join(IMG, m.group(1) + ".jpg"), "rb").read(), ctype="image/jpeg")
        self._send(404, {"error": "not found"})
    def do_POST(self):
        path = self.path.split("?")[0]
        if path.endswith("/api/runs"): return self._post_run()
        if not path.endswith("/api/posts"): return self._send(404, {"error": "not found"})
        if self.headers.get("X-Client-Key") != KEY: return self._send(403, {"error": "denied"})
        ip = self.headers.get("X-Real-IP") or self.client_address[0]; now = time.time()
        h = [t for t in hits.get(ip, []) if now - t < 3600]
        if len(h) >= 40: return self._send(429, {"error": "too many"})
        hits[ip] = h + [now]
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > 4_500_000: return self._send(413, {"error": "too large"})
        try:
            d = json.loads(self.rfile.read(n))
            b = {"id": str(d["id"]), "area": "haeundae", "lat": float(d["lat"]), "lon": float(d["lon"]), "type": d["type"],
                 "text": str(d.get("text", ""))[:500], "road": str(d.get("road", ""))[:60], "author": str(d.get("author", "러너"))[:20],
                 "created": int(d.get("created", now * 1000)), "img": "", "device": str(d.get("device", ""))[:40],
                 "visibility": "friends" if d.get("visibility") == "friends" else "public"}
            assert ID_RE.match(b["id"]) and b["type"] in TYPES and 33 < b["lat"] < 39 and 124 < b["lon"] < 132
            if d.get("photo"):
                raw = base64.b64decode(d["photo"]); assert raw[:3] == b"\xff\xd8\xff" and len(raw) < 3_000_000
                open(os.path.join(IMG, b["id"] + ".jpg"), "wb").write(raw); b["img"] = b["id"] + ".jpg"
        except Exception:
            return self._send(400, {"error": "bad request"})
        store.add(b); self._send(200, {"ok": True})

    def _post_run(self):
        if self.headers.get("X-Client-Key") != KEY: return self._send(403, {"error": "denied"})
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > 4_500_000: return self._send(413, {"error": "too large"})
        try:
            d = json.loads(self.rfile.read(n)); rid = str(d["id"]); assert ID_RE.match(rid)
            png = ""
            if d.get("png"):
                raw = base64.b64decode(d["png"]); assert raw[:4] == b"\x89PNG" and len(raw) < 1_500_000
                open(os.path.join(RUNIMG, rid + ".png"), "wb").write(raw); png = rid + ".png"
            store.add_run({"id": rid, "start": int(d["startMs"]), "dur": int(d["durationSec"]), "dist": float(d["distanceM"]), "hr": int(d.get("avgHr", 0)),
                           "asc": int(d.get("ascentM", 0)), "title": str(d.get("title", ""))[:80], "device": str(d.get("device", ""))[:40], "pts": json.dumps(d.get("pts", []))[:200000], "png": png})
        except Exception:
            return self._send(400, {"error": "bad request"})
        self._send(200, {"ok": True})

if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("BUBBLE_PORT", "5301"))), H).serve_forever()
