#!/usr/bin/env python3
"""
memeapp mascot generator — builds the 9 v1 assets from
docs/memeapp-meme-asset-brief.md as hand-composed SVG, one shared
construction (body/eyes/brows/mouth/arms + per-pose props), so the
character stays consistent across poses. No external assets, no
copyrighted references — original geometric construction only.

Palette matches the Figma "core" variable collection exactly:
  ink    #0D0D0D
  paper  #FFFFFF
  yellow #FFE600
  red    #FF2E00
  win    #16DB65
  gray   #6B6B6B
"""

INK = "#0D0D0D"
PAPER = "#FFFFFF"
YELLOW = "#FFE600"
RED = "#FF2E00"
WIN = "#16DB65"
GRAY = "#6B6B6B"

W, H = 960, 640
CX, CY = 480, 340          # body center
BODY_W, BODY_H = 400, 380
BODY_RX = 130
OUTLINE = 24

def body(fill=YELLOW):
    x, y = CX - BODY_W / 2, CY - BODY_H / 2
    return f'<rect x="{x}" y="{y}" width="{BODY_W}" height="{BODY_H}" rx="{BODY_RX}" ry="{BODY_RX}" fill="{fill}" stroke="{INK}" stroke-width="{OUTLINE}" stroke-linejoin="round"/>'

def eye(cx, cy, pupil_dx=0, pupil_dy=0, r=34, closed=False, wide=False):
    if closed:
        return f'<path d="M {cx-30} {cy} Q {cx} {cy+18} {cx+30} {cy}" fill="none" stroke="{INK}" stroke-width="12" stroke-linecap="round"/>'
    pr = 15 if not wide else 12
    er = r if not wide else r + 8
    return (f'<circle cx="{cx}" cy="{cy}" r="{er}" fill="{PAPER}" stroke="{INK}" stroke-width="10"/>'
            f'<circle cx="{cx+pupil_dx}" cy="{cy+pupil_dy}" r="{pr}" fill="{INK}"/>')

def brow(cx, cy, angle=0, length=70):
    return f'<rect x="{cx-length/2}" y="{cy-9}" width="{length}" height="18" rx="9" fill="{INK}" transform="rotate({angle} {cx} {cy})"/>'

def mouth_flat(cx, cy, w=90):
    return f'<line x1="{cx-w/2}" y1="{cy}" x2="{cx+w/2}" y2="{cy}" stroke="{INK}" stroke-width="14" stroke-linecap="round"/>'

def mouth_o(cx, cy, r=20):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{INK}"/>'

def mouth_smile(cx, cy, w=110, depth=26):
    return f'<path d="M {cx-w/2} {cy} Q {cx} {cy+depth} {cx+w/2} {cy}" fill="none" stroke="{INK}" stroke-width="14" stroke-linecap="round"/>'

def mouth_content(cx, cy, w=70, depth=12):
    return f'<path d="M {cx-w/2} {cy} Q {cx} {cy+depth} {cx+w/2} {cy}" fill="none" stroke="{INK}" stroke-width="12" stroke-linecap="round"/>'

def arm(cx, cy, angle, length=150, width=64, fill=YELLOW):
    x, y = cx - width / 2, cy - length + width / 2
    return (f'<g transform="rotate({angle} {cx} {cy})">'
            f'<rect x="{x}" y="{y}" width="{width}" height="{length}" rx="{width/2}" '
            f'fill="{fill}" stroke="{INK}" stroke-width="16"/></g>')

def thumb(cx, cy, angle=0):
    return f'<g transform="rotate({angle} {cx} {cy})"><rect x="{cx-16}" y="{cy-46}" width="32" height="60" rx="16" fill="{YELLOW}" stroke="{INK}" stroke-width="12"/></g>'

def svg_wrap(*elements):
    body_els = "\n  ".join(elements)
    return f'''<svg width="{W}" height="{H}" viewBox="0 0 {W} {H}" xmlns="http://www.w3.org/2000/svg">
  {body_els}
</svg>'''

EX, EYL, EYR = 0, CX - 78, CX + 78          # eye row x positions
EYE_Y = CY - 70
BROW_Y = EYE_Y - 52
MOUTH_Y = CY + 40

ASSETS = {}

