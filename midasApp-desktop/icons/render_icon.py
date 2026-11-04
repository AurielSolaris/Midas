"""Rasterizes icons/icon.svg into the app icons: icon.png (window/Linux), icon.ico (Windows), icon.icns (macOS).
Run from midasApp-desktop/: python icons/render_icon.py"""
import io
import cairosvg
from PIL import Image

svg = open("icons/icon.svg", "rb").read()
big = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg, output_width=1024, output_height=1024))).convert("RGBA")
png256 = big.resize((256, 256), Image.LANCZOS)
png256.save("icons/icon.png")
png256.save("src/main/resources/icon.png")
big.save("icons/icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
big.save("icons/icon.icns")
big.resize((512, 512), Image.LANCZOS).save("icons/icon-512.png")
print("icons written")
