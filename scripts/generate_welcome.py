"""Original geometric welcome artwork (MIT); optional Pillow + FFmpeg regeneration.
Normal builds use the committed JPEG/MP4 files and need neither dependency.
"""
from pathlib import Path
import math
import subprocess
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
W, H = 976, 596

def artwork(warm=False):
    top, bottom = ((32, 46, 66), (139, 113, 91)) if warm else ((18, 44, 51), (73, 129, 122))
    image = Image.new('RGB', (W, H))
    draw = ImageDraw.Draw(image)
    for y in range(H):
        t = y / (H - 1)
        draw.line((0, y, W, y), fill=tuple(round(a+(b-a)*t) for a,b in zip(top,bottom)))
    draw.ellipse((674, 94, 798, 218), fill=(242, 219, 175))
    for base, amp, shift, color in [(388, 65, 0.4, (62,108,106)), (478, 78, 1.5, (37,78,82)), (552, 80, 2.8, (23,54,61))]:
        points = [(x, round(base+amp*math.sin(x/W*math.pi*1.6+shift))) for x in range(W)]
        draw.polygon(points+[(W,H),(0,H)], fill=color)
    return image

if __name__ == '__main__':
    for warm, name in [(False, 'welcome.jpg'), (True, 'welcome-alt.jpg')]:
        artwork(warm).save(ROOT / 'assets' / name, quality=94, optimize=True)
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-loop','1',
        '-i',str(ROOT/'assets/welcome.jpg'),'-vf',
        "scale=1952:1192,zoompan=z='1.03+0.02*sin(on*2*PI/120)':x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d=1:s=976x596:fps=24",
        '-t','5','-c:v','libx264','-pix_fmt','yuv420p','-crf','23','-movflags','+faststart','-an',
        str(ROOT/'assets/welcome.mp4')],check=True)
    print('Generated original welcome artwork and looping video')
