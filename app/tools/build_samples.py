#!/usr/bin/env python3
"""샘플 사람 10명(나1·친구4·다른 사람5) + 사고 사진 5장 + 샘플 글. 결과: app/src/main/assets/samples/
사진/아바타는 임시 그림이다. 같은 파일 이름(person_1~10.png, accident_1~5.jpg)으로 덮어쓰면 반영된다."""
import json, math, os, random, time, urllib.request
from PIL import Image, ImageDraw, ImageFont
OUT = "app/src/main/assets/samples"; os.makedirs(OUT, exist_ok=True)
FONT = "app/src/main/res/font/suite.ttf"
random.seed(11)
people = [("p1","나","me"),("p2","민준","friend"),("p3","서연","friend"),("p4","지호","friend"),("p5","하윤","friend"),
          ("p6","달리는곰","other"),("p7","부산갈매기","other"),("p8","해운대러너","other"),("p9","새벽조깅","other"),("p10","광안파도","other")]
cols = [(182,255,26),(255,120,200),(90,200,255),(255,190,60),(160,120,255),(255,120,100),(80,220,170),(255,160,60),(120,160,255),(200,200,80)]
for i,(pid,name,rel) in enumerate(people,1):
    im=Image.new("RGB",(256,256),cols[i-1]); d=ImageDraw.Draw(im)
    d.ellipse([88,50,168,130],fill=(255,255,255)); d.ellipse([34,138,222,330],fill=(255,255,255)); im.save(f"{OUT}/person_{i}.png")
json.dump([{"id":p,"name":n,"relation":r,"avatar":f"samples/person_{i}.png"} for i,(p,n,r) in enumerate(people,1)], open(f"{OUT}/people.json","w"), ensure_ascii=False, indent=1)
f = ImageFont.truetype(FONT, 54)
acc = [("가로등 고장",(40,44,60),"💡"),("벤치 파손",(60,50,40),"🪑"),("보도블록 파손",(50,56,50),"🧱"),("길 가장자리 붕괴",(64,44,44),"⚠"),("난간 흔들림",(40,56,64),"🚧")]
for i,(t,bg,_) in enumerate(acc,1):
    im=Image.new("RGB",(720,480),bg); d=ImageDraw.Draw(im)
    d.rectangle([0,330,720,480],fill=(90,90,95)); d.polygon([(0,480),(260,330),(460,330),(720,480)],fill=(120,120,125))
    d.ellipse([280,250,440,330],fill=(20,18,18))
    for a,b in [((330,250),(300,200)),((380,250),(410,190)),((300,300),(220,340)),((430,300),(520,345))]: d.line([a,b],fill=(25,25,25),width=6)
    d.text((360,90),t,font=f,fill=(255,255,255),anchor="mm"); d.text((360,150),"(임시 사진)",font=ImageFont.truetype(FONT,28),fill=(182,255,26),anchor="mm")
    im.save(f"{OUT}/accident_{i}.jpg",quality=88)
# 샘플 글: 해운대 코스를 따라
API="https://dongnam-hackathon.magabus.workers.dev"
courses=json.load(urllib.request.urlopen(urllib.request.Request(API+"/api/courses",headers={"User-Agent":"x"}),timeout=30))["courses"]
def hav(a,b):
    r=6371000;p1,p2=math.radians(a[0]),math.radians(b[0]);dl=math.radians(b[1]-a[1]);dp=p2-p1
    return 2*r*math.asin(math.sqrt(math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2))
def along(pts,fr):
    tot=sum(hav(a,b) for a,b in zip(pts,pts[1:]));t=tot*fr;acc=0
    for a,b in zip(pts,pts[1:]):
        d=hav(a,b)
        if d>0 and acc+d>=t:
            k=(t-acc)/d;return a[0]+(b[0]-a[0])*k,a[1]+(b[1]-a[1])*k
        acc+=d
    return pts[-1]
PER=["바다 보면서 뛰니까 스트레스가 확 풀려요","오늘 페이스 최고! 바람이 도와줬어요","초보도 뛰기 편한 평지예요","해 질 녘에 오면 진짜 예뻐요","친구랑 같이 뛰기 좋아요","아침 공기가 정말 좋아요","벤치랑 화장실이 가까워서 편해요","야경 맛집이에요, 밤 러닝 추천"]
ACC=[("warn","facility","가로등이 꺼져 있어요. 밤에는 조심하세요",1),("facility","facility","벤치 다리가 부러져 있어요",2),("warn","facility","보도블록이 깨져서 발을 헛디딜 수 있어요",3),("warn","warn","길 가장자리가 무너졌어요. 안쪽으로 달리세요",4),("facility","facility","난간이 흔들려요. 조심해서 지나가요",5)]
posts=[]; now=int(time.time()*1000); n=0
for c in courses:
    g=json.load(urllib.request.urlopen(urllib.request.Request(f"{API}/api/courses/{c['code']}/geojson",headers={"User-Agent":"x"}),timeout=30))
    lines=[[(p[1],p[0]) for p in (ft["geometry"]["coordinates"])] for ft in g["features"] if ft.get("properties",{}).get("layer","course")=="course" and ft["geometry"]["type"]=="LineString"]
    if not lines: continue
    pts=max(lines,key=len)
    for fr in (0.2,0.5,0.8):
        la,lo=along(pts,fr); n+=1
        if n%6==0 and n//6<=5:
            k=ACC[n//6-1]; pid=random.choice([p for p in people if p[2]!="me"]); vis="public"; typ=k[1]; img=f"samples/accident_{k[3]}.jpg"; txt=k[2]
        else:
            pid=people[random.randrange(10)]; typ="person"; img=""; txt=random.choice(PER); vis=("friends" if pid[2]=="friend" and random.random()<.5 else "public")
        posts.append({"id":f"x{n}","lat":round(la,5),"lon":round(lo,5),"type":typ,"road":c["name"].split("(")[0],"authorId":pid[0],"author":pid[1],"text":txt,"img":img,"visibility":vis,"created":now-random.randint(1,60)*3600*1000})
json.dump(posts,open(f"{OUT}/posts.json","w"),ensure_ascii=False,indent=1); print(len(posts),"posts")
