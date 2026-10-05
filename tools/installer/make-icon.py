"""Draws the installer/shortcut icon from the app's own palette.

The widget's card is 2:1, which reads as a smear at 16px, so the icon is the mark the app
would use: the dark rounded surface with the yuan sign in the accent blue.
"""
from PIL import Image, ImageDraw, ImageFont
import os

BG_TOP = (24, 30, 44)
BG_BOTTOM = (15, 19, 30)
ACCENT = (84, 160, 255)
TEXT = (233, 238, 248)
GOOD = (88, 214, 141)

SIZE = 256
CANVAS = 1024  # draw big, then downsample: the .ico gets every size from one render


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def vertical_gradient(size, top, bottom):
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((size, size))


def load_font(px):
    for name in ("msyhbd.ttc", "msyh.ttc", "seguisb.ttf", "arialbd.ttf", "segoeuib.ttf"):
        path = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", name)
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, px)
            except OSError:
                continue
    return ImageFont.load_default()


def main():
    s = CANVAS
    card = vertical_gradient(s, BG_TOP, BG_BOTTOM).convert("RGBA")

    # A 1px-ish border in the app's border colour, then a soft accent glow at the top.
    d = ImageDraw.Draw(card)
    border = round(s * 0.012)
    d.rounded_rectangle([border // 2, border // 2, s - 1 - border // 2, s - 1 - border // 2],
                        radius=round(s * 0.22), outline=(58, 70, 94, 255), width=border)

    glow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-s * 0.25, -s * 0.75, s * 1.25, s * 0.45], fill=(84, 160, 255, 46))
    card = Image.alpha_composite(card, glow)

    # The yuan sign, centred, with a thin white top bar so it reads as ¥ and not Y.
    font = load_font(round(s * 0.62))
    d = ImageDraw.Draw(card)
    text = "¥"
    box = d.textbbox((0, 0), text, font=font)
    x = (s - (box[2] - box[0])) / 2 - box[0]
    y = (s - (box[3] - box[1])) / 2 - box[1] - s * 0.015
    d.text((x, y), text, font=font, fill=TEXT + (255,))

    # A small green dot, the same colour as the idle tariff line.
    r = round(s * 0.055)
    cx, cy = round(s * 0.735), round(s * 0.735)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GOOD + (255,))

    card.putalpha(rounded_mask(s, round(s * 0.22)))
    icon = card.resize((SIZE, SIZE), Image.LANCZOS)

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.ico")
    icon.save(out, format="ICO",
              sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    png = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app-icon-preview.png")
    icon.save(png)
    print("wrote", out)
    print("wrote", png)


if __name__ == "__main__":
    main()
