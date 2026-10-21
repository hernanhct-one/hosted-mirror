"""Genera el ícono y el banner de Fire TV de Hosted Mirror (requiere Pillow).

Uso: python3 scripts/make-art.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
FONT = "/System/Library/Fonts/SFNS.ttf"

BG_TOP = (28, 31, 40)
BG_BOTTOM = (8, 8, 10)
WHITE = (245, 245, 247)


def gradient(w, h):
    img = Image.new("RGB", (w, h), BG_BOTTOM)
    px = img.load()
    for y in range(h):
        for x in range(w):
            # luz suave desde arriba a la derecha
            t = min(1.0, ((x / w - 1.0) ** 2 * 0.6 + (y / h) ** 2) ** 0.5 / 1.1)
            px[x, y] = tuple(round(BG_TOP[i] * (1 - t) + BG_BOTTOM[i] * t) for i in range(3))
    return img


def draw_mark(img, ox, oy, s):
    """Marca en una grilla de 24x24 escalada por s: pantalla con un teléfono delante."""
    def r(x0, y0, x1, y1):
        return [ox + x0 * s, oy + y0 * s, ox + x1 * s, oy + y1 * s]

    alpha = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(alpha)
    d.rounded_rectangle(r(2.5, 4.25, 18.5, 16.25), radius=2 * s, outline=255, width=max(1, round(1.6 * s)))
    d.rectangle(r(7, 18.5, 11.75, 20), fill=255)
    # hueco transparente alrededor del teléfono
    d.rounded_rectangle(r(13.0, 8.0, 23.0, 22.0), radius=2.4 * s, fill=0)
    d.rounded_rectangle(r(14.25, 9.25, 21.75, 20.75), radius=1.5 * s, fill=255)
    img.paste(Image.new("RGB", img.size, WHITE), (0, 0), alpha)


def font(size, weight="Semibold"):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


def banner():
    W, H = 1280, 720
    img = gradient(W, H)
    d = ImageDraw.Draw(img)
    s = 9.0
    title = font(118, "Semibold")
    tw = d.textlength("Hosted Mirror", font=title)
    mark_w = 24 * s
    gap = 40
    total = mark_w + gap + tw
    x = (W - total) / 2
    draw_mark(img, x, H / 2 - 12 * s, s)
    d.text((x + mark_w + gap, H / 2), "Hosted Mirror", font=title, fill=WHITE, anchor="lm")
    sub = font(40, "Regular")
    d.text((W / 2, H / 2 + 130), "AirPlay para tu Fire TV", font=sub, fill=(142, 142, 147), anchor="mm")
    for folder, size in (("drawable-xhdpi", (320, 180)), ("drawable-xxhdpi", (480, 270)), ("drawable-xxxhdpi", (640, 360))):
        out = RES / folder
        out.mkdir(parents=True, exist_ok=True)
        img.resize(size, Image.LANCZOS).save(out / "banner.png", optimize=True)


def icon():
    N = 1024
    img = gradient(N, N)
    mask = Image.new("L", (N, N), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, N - 1, N - 1], radius=int(N * 0.22), fill=255)
    d = ImageDraw.Draw(img)
    s = N / 24 * 0.62
    ox = (N - 24 * s) / 2 - 0.5 * s
    oy = (N - 24 * s) / 2
    draw_mark(img, ox, oy, s)
    rgba = img.convert("RGBA")
    rgba.putalpha(mask)
    for folder, size in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                         ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        out = RES / folder
        out.mkdir(parents=True, exist_ok=True)
        rgba.resize((size, size), Image.LANCZOS).save(out / "ic_launcher.png", optimize=True)
    store = ROOT / "store"
    store.mkdir(exist_ok=True)
    rgba.resize((512, 512), Image.LANCZOS).save(store / "icon-512.png", optimize=True)


if __name__ == "__main__":
    banner()
    icon()
    print("ícono y banner generados")