# 1. side_eye — 3/4 turn, eyes cut sideways, flat mouth, one brow up
ASSETS["side_eye"] = svg_wrap(
    arm(CX - 195, CY + 70, 18),
    arm(CX + 195, CY + 70, -8),
    body(),
    eye(EYL, EYE_Y, pupil_dx=14, pupil_dy=2),
    eye(EYR, EYE_Y, pupil_dx=14, pupil_dy=2),
    brow(EYL, BROW_Y, angle=-8),
    brow(EYR, BROW_Y, angle=-22),
    mouth_flat(CX, MOUTH_Y, w=80),
)

# 2. unimpressed_cat — face-on, half-lidded, arms crossed, flat mouth
ASSETS["unimpressed_cat"] = svg_wrap(
    body(),
    arm(CX - 130, CY + 150, 60, length=190),
    arm(CX + 130, CY + 150, -60, length=190),
    f'<rect x="{EYL-40}" y="{EYE_Y-10}" width="80" height="20" rx="8" fill="{INK}"/>',
    f'<rect x="{EYR-40}" y="{EYE_Y-10}" width="80" height="20" rx="8" fill="{INK}"/>',
    brow(EYL, BROW_Y, angle=0, length=60),
    brow(EYR, BROW_Y, angle=0, length=60),
    mouth_flat(CX, MOUTH_Y, w=100),
)

# 3. confused_math — tilted head, pointing at a hand-drawn "?", furrowed brow
def question_mark(cx, cy, scale=1.0):
    s = scale
    return (f'<g transform="translate({cx} {cy}) scale({s})">'
            f'<path d="M -26 -34 Q -26 -64 4 -64 Q 34 -64 34 -34 Q 34 -12 10 -4 Q -6 2 -6 22" '
            f'fill="none" stroke="{INK}" stroke-width="18" stroke-linecap="round" stroke-linejoin="round"/>'
            f'<circle cx="-6" cy="52" r="12" fill="{INK}"/></g>')

ASSETS["confused_math"] = svg_wrap(
    f'<g transform="rotate(-7 {CX} {CY})">',
    body(),
    arm(CX - 190, CY + 90, 35),
    eye(EYL, EYE_Y, pupil_dx=-6, pupil_dy=-4),
    eye(EYR, EYE_Y, pupil_dx=-6, pupil_dy=-4),
    brow(EYL, BROW_Y, angle=18),
    brow(EYR, BROW_Y, angle=-18),
    mouth_content(CX, MOUTH_Y, w=50, depth=-8),
    '</g>',
    arm(CX + 190, CY + 30, -70, length=180),
    question_mark(CX + 250, CY - 130, scale=1.15),
)

# 4. philosoraptor — thinker pose, chin on fist, arched brow
ASSETS["philosoraptor"] = svg_wrap(
    body(),
    arm(CX - 195, CY + 70, 20),
    arm(CX + 150, CY + 10, -140, length=190),
    f'<circle cx="{CX+150}" cy="{CY-56}" r="42" fill="{YELLOW}" stroke="{INK}" stroke-width="16"/>',
    eye(EYL, EYE_Y, pupil_dx=0, pupil_dy=-8),
    eye(EYR, EYE_Y, pupil_dx=0, pupil_dy=-8),
    brow(EYL, BROW_Y, angle=-4, length=60),
    brow(EYR, BROW_Y - 16, angle=-28, length=64),
    mouth_content(CX, MOUTH_Y, w=44, depth=6),
)

# 5. surprised — wide eyes, small o mouth, hands raised
ASSETS["surprised"] = svg_wrap(
    body(),
    arm(CX - 195, CY - 60, 130, length=180),
    arm(CX + 195, CY - 60, -130, length=180),
    eye(EYL, EYE_Y, pupil_dx=0, pupil_dy=0, wide=True),
    eye(EYR, EYE_Y, pupil_dx=0, pupil_dy=0, wide=True),
    brow(EYL, BROW_Y - 10, angle=-4, length=54),
    brow(EYR, BROW_Y - 10, angle=4, length=54),
    mouth_o(CX, MOUTH_Y + 10),
)

