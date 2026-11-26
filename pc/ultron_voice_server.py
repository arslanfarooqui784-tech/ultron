#!/usr/bin/env python3
"""
ULTRON - voice bridge, face builder and app server.
Double-click this file. It opens ULTRON in your web browser.
Leave the black window open. Needs ultron_index.html in the same folder.
The Fish Audio API key is read from fish_api_key.txt in this folder or in Documents.
"""

import json
import os
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
HOST = "127.0.0.1"
PORT = int(os.environ.get("ULTRON_PORT", "8765"))

# The "Deep" voice chosen for ULTRON on Fish Audio
VOICE_ID = os.environ.get("ULTRON_VOICE_ID", "bb742915b1fa41b389a850b11efae0c8")
# Fish Audio speech model ("s2.1-pro-free" is the free developer tier)
TTS_MODEL = os.environ.get("FISH_MODEL", "s2.1-pro-free")
MAX_CHARS = 600

EXTRA_JS = r"""(function () {
  var head = document.getElementById('head'), root = document.getElementById('root');
  if (!head || !root) return;
  var names = ['smile', 'happy', 'curious', 'surprised', 'angry', 'sad'];
  var css = document.createElement('style');
  css.textContent = '.u-expr{transition:opacity .35s ease}#u-exprbar{position:absolute;bottom:10px;left:50%;transform:translateX(-50%);z-index:5;display:flex;gap:2px;padding:3px;border:1px solid rgba(150,160,175,.16);border-radius:4px;background:rgba(9,11,15,.75)}#u-exprbar .u-seg{height:26px;font-size:10px;padding:0 9px}';
  document.head.appendChild(css);
  var first = head.querySelector('.u-layer');
  var L = {};
  names.forEach(function (n) {
    var img = document.createElement('img');
    img.className = 'u-layer u-expr';
    img.src = 'face/x-' + n + '.png';
    img.alt = '';
    head.insertBefore(img, first);
    L[n] = img;
  });
  var cur = '', timer = null;
  var bar = document.createElement('div');
  bar.id = 'u-exprbar';
  bar.setAttribute('role', 'group');
  bar.setAttribute('aria-label', 'Facial expression');
  function show(n, ms) {
    clearTimeout(timer);
    cur = n;
    names.forEach(function (k) { L[k].classList.toggle('on', k === n); });
    bar.querySelectorAll('button').forEach(function (b) { b.className = b.dataset.x === (n || 'neutral') ? 'u-seg on' : 'u-seg'; });
    if (n && ms) timer = setTimeout(function () { show('', 0); }, ms);
  }
  ['neutral'].concat(names).forEach(function (n) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'u-seg';
    b.textContent = n.toUpperCase();
    b.dataset.x = n;
    b.onclick = function () { show(n === 'neutral' ? '' : n, 0); };
    bar.appendChild(b);
  });
  root.appendChild(bar);
  show('', 0);
  var last = 'idle';
  function mode() { var m = root.className.match(/m-(\w+)/); return m ? m[1] : 'idle'; }
  new MutationObserver(function () {
    var m = mode();
    if (m === last) return;
    var prev = last;
    last = m;
    if (m === 'think') show('curious', 0);
    else if (m === 'listen') show('surprised', 1400);
    else if (m === 'speak') { if (cur === 'curious' || cur === 'surprised') show('', 0); }
    else if (m === 'idle' && prev === 'speak') show('smile', 2800);
    else if (m === 'idle' && cur === 'curious') show('', 0);
  }).observe(root, { attributes: true, attributeFilter: ['class'] });
  (function idle() {
    setTimeout(function () {
      if (mode() === 'idle' && !cur) show(Math.random() < 0.6 ? 'smile' : 'curious', 2400);
      idle();
    }, 15000 + Math.random() * 15000);
  })();
})();
"""

# ---------------- ULTRON face (built from your photo) ----------------
FACE_DIR = os.path.join(HERE, "ULTRON_face2")
FACE_FILES = ["base", "blink", "squint", "mouth-a", "mouth-e", "mouth-o", "mouth-i", "facemask",
              "x-smile", "x-happy", "x-curious", "x-surprised", "x-angry", "x-sad"]
PHOTO_NAME = "WhatsApp Image 2026-11-10 at 11.34.44 PM"
CROP = (100, 130, 800, 930)  # x0, y0, x1, y1 in source pixels
EYES = [(367, 548), (550, 550)]


