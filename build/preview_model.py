import json, io, math, sys
from PIL import Image, ImageDraw

BASE = r"E:/beer/rust/mc-nebula/javamc/server-1.21.11/nebula/物品/资源"
MODEL = sys.argv[1] if len(sys.argv) > 1 else "guard_model"
OUT = sys.argv[2] if len(sys.argv) > 2 else "build/preview_mob.png"

m = json.load(io.open(f"{BASE}/物品模型/{MODEL}.json", encoding="utf8"))
texs = {}
for k, v in m["textures"].items():
    if k == "particle":
        continue
    texs[k] = Image.open(f"{BASE}/物品材质/{v.split(':')[1].split('/')[-1]}.png").convert("RGB")

# 面定义：顶点顺序 + 法线 + 在 uv 中对应的 (u,v) 轴
FACE_DEF = {
    "down":  ([(0,0,0),(1,0,0),(1,0,1),(0,0,1)], (0,-1,0)),
    "up":    ([(0,1,0),(0,1,1),(1,1,1),(1,1,0)], (0,1,0)),
    "north": ([(0,0,0),(0,1,0),(1,1,0),(1,0,0)], (0,0,-1)),
    "south": ([(1,0,1),(1,1,1),(0,1,1),(0,0,1)], (0,0,1)),
    "west":  ([(0,0,1),(0,1,1),(0,1,0),(0,0,0)], (-1,0,0)),
    "east":  ([(1,0,0),(1,1,0),(1,1,1),(1,0,1)], (1,0,0)),
}

def rot(p, spec):
    ax = spec["axis"]; ang = math.radians(spec["angle"]); o = spec["origin"]
    x, y, z = p[0]-o[0], p[1]-o[1], p[2]-o[2]
    c, s = math.cos(ang), math.sin(ang)
    if ax == "y":
        x, z = x*c + z*s, -x*s + z*c
    elif ax == "x":
        y, z = y*c - z*s, y*s + z*c
    else:
        x, y = x*c - y*s, x*s + y*c
    return (x+o[0], y+o[1], z+o[2])

faces = []
for el in m["elements"]:
    f, t = el["from"], el["to"]
    for name, (corners, nrm) in FACE_DEF.items():
        if name not in el.get("faces", {}):
            continue
        fd = el["faces"][name]
        pts = []
        for (cx, cy, cz) in corners:
            p = (f[0] if cx == 0 else t[0], f[1] if cy == 0 else t[1], f[2] if cz == 0 else t[2])
            if "rotation" in el:
                p = rot(p, el["rotation"])
            pts.append(((p[0]-8)/16.0, (p[1]-8)/16.0, (p[2]-8)/16.0))
        if "rotation" in el:
            nrm = rot(nrm, {"origin": [0, 0, 0], "axis": el["rotation"]["axis"],
                            "angle": el["rotation"]["angle"]})
        uv = fd["uv"]
        faces.append({"pts": pts, "n": nrm, "uv": uv, "tex": fd["texture"],
                      "avg_y": sum(p[1] for p in pts)/4.0})

def view(kind):
    # 返回 (屏幕x, 屏幕y, 深度)；深度越大越靠近相机，绘制时由远及近
    if kind == "front":      # 相机在 +Z
        return lambda p: (p[0], -p[1], p[2])
    if kind == "side":       # 相机在 +X
        return lambda p: (-p[2], -p[1], p[0])
    # iso：相机在 (+x,+y,+z) 方向
    return lambda p: ((p[0]-p[2])*0.866, -p[1] + (p[0]+p[2])*0.45, p[0]+p[1]+p[2])

LIGHT = (0.45, 0.75, 0.48)
def shade(n):
    l = sum(a*b for a, b in zip(n, LIGHT))
    return max(0.35, min(1.0, 0.55 + 0.55*l))

SIZE = 300
tiles = []
for kind in ("front", "side", "iso"):
    proj = view(kind)
    img = Image.new("RGB", (SIZE, SIZE), (24, 24, 32))
    d = ImageDraw.Draw(img)
    order = sorted(faces, key=lambda fc: proj((sum(p[0] for p in fc["pts"])/4,
                                              sum(p[1] for p in fc["pts"])/4,
                                              sum(p[2] for p in fc["pts"])/4))[2])
    for fc in order:
        tex = texs[fc["tex"].lstrip("#")]
        u0, v0, u1, v1 = fc["uv"]
        sx = max(0, min(15, int((u0+u1)/2)))
        sy = max(0, min(15, int((v0+v1)/2)))
        c = tex.getpixel((sx, sy))
        k = shade(fc["n"])
        col = (int(c[0]*k), int(c[1]*k), int(c[2]*k))
        poly = []
        for p in fc["pts"]:
            x, y, _ = proj(p)
            poly.append((SIZE/2 + x*SIZE*0.85, SIZE/2 + y*SIZE*0.85))
        d.polygon(poly, fill=col, outline=(0, 0, 0))
    d.text((6, 6), kind, fill=(200, 200, 200))
    tiles.append(img)

out = Image.new("RGB", (SIZE*len(tiles), SIZE), (24, 24, 32))
for i, t in enumerate(tiles):
    out.paste(t, (i*SIZE, 0))
out.save(OUT)
print("saved", OUT, "faces", len(faces))