# 6. stonks — deadpan confident stare, thumbs up, tiny tie, up-arrow
ASSETS["stonks"] = svg_wrap(
    body(),
    f'<path d="M {CX-26} {CY-190} L {CX+26} {CY-190} L {CX} {CY-140} Z" fill="{INK}"/>',
    f'<rect x="{CX-14}" y="{CY-142}" width="28" height="70" fill="{INK}"/>',
    eye(EYL, EYE_Y, pupil_dx=0, pupil_dy=0),
    eye(EYR, EYE_Y, pupil_dx=0, pupil_dy=0),
    brow(EYL, BROW_Y, angle=0, length=56),
    brow(EYR, BROW_Y, angle=0, length=56),
    mouth_flat(CX, MOUTH_Y, w=60),
    arm(CX - 195, CY + 60, 30),
    arm(CX + 195, CY + 30, -95, length=170),
    thumb(CX + 300, CY - 40, angle=-95),
    f'<path d="M {CX+240} {CY-150} L {CX+300} {CY-210} M {CX+300} {CY-210} L {CX+270} {CY-208} '
    f'M {CX+300} {CY-210} L {CX+296} {CY-182}" stroke="{WIN}" stroke-width="14" '
    f'stroke-linecap="round" stroke-linejoin="round" fill="none"/>',
)

# 7. this_is_fine — seated, mug, closed content smile, small flame accents
ASSETS["this_is_fine"] = svg_wrap(
    body(),
    f'<path d="M {CX-320} {CY+150} Q {CX-300} {CY+70} {CX-330} {CY+10}" fill="none" stroke="{RED}" stroke-width="16" stroke-linecap="round"/>',
    f'<path d="M {CX+320} {CY+150} Q {CX+340} {CY+80} {CX+312} {CY+20}" fill="none" stroke="{RED}" stroke-width="16" stroke-linecap="round"/>',
    eye(EYL, EYE_Y, closed=True),
    eye(EYR, EYE_Y, closed=True),
    brow(EYL, BROW_Y, angle=0, length=54),
    brow(EYR, BROW_Y, angle=0, length=54),
    mouth_content(CX, MOUTH_Y, w=76, depth=14),
    arm(CX - 150, CY + 40, 60, length=150),
    f'<rect x="{CX-56}" y="{CY+140}" width="80" height="60" rx="10" fill="{PAPER}" stroke="{INK}" stroke-width="14"/>',
    f'<path d="M {CX+24} {CY+150} q 30 0 30 25 q 0 25 -30 25" fill="none" stroke="{INK}" stroke-width="12"/>',
)

# 8. waiting — slouched, arms loosely crossed, gaze off-frame, tired
ASSETS["waiting"] = svg_wrap(
    f'<g transform="translate(0 26) rotate(4 {CX} {CY})">',
    body(),
    arm(CX - 120, CY + 160, 65, length=200),
    arm(CX + 120, CY + 160, -65, length=200),
    eye(EYL, EYE_Y, closed=True),
    eye(EYR, EYE_Y, pupil_dx=20, pupil_dy=4),
    brow(EYL, BROW_Y, angle=0, length=50),
    brow(EYR, BROW_Y, angle=-4, length=50),
    mouth_flat(CX, MOUTH_Y, w=70),
    '</g>',
)

# 9. win_default — success card: single fist-pump clear of the face, big grin
ASSETS["win_default"] = svg_wrap(
    body(),
    arm(CX - 170, CY - 30, -35, length=220),
    arm(CX + 150, CY + 160, -55, length=150),
    eye(EYL, EYE_Y, closed=True),
    eye(EYR, EYE_Y, closed=True),
    brow(EYL, BROW_Y - 6, angle=-10, length=56),
    brow(EYR, BROW_Y - 6, angle=10, length=56),
    mouth_smile(CX, MOUTH_Y, w=130, depth=34),
    f'<path d="M {CX-260} {CY-220} l 14 34 l 36 4 l -28 24 l 8 36 l -30 -20 l -30 20 l 8 -36 '
    f'l -28 -24 l 36 -4 Z" fill="{WIN}" stroke="{INK}" stroke-width="10" stroke-linejoin="round"/>',
)

if __name__ == "__main__":
    import pathlib
    out = pathlib.Path(__file__).parent
    for name, svg in ASSETS.items():
        (out / f"{name}.svg").write_text(svg)
    print(f"wrote {len(ASSETS)} svgs to {out}")