def process(src_bgr, glow=1.0):
    import numpy as np, cv2
    img = src_bgr.astype(np.float32) / 255.0
    H, W = img.shape[:2]
    b, g, r = img[..., 0], img[..., 1], img[..., 2]
    yy = np.arange(H)[:, None].astype(np.float32)
    xx = np.arange(W)[None, :].astype(np.float32)

    bg = (b > 0.45) & (b - r > 0.28) & (b - g > 0.05)
    mask = (~bg).astype(np.uint8) * 255
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    mask = cv2.erode(mask, np.ones((3, 3), np.uint8))
    mask = cv2.GaussianBlur(mask, (0, 0), 2.2).astype(np.float32) / 255.0
    fade = np.clip((905 - yy) / 45.0, 0, 1) ** 1.2
    w = np.clip((yy - 840) / 50.0, 0, 1)
    neck = 1 - w * (1 - np.clip((125 - np.abs(xx - 452)) / 40.0, 0, 1))
    fade = fade * neck
    skin = ((r > 0.30) & (r - b > 0.10) & (r - g > 0.04) & (g - b > 0.0)).astype(np.uint8) * 255
    skin = cv2.morphologyEx(skin, cv2.MORPH_OPEN, np.ones((7, 7), np.uint8))
    skin = cv2.morphologyEx(skin, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    skin = cv2.erode(skin, np.ones((7, 7), np.uint8))
    skin = cv2.GaussianBlur(skin, (0, 0), 4).astype(np.float32) / 255.0
    wy = np.clip((yy - 835) / 40.0, 0, 1)
    fade = fade * (1 - wy * (1 - skin))
    mask *= fade

    gray = cv2.cvtColor(src_bgr, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=3.2, tileGridSize=(8, 8))
    L = clahe.apply(gray).astype(np.float32) / 255.0
    L = cv2.bilateralFilter(L, 9, 0.12, 6)
    blur = cv2.GaussianBlur(L, (0, 0), 6)
    detail = L - blur
    lift = 1 + 0.55 * np.clip((yy - 560) / 260.0, 0, 1)
    L = np.clip((blur ** 1.9) * 0.62 * lift + detail * 1.5, 0, 1)
    beard = np.clip((0.22 - blur) / 0.12, 0, 1) * (yy > 640)
    L = L * (1 - 0.55 * beard)
    k = np.array([[-2, -1, 0], [-1, 1, 1], [0, 1, 2]], np.float32)
    emb = cv2.filter2D(cv2.GaussianBlur(L, (0, 0), 1.4), -1, k)
    inner = cv2.erode((mask * 255).astype(np.uint8), np.ones((15, 15), np.uint8)).astype(np.float32) / 255.0
    spec = (np.clip(emb * 1.5, 0, 1) ** 1.4) * inner

    stops = np.array([0.0, 0.18, 0.45, 0.75, 1.0])
    cols = np.array([[3, 4, 5], [10, 12, 15], [27, 32, 40], [70, 79, 92], [170, 180, 194]], np.float32) / 255.0
    rgb = np.zeros((H, W, 3), np.float32)
    for c in range(3):
        rgb[..., c] = np.interp(L, stops, cols[:, c])
    rgb += spec[..., None] * np.array([0.32, 0.36, 0.42])
    side = np.clip((xx - 470) / 260.0, 0, 1) ** 1.4
    rgb *= (1 - 0.38 * side)[..., None]
    under = np.clip((yy - 640) / 260.0, 0, 1) * np.clip((1000 - yy) / 120.0, 0, 1)
    rgb[..., 0] += under * L * 0.08

    if glow > 0:
        for (ex, ey) in EYES:
            d = np.sqrt((xx - ex) ** 2 + (yy - ey) ** 2)
            core = np.clip(1 - d / 13.0, 0, 1) ** 0.8
            halo = np.exp(-((xx - ex) ** 2 / (2 * 30 ** 2) + (yy - ey) ** 2 / (2 * 9 ** 2)))
            rgb[..., 0] += (halo * 0.55 + core * 1.0) * glow
            rgb[..., 1] += (core * 0.18 + halo * 0.02) * glow
            rgb[..., 2] += (core * 0.22 + halo * 0.05) * glow
            hot = np.clip(1 - d / 4.5, 0, 1)
            rgb += hot[..., None] * 0.9 * glow

    rgb = np.clip(rgb, 0, 1)
    x0, y0, x1, y1 = CROP
    rgb = rgb[y0:y1, x0:x1]
    a = mask[y0:y1, x0:x1]
    return np.dstack([rgb, a])


def save_rgba(path, rgba):
    import numpy as np, cv2
    rgba = rgba.copy()
    rgba[rgba[..., 3] <= 0, :3] = 0
    out = (np.clip(rgba, 0, 1) * 255).astype(np.uint8)
    cv2.imwrite(path, cv2.cvtColor(out, cv2.COLOR_RGBA2BGRA))


def patch(rgba, region_alpha):
    out = rgba.copy()
    out[..., 3] = out[..., 3] * region_alpha
    return out


def build_faces(photo, outdir):
    import numpy as np, cv2
    os.makedirs(outdir, exist_ok=True)
    src = cv2.imread(photo)
    if src is None or src.shape[:2] != (1600, 900):
        raise RuntimeError("Photo not found or not the original 900x1600 photo: " + str(photo))
    H, W = src.shape[:2]
    x0, y0, x1, y1 = CROP
    cw, ch = x1 - x0, y1 - y0
    cyy = np.arange(ch)[:, None].astype(np.float32)
    cxx = np.arange(cw)[None, :].astype(np.float32)

    def ellipse_alpha(cx, cy, rx, ry, feather=0.35):
        e = ((cxx - cx) / rx) ** 2 + ((cyy - cy) / ry) ** 2
        return np.clip((1 - e) / feather, 0, 1)

    base = process(src, glow=1.0)
    save_rgba(os.path.join(outdir, "base.png"), base)

    eye_region = np.maximum.reduce([ellipse_alpha(ex - x0, ey - y0, 62, 28) for ex, ey in EYES])

    def lids(frac):
        im = src.copy()
        m = np.zeros((H, W), np.uint8)
        for ex, ey in EYES:
            top = ey - 14
            cut = int(round(top + (28 * frac)))
            em = np.zeros((H, W), np.uint8)
            cv2.ellipse(em, (ex, ey), (47, 15), 0, 0, 360, 255, -1)
            em[cut:, :] = 0
            m |= em
        m = cv2.dilate(m, np.ones((3, 3), np.uint8))
        im = cv2.inpaint(im, m, 9, cv2.INPAINT_TELEA)
        blurred = cv2.GaussianBlur(im, (0, 0), 2.5)
        mf = cv2.GaussianBlur(m, (0, 0), 2).astype(np.float32)[..., None] / 255.0
        im = (im * (1 - mf) + blurred * mf).astype(np.uint8)
        for ex, ey in EYES:
            ly = int(round(ey - 14 + 28 * frac))
            pts = np.array([[ex - 44, ly - 1], [ex - 20, ly + 2], [ex, ly + 3], [ex + 20, ly + 2], [ex + 44, ly - 1]], np.int32)
            cv2.polylines(im, [pts], False, (28, 22, 24), 2, cv2.LINE_AA)
        return im

    blink = process(lids(1.0), glow=0.0)
    save_rgba(os.path.join(outdir, "blink.png"), patch(blink, eye_region))
    squint = process(lids(0.5), glow=0.7)
    save_rgba(os.path.join(outdir, "squint.png"), patch(squint, eye_region))

    MY, MX = 724, 451
    mouth_region = ellipse_alpha(MX - x0, 790 - y0, 165, 150, 0.3)
    yy = np.arange(H)[:, None].astype(np.float32)
    xx = np.arange(W)[None, :].astype(np.float32)

    def mouth(h, wf):
        hw = 70 * wf
        wx = np.exp(-((xx - MX) / (105.0)) ** 2)
        fall = np.clip((960 - yy) / 80.0, 0, 1)
        dy = np.where(yy >= MY, h * wx * fall, 0).astype(np.float32)
        map_x = np.repeat(xx, H, 0).astype(np.float32)
        map_y = (np.repeat(yy, W, 1) - dy).astype(np.float32)
        im = cv2.remap(src, map_x, map_y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
        cav = np.zeros((H, W), np.uint8)
        cv2.ellipse(cav, (MX, int(MY + h * 0.5)), (int(hw), max(2, int(h * 0.55))), 0, 0, 360, 255, -1)
        cavf = cv2.GaussianBlur(cav, (0, 0), 1.6).astype(np.float32)[..., None] / 255.0
        inside = np.zeros_like(im)
        inside[:] = (18, 10, 12)
        im = (im * (1 - cavf) + inside * cavf).astype(np.uint8)
        return im

    def draw_cavity(fr, h, wf, teeth):
        cx, cy = MX - x0, MY - y0 + h * 0.5
        rx, ry = 66 * wf, max(2.0, h * 0.58)
        e = ((cxx - cx) / rx) ** 2 + ((cyy - cy) / ry) ** 2
        cav = np.clip((1 - e) / 0.25, 0, 1)
        col = np.array([0.012, 0.012, 0.016])
        shade = np.clip((cyy - (cy - ry)) / (2 * ry), 0, 1)[..., None]
        inner = col + shade * np.array([0.05, 0.055, 0.065])
        fr[..., :3] = fr[..., :3] * (1 - cav[..., None]) + inner * cav[..., None]
        if teeth > 0:
            top = cy - ry
            band = cav * ((cyy >= top) & (cyy < top + 2 * ry * teeth)).astype(np.float32)
            band = cv2.GaussianBlur(band, (0, 0), 1.0)
            band *= np.clip((1 - ((cxx - cx) / (rx * 0.8)) ** 2) / 0.35, 0, 1)
            fr[..., :3] = fr[..., :3] * (1 - band[..., None] * 0.8) + np.array([0.46, 0.5, 0.56]) * band[..., None] * 0.8
        rim = np.exp(-(((np.sqrt(e) - 1.08) / 0.07) ** 2)) * (cyy > cy) * np.clip(1 - np.abs(cxx - cx) / rx, 0, 1)
        fr[..., :3] += rim[..., None] * np.array([0.22, 0.24, 0.28])
        return fr

    for name, h, wf, teeth in [("mouth-a", 34, 1.1, 0.25), ("mouth-e", 18, 1.2, 0.4), ("mouth-o", 28, 0.65, 0.0), ("mouth-i", 10, 1.05, 0.55)]:
        fr = process(mouth(h, wf), glow=0.0)
        fr = draw_cavity(fr, h, wf, teeth)
        save_rgba(os.path.join(outdir, name + ".png"), patch(fr, mouth_region))

    g = cv2.GaussianBlur(cv2.cvtColor(src, cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0, (0, 0), 4)
    yyf = np.arange(H)[:, None].astype(np.float32)
    xxf = np.arange(W)[None, :].astype(np.float32)
    skin = np.clip((g - 0.26) / 0.08, 0, 1)
    ell = ((xxf - 455) / 215.0) ** 2 + ((yyf - 600) / 330.0) ** 2
    win = np.clip((1.0 - ell) / 0.12, 0, 1)
    keep = np.where(yyf < 640, skin, 1.0) * win
    keep = cv2.morphologyEx((keep * 255).astype(np.uint8), cv2.MORPH_OPEN, np.ones((9, 9), np.uint8))
    keep = cv2.morphologyEx(keep, cv2.MORPH_CLOSE, np.ones((25, 25), np.uint8))
    keep = cv2.erode(keep, np.ones((9, 9), np.uint8))
    keep = cv2.GaussianBlur(keep, (0, 0), 6).astype(np.float32) / 255.0
    keep = keep[130:930, 100:800]
    a = (keep * base[..., 3] * 255).astype(np.uint8)
    cv2.imwrite(os.path.join(outdir, "facemask.png"), np.dstack([np.full_like(a, 255)] * 3 + [a]))
    build_expressions(src, outdir)


# expressions: (centre x, centre y, spread x, spread y, move x, move y) in photo pixels
EXPRESSIONS = {
    "smile": [(380, 728, 30, 26, -7, -14), (522, 728, 30, 26, 7, -14), (335, 650, 55, 50, 0, -5),
              (568, 650, 55, 50, 0, -5), (367, 570, 45, 10, 0, -3), (550, 572, 45, 10, 0, -3)],
    "happy": [(380, 728, 32, 28, -9, -20), (522, 728, 32, 28, 9, -20), (335, 650, 55, 50, 0, -7),
              (568, 650, 55, 50, 0, -7), (367, 570, 45, 10, 0, -4), (550, 572, 45, 10, 0, -4),
              (360, 505, 50, 22, 0, -4), (550, 505, 50, 22, 0, -4)],
    "curious": [(355, 505, 55, 24, 0, -13), (550, 507, 55, 24, 0, 3), (522, 728, 30, 26, 6, -7)],
    "surprised": [(360, 505, 60, 26, 0, -13), (550, 505, 60, 26, 0, -13)],
    "angry": [(410, 512, 32, 22, 5, 9), (495, 512, 32, 22, -5, 9), (320, 500, 40, 22, 0, -2),
              (590, 500, 40, 22, 0, -2), (380, 728, 30, 26, 0, 6), (522, 728, 30, 26, 0, 6)],
    "sad": [(410, 510, 32, 22, 0, -9), (495, 510, 32, 22, 0, -9), (380, 728, 30, 26, -2, 9),
            (522, 728, 30, 26, 2, 9)],
}


def build_expressions(src, outdir):
    import numpy as np, cv2
    H, W = src.shape[:2]
    x0, y0, x1, y1 = CROP
    cyy = np.arange(y1 - y0)[:, None].astype(np.float32)
    cxx = np.arange(x1 - x0)[None, :].astype(np.float32)
    e = ((cxx - 351) / 240.0) ** 2 + ((cyy - 500) / 280.0) ** 2
    region = np.clip((1 - e) / 0.25, 0, 1)
    yy = np.arange(H)[:, None].astype(np.float32)
    xx = np.arange(W)[None, :].astype(np.float32)
    for name, moves in EXPRESSIONS.items():
        dxs = np.zeros((H, W), np.float32)
        dys = np.zeros((H, W), np.float32)
        for cx, cy, sx, sy, mx, my in moves:
            g = np.exp(-(((xx - cx) ** 2) / (2 * sx ** 2) + ((yy - cy) ** 2) / (2 * sy ** 2)))
            dxs += mx * g
            dys += my * g
        map_x = (np.repeat(xx, H, 0) - dxs).astype(np.float32)
        map_y = (np.repeat(yy, W, 1) - dys).astype(np.float32)
        im = cv2.remap(src, map_x, map_y, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
        if name == "surprised":
            hh = 22
            cav = np.zeros((H, W), np.uint8)
            cv2.ellipse(cav, (451, 724 + hh // 2), (34, hh // 2 + 2), 0, 0, 360, 255, -1)
            wx = np.exp(-((xx - 451) / 105.0) ** 2)
            fall = np.clip((960 - yy) / 80.0, 0, 1)
            dy = np.where(yy >= 724, hh * wx * fall, 0).astype(np.float32)
            im = cv2.remap(im, np.repeat(xx, H, 0).astype(np.float32), (np.repeat(yy, W, 1) - dy).astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
            cf = cv2.GaussianBlur(cav, (0, 0), 1.6).astype(np.float32)[..., None] / 255.0
            im = (im * (1 - cf) + np.array([18, 10, 12]) * cf).astype(np.uint8)
        fr = process(im, glow=1.25 if name in ("angry", "surprised") else 1.0)
        if name == "happy":
            cx, cy = 451 - x0, 718 - y0
            rx, ry = 62.0, 7.0
            dx = (cxx - cx) / rx
            yc = cy - 10 * dx ** 2
            e2 = dx ** 2 + ((cyy - yc) / ry) ** 2
            cav = np.clip((1 - e2) / 0.3, 0, 1)
            fr[..., :3] = fr[..., :3] * (1 - cav[..., None]) + np.array([0.02, 0.02, 0.025]) * cav[..., None]
            teeth = cav * np.clip((yc + 1 - cyy) / 2.0, 0, 1) * np.clip((1 - dx ** 2) / 0.4, 0, 1)
            fr[..., :3] = fr[..., :3] * (1 - teeth[..., None] * 0.85) + np.array([0.55, 0.58, 0.64]) * teeth[..., None] * 0.85
        save_rgba(os.path.join(outdir, "x-" + name + ".png"), patch(fr, region))


def find_photo():
    home = os.path.expanduser("~")
    for folder in [os.path.join(home, "Downloads"), os.path.join(home, "OneDrive", "Downloads"),
                   os.path.join(home, "Documents"), os.path.join(home, "OneDrive", "Documents"), HERE]:
        for ext in (".jpeg", ".jpg", ".png"):
            p = os.path.join(folder, PHOTO_NAME + ext)
            if os.path.exists(p):
                return p
    return None


def ensure_faces():
    if all(os.path.exists(os.path.join(FACE_DIR, n + ".png")) for n in FACE_FILES):
        return
    print("Building ULTRON's face and expressions from your photo (first run only)...")
    try:
        import numpy, cv2  # noqa: F401
    except ImportError:
        import subprocess
        print("Installing image tools (numpy, opencv) - one time, needs internet...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "--user", "numpy", "opencv-python-headless"])
        import site, importlib
        importlib.invalidate_caches()
        sys.path.append(site.getusersitepackages())
    photo = find_photo()
    if not photo:
        raise RuntimeError("Could not find your photo '" + PHOTO_NAME + "' in Downloads or Documents.")
    build_faces(photo, FACE_DIR)
    print("Face ready.")


def key_paths():
    home = os.path.expanduser("~")
    return [
        os.path.join(HERE, "fish_api_key.txt"),
        os.path.join(home, "Documents", "fish_api_key.txt"),
        os.path.join(home, "OneDrive", "Documents", "fish_api_key.txt"),
    ]


def load_key():
    key = os.environ.get("FISH_API_KEY", "").strip()
    if key:
        return key
    for path in key_paths():
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8-sig") as f:
                key = f.read().replace("PASTE_YOUR_FISH_AUDIO_API_KEY_HERE", "").strip()
            if key:
                return key.split()[0]
    return ""


def synthesize(text):
    key = load_key()
    if not key:
        raise RuntimeError("No API key found. Put it in fish_api_key.txt next to this script, or set FISH_API_KEY.")
    body = json.dumps({
        "text": text,
        "reference_id": VOICE_ID,
        "format": "mp3",
        "mp3_bitrate": 128,
        "latency": "balanced",
        "prosody": {"speed": 0.95},
    }).encode("utf-8")
    req = urllib.request.Request(
        "https://api.fish.audio/v1/tts",
        data=body,
        method="POST",
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
            "model": TTS_MODEL,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        hints = {
            401: "The API key was rejected. Check fish_api_key.txt.",
            402: "Your Fish Audio account has no API credit for this model. Add credit in Billing, or keep the free model s2.1-pro-free.",
            429: "Too many requests. Wait a moment and try again.",
        }
        raise RuntimeError(f"Fish Audio error {e.code}. {hints.get(e.code, '')} {detail}".strip())


class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Private-Network", "true")

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def _json(self, code, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self._cors()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _file(self, path, ctype):
        with open(path, "rb") as f:
            self._bytes(f.read(), ctype)

    def _bytes(self, data, ctype):
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        route = self.path.split("?")[0]
        if route.startswith("/health"):
            self._json(200, {"ok": True, "engine": "fish", "voice": VOICE_ID, "model": TTS_MODEL, "key": bool(load_key())})
            return
        if route in ("/", "/index.html"):
            page = os.path.join(HERE, "ultron_index.html")
            if os.path.exists(page):
                with open(page, "r", encoding="utf-8") as f:
                    html = f.read()
                html = html.replace("</body>", '<script src="ultron_extra.js"></script></body>')
                self._bytes(html.encode("utf-8"), "text/html; charset=utf-8")
                return
        if route == "/ultron_extra.js":
            self._bytes(EXTRA_JS.encode("utf-8"), "application/javascript; charset=utf-8")
            return
        if route.startswith("/face/"):
            name = os.path.basename(route)
            path = os.path.join(FACE_DIR, name)
            if name.endswith(".png") and os.path.exists(path):
                self._file(path, "image/png")
                return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.startswith("/speak"):
            self._json(404, {"error": "not found"})
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(n) or b"{}")
            text = str(payload.get("text", "")).strip()[:MAX_CHARS]
            if not text:
                self._json(400, {"error": "no text"})
                return
            audio = synthesize(text)
        except Exception as e:
            print("  ! " + str(e))
            self._json(502, {"error": str(e)})
            return
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "audio/mpeg")
        self.send_header("Content-Length", str(len(audio)))
        self.end_headers()
        self.wfile.write(audio)
        print(f"  > spoke {len(text)} chars")

    def log_message(self, fmt, *args):
        pass


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--test":
        text = " ".join(sys.argv[2:]) or "ULTRON online. All systems are operational."
        audio = synthesize(text)
        out = os.path.join(HERE, "ultron_test.mp3")
        with open(out, "wb") as f:
            f.write(audio)
        print("Saved " + out)
        return
    if not load_key():
        print("Warning: no API key yet. Add fish_api_key.txt before speaking.")
    try:
        ensure_faces()
    except Exception as e:
        print("Face setup problem: " + str(e))
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    url = f"http://{HOST}:{PORT}/"
    print(f"ULTRON is running on {url}  (voice {VOICE_ID}, model {TTS_MODEL})")
    print("Leave this window open. Press Ctrl+C to stop.")
    if os.path.exists(os.path.join(HERE, "ultron_index.html")) and "--no-open" not in sys.argv:
        import webbrowser
        webbrowser.open(url)
    server.serve_forever()


if __name__ == "__main__":
    print("ULTRON is locked. Start it by double-clicking ULTRON.py (password protected).")
    input("Press Enter to close...")
