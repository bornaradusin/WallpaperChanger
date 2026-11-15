"""
Makes made-up sample pictures and videos for the wiki's screenshots, and the app's library file
that lists them in two albums (home screen pictures, lock screen videos), with a few rules.
Nothing personal is ever shown.

    python3 seed.py <output folder>
"""
import json
import math
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
FILES = "/data/data/io.github.bornaradusin.wallpaperchanger/files/shared"

# Soft abstract pictures: two or three blurred colour blobs on a gradient
PALETTES = [
    ((16, 24, 64), (64, 120, 255), (160, 220, 255)),
    ((40, 10, 40), (220, 60, 120), (255, 180, 120)),
    ((8, 40, 32), (40, 180, 120), (200, 255, 200)),
    ((60, 30, 0), (255, 140, 40), (255, 230, 150)),
    ((20, 20, 30), (120, 100, 220), (255, 160, 220)),
    ((0, 30, 50), (0, 160, 200), (240, 250, 255)),
    ((30, 0, 0), (200, 40, 40), (255, 200, 120)),
    ((10, 10, 10), (90, 90, 110), (230, 230, 240)),
]


def picture(i, colours):
    w, h = 1080, 2400
    base, mid, light = colours
    im = Image.new("RGB", (w, h), base)
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = y / h
        c = tuple(int(base[k] + (mid[k] - base[k]) * t * 0.6) for k in range(3))
        d.line([(0, y), (w, y)], fill=c)
    blobs = Image.new("RGB", (w, h), (0, 0, 0))
    b = ImageDraw.Draw(blobs)
    for j in range(3):
        a = (i * 1.7 + j * 2.1)
        cx = int(w * (0.5 + 0.35 * math.sin(a)))
        cy = int(h * (0.35 + 0.3 * math.cos(a * 1.3)))
        r = 300 + 120 * j
        b.ellipse([cx - r, cy - r, cx + r, cy + r], fill=light if j == 0 else mid)
    blobs = blobs.filter(ImageFilter.GaussianBlur(160))
    im = Image.blend(im, blobs, 0.55)
    name = f"sample-{i + 1:02d}.jpg"
    im.save(os.path.join(OUT, name), quality=88)
    return name


def video(i):
    name = f"clip-{i + 1:02d}.mp4"
    path = os.path.join(OUT, name)
    sources = [
        f"gradients=s=720x1280:speed=0.03:d=6:c0=0x101848:c1=0x4078ff:c2=0xa0dcff:seed={i + 3}",
        f"gradients=s=720x1280:speed=0.04:d=6:c0=0x280a28:c1=0xdc3c78:c2=0xffb478:seed={i + 7}",
        "mandelbrot=s=720x1280:rate=25",
    ]
    for src in sources[i % 3:] + sources[:i % 3]:
        r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", src, "-t", "6",
                            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast", path])
        if r.returncode == 0:
            return name
    raise SystemExit("Couldn't make a sample video")


pictures = [picture(i, PALETTES[i % len(PALETTES)]) for i in range(8)]
videos = [video(i) for i in range(4)]


def item(n, album, name, is_video, folder=None):
    return {"id": f"item-{album}-{n}", "album": album, "uri": f"file://{FILES}/{name}",
            "video": is_video, "name": name, "folder": folder}


home_folder = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FWallpapers"
lock_folder = "content://com.android.externalstorage.documents/tree/primary%3AMovies%2FLock%20screen"
items = [item(n, "home", p, False, home_folder) for n, p in enumerate(pictures)]
items += [item(n, "lock", v, True, lock_folder) for n, v in enumerate(videos)]
items[2]["crop"] = {"l": 0.1, "t": 0.05, "r": 0.85, "b": 0.75}

library = {
    "albums": [
        {"id": "home", "name": "Wallpapers", "active": True, "lock": False},
        {"id": "lock", "name": "Lock screen videos", "active": False, "lock": True},
        {"id": "winter", "name": "Winter", "active": False, "lock": False},
    ],
    "items": items,
    "folders": [
        {"uri": home_folder, "album": "home", "name": "Wallpapers"},
        {"uri": lock_folder, "album": "lock", "name": "Lock screen"},
    ],
    "rules": [
        {"id": "r1", "trigger": {"type": "time", "hour": 7, "minute": 30, "days": 31}, "action": {"type": "next"}, "enabled": True},
        {"id": "r2", "trigger": {"type": "place", "lat": 53.3438, "lon": -6.2546, "radius": 300.0, "label": "Work"},
         "action": {"type": "album", "album": "winter"}, "enabled": True},
        {"id": "r3", "trigger": {"type": "wifi", "ssid": "Home Wi-Fi"}, "action": {"type": "album", "album": "home"}, "enabled": False},
    ],
    "settings": {"lockOwn": True, "interval": 0, "onUnlock": True, "random": True, "rescanHours": 24},
    "current": items[0]["id"],
    "lockCurrent": items[len(pictures)]["id"],
    # Far in the future, so the made-up folders aren't looked through
    "scanned": 4102444800000,
}
with open(os.path.join(OUT, "library.json"), "w") as f:
    json.dump(library, f)
print("seeded", len(pictures), "pictures and", len(videos), "videos")
