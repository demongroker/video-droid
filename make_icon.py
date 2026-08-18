from PIL import Image, ImageDraw, ImageFont
import os

ROOT = "/home/adnan/video-droid/app/src/main/res"
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
try:
    font_base = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 60)
except Exception:
    font_base = ImageFont.load_default()

for dpi, size in sizes.items():
    d = os.path.join(ROOT, f"mipmap-{dpi}")
    os.makedirs(d, exist_ok=True)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=(63, 81, 181, 255))
    scale = size / 192.0
    font = font_base.font_variant(size=int(120 * scale))
    bbox = draw.textbbox((0, 0), "VD", font=font)
    w = bbox[2] - bbox[0]; h = bbox[3] - bbox[1]
    draw.text(((size - w) / 2 - bbox[0], (size - h) / 2 - bbox[1]), "VD",
              font=font, fill=(255, 255, 255, 255))
    img.save(os.path.join(d, "ic_launcher.png"))
    print("wrote", os.path.join(d, "ic_launcher.png"))
print("icons done")
