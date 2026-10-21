"""Imágenes para la ficha de Amazon Appstore y el banner del launcher (requiere Pillow).

Uso: python3 scripts/make-store-art.py
Entrada: store/brand/fondo-b.png (foto de fondo) y store/icon-512.png (de make-art.py).
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
OUT = ROOT / "store" / "amazon"
PHOTO = ROOT / "store" / "brand" / "fondo-b.png"
LOGO = ROOT / "store" / "brand" / "hosted-logo-footer.png"
ICON = ROOT / "store" / "brand" / "icono-hosted-mirror-1254.png"
FONT = "/System/Library/Fonts/SFNS.ttf"
WHITE = (245, 245, 247)
GRAY = (160, 160, 166)
NAME = "Hosted Mirror Play"


def font(size, weight="Semibold"):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


def cover(img, w, h):
    """Recorta y escala como CSS background-size: cover."""
    scale = max(w / img.width, h / img.height)
    img = img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)
    x = (img.width - w) // 2
    y = (img.height - h) // 2
    return img.crop((x, y, x + w, y + h))


def scrim(img, strength=0.82):
    """Oscurece de izquierda a derecha y desde abajo, como en la app."""
    w, h = img.size
    mask = Image.new("L", (w, h))
    px = mask.load()
    for y in range(h):
        for x in range(w):
            left = max(0.0, 1.0 - x / (w * 0.75))
            bottom = max(0.0, (y / h - 0.55) / 0.45)
            px[x, y] = round(255 * min(1.0, 0.35 + strength * max(left, bottom)))
    dark = Image.new("RGB", (w, h), (6, 6, 7))
    return Image.composite(dark, img, mask)


def draw_mark(img, ox, oy, s):
    def r(x0, y0, x1, y1):
        return [ox + x0 * s, oy + y0 * s, ox + x1 * s, oy + y1 * s]

    alpha = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(alpha)
    d.rounded_rectangle(r(2.5, 4.25, 18.5, 16.25), radius=2 * s, outline=255, width=max(1, round(1.6 * s)))
    d.rectangle(r(7, 18.5, 11.75, 20), fill=255)
    d.rounded_rectangle(r(13.0, 8.0, 23.0, 22.0), radius=2.4 * s, fill=0)
    d.rounded_rectangle(r(14.25, 9.25, 21.75, 20.75), radius=1.5 * s, fill=255)
    img.paste(Image.new("RGB", img.size, WHITE), (0, 0), alpha)


def paste_logo(img, height, margin):
    logo = Image.open(LOGO).convert("RGBA")
    logo = logo.crop(logo.getbbox())
    logo = logo.resize((round(logo.width * height / logo.height), height), Image.LANCZOS)
    img.paste(logo, (img.width - logo.width - margin, margin), logo)


def tile(w, h, subtitle=True, logo=True):
    """Arte con foto + marca + nombre (ícono de Fire TV, banner, promo)."""
    img = scrim(cover(Image.open(PHOTO).convert("RGB"), w, h), 0.9)
    d = ImageDraw.Draw(img)
    s = h / 80
    x = round(w * 0.075)
    text_x = x + 24 * s + h * 0.04
    size = round(h * 0.15)
    title = font(size, "Semibold")
    while d.textlength(NAME, font=title) > w - text_x - x and size > 10:
        size -= 2
        title = font(size, "Semibold")
    cy = h * (0.46 if subtitle else 0.5)
    icon_size = round(26 * s)
    icon = Image.open(ICON).convert("RGBA").resize((icon_size, icon_size), Image.LANCZOS)
    img.paste(icon, (round(x - s), round(cy - icon_size / 2)), icon)
    d.text((text_x, cy), NAME, font=title, fill=WHITE, anchor="lm")
    if subtitle:
        d.text((text_x + 0.4 * s, cy + h * 0.13), "Screen Mirroring · Fire TV", font=font(round(h * 0.06), "Regular"),
               fill=GRAY, anchor="lm")
    if logo:
        paste_logo(img, round(h * 0.16), round(h * 0.06))
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    icon = Image.open(ROOT / "store" / "icon-512.png").convert("RGBA")
    icon.save(OUT / "icon-512x512.png", optimize=True)
    icon.resize((114, 114), Image.LANCZOS).save(OUT / "icon-114x114.png", optimize=True)

    tile(1280, 720).save(OUT / "firetv-icon-1280x720.png", optimize=True)
    tile(1024, 500).save(OUT / "promo-1024x500.png", optimize=True)

    bg = scrim(cover(Image.open(PHOTO).convert("RGB"), 1920, 1080), 0.5)
    bg.save(OUT / "firetv-background-1920x1080.jpg", quality=90)

    banner = tile(1280, 720, subtitle=False, logo=False)
    for folder, size in (("drawable-xhdpi", (320, 180)), ("drawable-xxhdpi", (480, 270)),
                         ("drawable-xxxhdpi", (640, 360))):
        banner.resize(size, Image.LANCZOS).save(RES / folder / "banner.png", optimize=True)
    print("arte de tienda en", OUT)


if __name__ == "__main__":
    main()
