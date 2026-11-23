#!/usr/bin/env python3
"""Make the 320x180dp TV launcher banner (640x360 px, xhdpi) from the Anne Bonny icon."""
import subprocess
from PIL import Image, ImageDraw, ImageFont

W, H = 640, 360
BG = (10, 10, 14)
ACCENT = (224, 68, 60)
GOLD = (217, 165, 74)

art = Image.open("anne_bonny_icon.png").convert("RGB").resize((H + 40, H + 40), Image.LANCZOS)
art = art.crop((20, 10, 20 + H, 10 + H))  # centre on the face and hat

banner = Image.new("RGB", (W, H), BG)
banner.paste(art, (W - H, 0))

# Fade the portrait's left edge into the background.
fade = Image.new("L", (H, H))
for x in range(H):
    a = min(255, int(255 * (x / (H * 0.55)) ** 1.4))
    for y in range(H):
        fade.putpixel((x, y), a)
banner.paste(Image.new("RGB", (H, H), BG), (W - H, 0), Image.eval(fade, lambda v: 255 - v))

def font(style, size):
    path = subprocess.check_output(["fc-match", "-f", "%{file}", f"Noto Sans:style={style}"]).decode()
    return ImageFont.truetype(path, size)

d = ImageDraw.Draw(banner)
d.text((44, 92), "Ani", font=font("Black", 150), fill=(243, 239, 233))
d.rectangle((50, 280, 130, 286), fill=ACCENT)
d.text((50, 296), "ANIME ON DECK", font=font("Bold", 22), fill=GOLD)

banner.save("app/src/main/res/drawable-xhdpi/tv_banner.png", optimize=True)
print("saved")
