"""Draws catalogue-style sample pictures for the 50 seeded products and writes
src/main/resources/db/migration/V7__better_sample_pictures.sql.

No photos are copied from the internet: every picture is drawn here as an SVG illustration
(garment shape, shading, folds, prints, borders, hanger, studio background) in the product's own colour.
V7 only replaces pictures that are still samples (is_placeholder = TRUE), never a real photo the Owner uploaded.

Run from the csms folder:   python3 scripts/make_sample_pictures.py            (writes V7)
                            python3 scripts/make_sample_pictures.py --preview  (also writes preview/*.svg)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "src/main/resources/db/migration/V2__seed_data.sql"
OUT = ROOT / "src/main/resources/db/migration/V7__better_sample_pictures.sql"

GOLD, GOLD_LIGHT = "#c9a33e", "#ecd27a"
COLOURS = {
    "blue": "#3563b5", "pink": "#e889ad", "maroon": "#7c2235", "mustard": "#d6a62c", "white": "#f7f6f2",
    "peach": "#f3b08f", "green": "#2f8a55", "wine": "#6b1d3a", "navy": "#1f2d57", "yellow": "#efc93d",
    "sky blue": "#86bfe8", "red": "#c62f3a", "magenta": "#b3266f", "black": "#2a2a2e", "teal": "#16807d",
    "lavender": "#b59ad8", "beige": "#d8c3a0", "cream": "#efe5cc", "gold": "#c9a33e", "multi": "#e27fa6",
    "grey": "#8c9199", "brown": "#7a5236", "light blue": "#a9cbe9", "red check": "#c0343c",
    "blue check": "#3b67b0", "olive": "#6d7a3a", "dark blue": "#253766", "ivory": "#f3ead6",
    "charcoal": "#3e4249", "assorted": "#3f6fb0", "purple": "#7b4bb0",
}


# ---------------------------------------------------------------- colour helpers
def rgb(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def mix(a, b, t):
    ra, rb = rgb(a), rgb(b)
    return "#%02x%02x%02x" % tuple(round(x + (y - x) * t) for x, y in zip(ra, rb))


def light(c, t):
    return mix(c, "#ffffff", t)


def dark(c, t):
    return mix(c, "#000000", t)


def lum(c):
    r, g, b = rgb(c)
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255


def contrast(c):
    return dark(c, 0.45) if lum(c) > 0.62 else light(c, 0.75)


# ---------------------------------------------------------------- geometry helpers
def smooth(pts, closed=True, t=0.18):
    """Smooth path through points; a point (x, y, 'c') is a sharp corner."""
    P = [(p[0], p[1]) for p in pts]
    C = [len(p) > 2 for p in pts]
    n = len(P)
    d = f"M{P[0][0]:.1f} {P[0][1]:.1f}"
    for i in range(n if closed else n - 1):
        p0, p1, p2, p3 = P[i - 1], P[i], P[(i + 1) % n], P[(i + 2) % n]
        c1 = p1 if C[i] else (p1[0] + (p2[0] - p0[0]) * t, p1[1] + (p2[1] - p0[1]) * t)
        c2 = p2 if C[(i + 1) % n] else (p2[0] - (p3[0] - p1[0]) * t, p2[1] - (p3[1] - p1[1]) * t)
        d += f" C{c1[0]:.1f} {c1[1]:.1f} {c2[0]:.1f} {c2[1]:.1f} {p2[0]:.1f} {p2[1]:.1f}"
    return d + (" Z" if closed else "")


def mirror(half):
    """half = right side from top-centre to bottom-centre (both on x = 300)."""
    left = [(600 - p[0], p[1], *p[2:]) for p in reversed(half[1:-1])]
    return half + left


def mx(x):
    return 600 - x


# ---------------------------------------------------------------- picture builder
class Pic:
    def __init__(self, base):
        self.base = base
        self.defs, self.body, self.n = [], [], 0

    def nid(self, p):
        self.n += 1
        return f"{p}{self.n}"

    def grad(self, c, x1=150, x2=450):
        g = self.nid("g")
        self.defs.append(
            f'<linearGradient id="{g}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="0" x2="{x2}" y2="0">'
            f'<stop offset="0" stop-color="{light(c, .22)}"/><stop offset=".45" stop-color="{c}"/>'
            f'<stop offset="1" stop-color="{dark(c, .26)}"/></linearGradient>')
        return g

    def pattern(self, kind, c, c2=None):
        p = self.nid("p")
        fg = c2 or contrast(c)
        if kind == "floral":
            w, inner = 58, ""
            for cx, cy, s in ((14, 15, 1), (43, 44, .85)):
                petals = "".join(f'<circle cx="{cx + 5.2 * s * dx:.1f}" cy="{cy + 5.2 * s * dy:.1f}" r="{4.3 * s:.1f}"/>'
                                 for dx, dy in ((0, -1), (.95, -.31), (.59, .81), (-.59, .81), (-.95, -.31)))
                inner += (f'<g fill="{fg}" opacity=".85">{petals}</g><circle cx="{cx}" cy="{cy}" r="{2.6 * s:.1f}" fill="{GOLD_LIGHT}"/>'
                          f'<ellipse cx="{cx + 11 * s:.1f}" cy="{cy + 7 * s:.1f}" rx="{4 * s:.1f}" ry="{1.8 * s:.1f}" '
                          f'fill="{mix(fg, "#3d8a52", .6)}" opacity=".7" transform="rotate(30 {cx + 11 * s:.1f} {cy + 7 * s:.1f})"/>')
        elif kind == "multi":
            w = 60
            cols = ["#f4d03f", "#5dade2", "#ffffff", "#e74c3c"]
            inner = ""
            for i, (cx, cy) in enumerate(((12, 14), (42, 18), (24, 44), (52, 50))):
                petals = "".join(f'<circle cx="{cx + 5 * dx:.1f}" cy="{cy + 5 * dy:.1f}" r="4"/>'
                                 for dx, dy in ((0, -1), (.95, -.31), (.59, .81), (-.59, .81), (-.95, -.31)))
                inner += f'<g fill="{cols[i]}" opacity=".85">{petals}</g><circle cx="{cx}" cy="{cy}" r="2.4" fill="#7a3b1d"/>'
        elif kind == "buta":
            w = 40
            drop = 'c4 -5 9 -3 9 3 c0 5 -5 9 -11 11 c3 -3 3 -6 1 -9 c-2 -2 -3 -4 1 -5 z'
            inner = (f'<path d="M10 8 {drop}" fill="{GOLD}" opacity=".9"/>'
                     f'<path d="M30 28 {drop}" fill="{GOLD}" opacity=".9"/>'
                     f'<circle cx="30" cy="8" r="1.6" fill="{GOLD_LIGHT}"/><circle cx="10" cy="28" r="1.6" fill="{GOLD_LIGHT}"/>')
        elif kind == "checks":
            w = 44
            inner = (f'<rect x="0" y="0" width="44" height="9" fill="{fg}" opacity=".45"/>'
                     f'<rect x="0" y="0" width="9" height="44" fill="{fg}" opacity=".45"/>'
                     f'<rect x="0" y="24" width="44" height="2" fill="#ffffff" opacity=".45"/>'
                     f'<rect x="24" y="0" width="2" height="44" fill="#ffffff" opacity=".45"/>')
        elif kind == "smallcheck":
            w = 18
            inner = (f'<rect width="18" height="3" fill="{fg}" opacity=".35"/>'
                     f'<rect width="3" height="18" fill="{fg}" opacity=".35"/>')
        elif kind == "stripes":
            w = 22
            inner = f'<rect x="0" y="0" width="4" height="22" fill="{fg}" opacity=".45"/>'
        elif kind == "hstripes":
            w = 26
            inner = f'<rect x="0" y="0" width="26" height="5" fill="{fg}" opacity=".4"/>'
        elif kind == "dots":
            w = 26
            inner = f'<circle cx="6" cy="6" r="3" fill="{fg}" opacity=".85"/><circle cx="19" cy="19" r="3" fill="{fg}" opacity=".85"/>'
        elif kind == "knit":
            w = 16
            inner = f'<path d="M0 1 L8 12 L16 1" fill="none" stroke="{dark(c, .3)}" stroke-width="2" opacity=".35"/>'
        elif kind == "rib":
            w = 9
            inner = f'<rect x="0" y="0" width="2" height="9" fill="{dark(c, .25)}" opacity=".25"/>'
        elif kind == "twill":
            w = 7
            inner = f'<path d="M0 7 L7 0" stroke="{light(c, .35)}" stroke-width="1.4" opacity=".35"/>'
        elif kind == "chikan":
            w = 46
            flower = lambda x, y: (f'<circle cx="{x}" cy="{y}" r="3.6" fill="none" stroke="#ffffff" stroke-width="1.4"/>'
                                   f'<circle cx="{x}" cy="{y}" r="1" fill="#ffffff"/>')
            inner = (flower(12, 12) + flower(35, 34) +
                     '<path d="M16 15 q8 6 15 15" fill="none" stroke="#ffffff" stroke-width="1" stroke-dasharray="2 3" opacity=".9"/>')
        elif kind == "block":
            w = 34
            inner = (f'<path d="M17 5 L26 17 L17 29 L8 17 Z" fill="none" stroke="{fg}" stroke-width="2" opacity=".55"/>'
                     f'<circle cx="17" cy="17" r="2.5" fill="{fg}" opacity=".6"/><circle cx="0" cy="0" r="2" fill="{fg}" opacity=".5"/>')
        elif kind == "sequin":
            w = 22
            inner = (f'<circle cx="5" cy="6" r="1.8" fill="{GOLD_LIGHT}"/><circle cx="16" cy="15" r="1.5" fill="#ffffff" opacity=".8"/>')
        elif kind == "paisley":
            w = 52
            inner = (f'<path d="M14 10 c7 -8 17 -3 15 6 c-2 8 -10 12 -18 13 c5 -4 6 -9 3 -13 c-2 -3 -3 -5 0 -6 z" fill="none" '
                     f'stroke="{fg}" stroke-width="2" opacity=".45"/>'
                     f'<path d="M40 36 c7 -8 17 -3 15 6 c-2 8 -10 12 -18 13 c5 -4 6 -9 3 -13 c-2 -3 -3 -5 0 -6 z" fill="none" '
                     f'stroke="{fg}" stroke-width="2" opacity=".45"/>')
        elif kind == "linen":
            w = 10
            inner = f'<path d="M0 3 H10 M0 8 H6" stroke="{dark(c, .15)}" stroke-width="1" opacity=".25"/>'
        elif kind == "stars":
            w = 34
            star = lambda x, y: f'<path d="M{x} {y - 4} l1.2 2.8 3 .2 -2.3 2 .8 3 -2.7 -1.6 -2.7 1.6 .8 -3 -2.3 -2 3 -.2 z" fill="{fg}" opacity=".85"/>'
            inner = star(9, 9) + star(26, 26)
        else:
            raise ValueError(kind)
        self.defs.append(f'<pattern id="{p}" patternUnits="userSpaceOnUse" width="{w}" height="{w}">{inner}</pattern>')
        return p

    def garment(self, d, c, pattern=None, c2=None, stroke=True):
        """Draws a filled garment shape with shading and returns its clip id for details."""
        clip = self.nid("k")
        self.defs.append(f'<clipPath id="{clip}"><path d="{d}"/></clipPath>')
        g = self.grad(c)
        s = f' stroke="{dark(c, .4)}" stroke-opacity=".55" stroke-width="2" stroke-linejoin="round"' if stroke else ""
        self.body.append(f'<path d="{d}" fill="url(#{g})" filter="url(#ds)"{s}/>')
        if pattern:
            self.body.append(f'<path d="{d}" fill="url(#{self.pattern(pattern, c, c2)})"/>')
        return clip

    def clipped(self, clip, svg):
        self.body.append(f'<g clip-path="url(#{clip})">{svg}</g>')

    def add(self, svg):
        self.body.append(svg)

    def fold(self, d, c, o=.32, w=3):
        self.body.append(f'<path d="{d}" fill="none" stroke="{dark(c, .45)}" stroke-width="{w}" opacity="{o}" stroke-linecap="round"/>')

    def svg(self, floor_y=548, floor_rx=170):
        bg2 = mix(self.base, "#ece9e3", .86)
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 600" width="600" height="600"><defs>'
                f'<radialGradient id="bg" cx="300" cy="250" r="430" gradientUnits="userSpaceOnUse">'
                f'<stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="{bg2}"/></radialGradient>'
                '<filter id="ds" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="7" stdDeviation="8" '
                'flood-color="#000" flood-opacity=".2"/></filter>'
                '<filter id="blur"><feGaussianBlur stdDeviation="9"/></filter>'
                + "".join(self.defs) + '</defs>'
                '<rect width="600" height="600" fill="url(#bg)"/>'
                f'<ellipse cx="300" cy="{floor_y}" rx="{floor_rx}" ry="16" fill="#000" opacity=".13" filter="url(#blur)"/>'
                + "".join(self.body) + '</svg>')


# ---------------------------------------------------------------- shared parts
def hanger(p):
    p.add('<path d="M300 92 L300 70 C300 56 318 50 326 60 C332 68 326 78 314 80" fill="none" stroke="#9aa0a7" '
          'stroke-width="5" stroke-linecap="round"/>'
          '<path d="M172 122 Q300 70 428 122" fill="none" stroke="#8a5a32" stroke-width="12" stroke-linecap="round"/>'
          '<path d="M180 118 Q300 70 420 118" fill="none" stroke="#c08a58" stroke-width="3" stroke-linecap="round" opacity=".8"/>')


def sleeves(kind):
    return {
        "34": [(426, 134), (464, 250, "c"), (426, 262, "c"), (396, 184, "c")],
        "long": [(428, 136), (474, 322, "c"), (440, 330, "c"), (400, 188, "c")],
        "short": [(424, 132), (452, 198, "c"), (414, 208, "c"), (394, 178, "c")],
        "none": [(372, 118, "c"), (392, 204, "c")],
    }[kind]


def cuff(p, clip, kind, colour, w=12):
    if kind == "none":
        return
    a, b = {"34": ((458, 236), (428, 248)), "long": ((468, 306), (440, 314)), "short": ((446, 184), (416, 194))}[kind]
    seg = lambda a, b: f'<line x1="{a[0]}" y1="{a[1]}" x2="{b[0]}" y2="{b[1]}" stroke="{colour}" stroke-width="{w}"/>'
    p.clipped(clip, seg(a, b) + seg((mx(a[0]), a[1]), (mx(b[0]), b[1])))


def band(p, clip, y1, y2, colour, extra=""):
    p.clipped(clip, f'<rect x="0" y="{y1}" width="600" height="{y2 - y1}" fill="{colour}"/>{extra}')


def zigzag(y, colour, x1=120, x2=480, h=7, step=14, w=2):
    pts = " ".join(f"{x},{y + (h if i % 2 else 0)}" for i, x in enumerate(range(x1, x2 + step, step)))
    return f'<polyline points="{pts}" fill="none" stroke="{colour}" stroke-width="{w}"/>'


def buttons(p, ys, x=300, r=5, fill="#fbfbfb", stroke="#00000055"):
    p.add("".join(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>' for y in ys))


# ---------------------------------------------------------------- garments
def tunic(p, c, *, length=500, flare=118, sleeve="34", neck="round", pattern=None, c2=None, trim=None,
          placket=True, slits=True, hang=True, waist=382):
    if hang:
        hanger(p)
    dip = {"round": (300, 146), "mandarin": (300, 116), "v": (300, 196)}[neck]
    half = [dip, (334, 104, "c"), (378, 114)] + sleeves(sleeve) + \
           [(waist, 280), (300 + flare - 6, length - 90), (300 + flare, length, "c"), (300, length + 8)]
    d = smooth(mirror(half))
    k = p.garment(d, c, pattern, c2)
    if neck == "round":
        p.clipped(k, f'<path d="M334 104 C328 134 314 146 300 146 C286 146 272 134 266 104" fill="none" '
                     f'stroke="{trim or dark(c, .2)}" stroke-width="8"/>')
    elif neck == "v":
        p.clipped(k, f'<path d="M334 104 L300 196 L266 104" fill="none" stroke="{trim or dark(c, .2)}" stroke-width="7"/>')
    else:
        p.add(f'<path d="M266 104 L334 104 L336 122 Q300 130 264 122 Z" fill="{trim or dark(c, .15)}" '
              f'stroke="{dark(c, .45)}" stroke-opacity=".5" stroke-width="1.5"/>')
    if placket:
        top = dip[1] + (6 if neck == "mandarin" else 0)
        p.add(f'<line x1="300" y1="{top}" x2="300" y2="{top + 92}" stroke="{dark(c, .35)}" stroke-width="2.5" opacity=".6"/>')
        buttons(p, [top + 18, top + 44, top + 70], r=4, fill=trim or light(c, .5))
    if trim:
        band(p, k, length - 30, length + 12, trim,
             zigzag(length - 15, dark(trim, .35), 150, 450, 6, 12, 1.6))
        cuff(p, k, sleeve, trim)
    if slits:
        p.fold(f"M{300 + flare - 3} {length} L{300 + flare - 9} {length - 64}", c, .5, 2.5)
        p.fold(f"M{mx(300 + flare - 3)} {length} L{mx(300 + flare - 9)} {length - 64}", c, .5, 2.5)
    p.fold(f"M352 300 C358 360 348 420 362 {length - 24}", c)
    p.fold(f"M248 300 C242 360 252 420 238 {length - 24}", c)
    return k


def bottoms(p, c, *, kind="jeans", top=72, scale=1.0, pattern=None):
    spec = {
        "jeans": (96, (404, 165), (378, 548), (316, 548), 232),
        "slim": (94, (400, 160), (364, 548), (318, 548), 232),
        "trousers": (98, (404, 165), (392, 548), (310, 548), 236),
        "leggings": (80, (384, 150), (352, 548), (314, 548), 220),
        "palazzo": (92, (400, 160), (472, 548), (306, 548), 262),
        "track": (98, (404, 162), (374, 534), (320, 534), 234),
        "pyjama": (74, (378, 360), (366, 568), (312, 568), 470),
        "salwar": (86, (396, 380), (356, 566), (318, 566), 480),
        "shorts": (86, (394, 380), (402, 488), (308, 488), 446),
        "boxer": (112, (420, 210), (430, 404), (312, 404), 330),
    }[kind]
    w, hip, outer, inner, crotch = spec
    half = [(300, top, "c"), (300 + w, top, "c"), hip, (*outer, "c"), (*inner, "c"), (300, crotch, "c")]
    d = smooth(mirror(half))
    pat = pattern or ("twill" if kind in ("jeans", "slim") else None)
    k = p.garment(d, c, pat)
    band(p, k, top, top + 20, dark(c, .12), f'<line x1="0" y1="{top + 20}" x2="600" y2="{top + 20}" stroke="{dark(c, .4)}" stroke-width="1.5" opacity=".5"/>')
    if kind in ("jeans", "slim", "trousers"):
        for x in (330, 382):
            p.add(f'<rect x="{x}" y="{top - 3}" width="7" height="27" rx="2" fill="{dark(c, .1)}" stroke="{dark(c, .4)}" stroke-opacity=".5"/>'
                  f'<rect x="{mx(x) - 7}" y="{top - 3}" width="7" height="27" rx="2" fill="{dark(c, .1)}" stroke="{dark(c, .4)}" stroke-opacity=".5"/>')
        p.fold(f"M300 {top + 22} L300 {top + 112} Q300 {top + 128} 318 {top + 122}", c, .55, 2.5)
        pocket = f"M{300 + w - 2} {top + 44} C{300 + w - 30} {top + 46} {300 + w - 54} {top + 34} {300 + w - 60} {top + 22}"
        p.fold(pocket, c, .55, 2.5)
        p.fold(pocket.replace(f"{300 + w - 2}", f"{mx(300 + w - 2)}").replace(f"{300 + w - 30}", f"{mx(300 + w - 30)}")
               .replace(f"{300 + w - 54}", f"{mx(300 + w - 54)}").replace(f"{300 + w - 60}", f"{mx(300 + w - 60)}"), c, .55, 2.5)
        p.add(f'<circle cx="300" cy="{top + 11}" r="5" fill="#b8b8b8" stroke="#777"/>')
    if kind in ("jeans", "slim"):
        st = 'fill="none" stroke="#d98a3a" stroke-width="2" stroke-dasharray="5 4"'
        p.clipped(k, f'<path d="M308 {top + 24} L308 {top + 104} Q308 {top + 118} 322 {top + 114}" {st}/>'
                     f'<line x1="0" y1="{top + 25}" x2="600" y2="{top + 25}" {st}/>'
                     f'<line x1="0" y1="{outer[1] - 14}" x2="600" y2="{outer[1] - 14}" {st}/>')
        p.add(f'<circle cx="{300 + w - 58}" cy="{top + 26}" r="3.5" fill="#b87333"/><circle cx="{mx(300 + w - 58)}" cy="{top + 26}" r="3.5" fill="#b87333"/>')
    if kind == "trousers":
        p.fold(f"M{300 + w // 2 + 4} {top + 40} L{(outer[0] + inner[0]) // 2} {outer[1] - 4}", c, .4, 2)
        p.fold(f"M{mx(300 + w // 2 + 4)} {top + 40} L{mx((outer[0] + inner[0]) // 2)} {outer[1] - 4}", c, .4, 2)
    if kind == "track":
        for off in (0, 9):
            p.clipped(k, f'<line x1="{300 + w - 6 - off}" y1="{top + 22}" x2="{outer[0] - 6 - off}" y2="{outer[1] - 26}" stroke="#ffffff" stroke-width="4"/>'
                         f'<line x1="{mx(300 + w - 6 - off)}" y1="{top + 22}" x2="{mx(outer[0] - 6 - off)}" y2="{outer[1] - 26}" stroke="#ffffff" stroke-width="4"/>')
        band(p, k, outer[1] - 26, outer[1] + 4, dark(c, .15))
    if kind in ("track", "pyjama", "salwar", "boxer", "leggings", "shorts"):
        p.clipped(k, f'<rect x="0" y="{top}" width="600" height="20" fill="url(#{p.pattern("rib", c)})"/>')
    if kind in ("track", "pyjama"):
        p.add(f'<path d="M294 {top + 16} q-6 30 -14 46 M306 {top + 16} q6 30 14 46" fill="none" stroke="#f5f5f5" stroke-width="3"/>')
    if kind == "palazzo":
        pleats = "".join(f'<path d="M{x} {top + 40} L{x + (x - 300) * .5:.0f} {outer[1] - 6} M{mx(x)} {top + 40} '
                         f'L{mx(x + (x - 300) * .5):.0f} {outer[1] - 6}" stroke="{dark(c, .45)}" stroke-width="2" opacity=".25"/>'
                         for x in (330, 360, 384, 396))
        p.clipped(k, pleats)
    if kind == "salwar":
        folds = "".join(f'<path d="M{x} {top + 30} Q{x + 10} {crotch - 40} {x - 10} {outer[1] - 20} M{mx(x)} {top + 30} '
                        f'Q{mx(x + 10)} {crotch - 40} {mx(x - 10)} {outer[1] - 20}" fill="none" stroke="{dark(c, .45)}" '
                        f'stroke-width="2" opacity=".25"/>' for x in (330, 350, 370))
        p.clipped(k, folds)
        band(p, k, outer[1] - 22, outer[1] + 4, dark(c, .12))
    return k


def shirt(p, c, *, pattern=None, c2=None, pocket=True, hang=True):
    if hang:
        hanger(p)
    half = [(300, 122), (334, 102, "c"), (380, 114)] + sleeves("long") + \
           [(392, 280), (400, 380), (408, 432, "c"), (300, 456)]
    k = p.garment(smooth(mirror(half)), c, pattern, c2)
    p.clipped(k, f'<rect x="292" y="120" width="16" height="340" fill="{light(c, .06)}" stroke="{dark(c, .3)}" stroke-opacity=".45" stroke-width="1.5"/>')
    buttons(p, [154, 198, 242, 286, 330, 374, 416], fill=light(c, .7) if lum(c) < .7 else "#ffffff")
    collar = f"M300 124 L334 98 L354 110 L326 162 Z"
    p.add(f'<path d="M266 102 Q300 88 334 102 L334 112 Q300 100 266 112 Z" fill="{dark(c, .18)}"/>'
          f'<path d="{collar}" fill="{light(c, .08)}" stroke="{dark(c, .4)}" stroke-opacity=".6" stroke-width="1.6"/>'
          f'<path d="M300 124 L266 98 L246 110 L274 162 Z" fill="{light(c, .08)}" stroke="{dark(c, .4)}" stroke-opacity=".6" stroke-width="1.6"/>')
    if pocket:
        p.add(f'<rect x="322" y="196" width="46" height="54" rx="3" fill="none" stroke="{dark(c, .4)}" stroke-opacity=".55" stroke-width="1.8"/>'
              f'<line x1="322" y1="206" x2="368" y2="206" stroke="{dark(c, .4)}" stroke-opacity=".45" stroke-width="1.5"/>')
    cuff(p, k, "long", dark(c, .1), 14)
    p.fold("M350 300 C356 350 350 400 360 440", c)
    p.fold("M250 300 C244 350 250 400 240 440", c)
    return k


def tshirt(p, c, *, polo=False, pattern=None, scale=1.0, dy=0, hang=True):
    if scale != 1.0 or dy:
        p.add(f'<g transform="translate({300 * (1 - scale)} {dy}) scale({scale})">')
    if hang:
        hanger(p)
    half = [(300, 132), (332, 104, "c"), (378, 114)] + sleeves("short") + [(392, 280), (396, 428, "c"), (300, 434)]
    k = p.garment(smooth(mirror(half)), c, pattern)
    if polo:
        p.add(f'<path d="M300 128 L332 100 L346 112 L318 152 Z M300 128 L268 100 L254 112 L282 152 Z" fill="{light(c, .06)}" '
              f'stroke="{dark(c, .4)}" stroke-opacity=".6" stroke-width="1.6"/>'
              f'<rect x="293" y="128" width="14" height="74" fill="{light(c, .05)}" stroke="{dark(c, .35)}" stroke-opacity=".5"/>')
        buttons(p, [150, 180], r=4, fill=light(c, .6))
        cuff(p, k, "short", dark(c, .12), 10)
    else:
        p.clipped(k, f'<path d="M332 104 C326 128 314 132 300 132 C286 132 274 128 268 104" fill="none" stroke="{dark(c, .15)}" stroke-width="12"/>')
    p.clipped(k, f'<line x1="0" y1="414" x2="600" y2="414" stroke="{dark(c, .35)}" stroke-width="1.5" stroke-dasharray="4 3" opacity=".6"/>')
    p.fold("M352 260 C356 320 350 370 358 418", c, .28)
    p.fold("M248 260 C244 320 250 370 242 418", c, .28)
    if scale != 1.0 or dy:
        p.add('</g>')
    return k


def blazer(p, c):
    hanger(p)
    half = [(300, 124), (336, 102, "c"), (384, 116)] + sleeves("long") + [(394, 290), (402, 400), (396, 452, "c"), (300, 462)]
    k = p.garment(smooth(mirror(half)), c, "linen")
    p.add('<path d="M274 104 L326 104 L300 254 Z" fill="#f8f8f6" stroke="#bbb" stroke-width="1"/>'
          f'<path d="M292 122 L308 122 L312 220 L300 238 L288 220 Z" fill="{dark(c, .1) if lum(c) > .3 else "#7b2232"}"/>')
    lapel = "M326 104 L354 114 L340 170 L358 180 L306 266 L300 254 Z"
    p.add(f'<path d="{lapel}" fill="{dark(c, .08)}" stroke="{dark(c, .45)}" stroke-opacity=".6" stroke-width="1.6"/>'
          f'<path d="M274 104 L246 114 L260 170 L242 180 L294 266 L300 254 Z" fill="{dark(c, .08)}" stroke="{dark(c, .45)}" stroke-opacity=".6" stroke-width="1.6"/>')
    buttons(p, [296, 340], x=300, r=7, fill=dark(c, .5), stroke="#00000088")
    p.add(f'<rect x="326" y="352" width="62" height="11" rx="2" fill="{dark(c, .12)}"/><rect x="212" y="352" width="62" height="11" rx="2" fill="{dark(c, .12)}"/>'
          f'<rect x="328" y="212" width="40" height="7" rx="2" fill="{dark(c, .12)}"/>')
    p.fold("M352 280 C358 340 352 400 360 446", c, .25)
    p.fold("M248 280 C242 340 248 400 240 446", c, .25)
    return k


def nehru(p, c):
    hanger(p)
    half = [(300, 116), (330, 104, "c"), (366, 112)] + sleeves("none") + [(394, 300), (398, 432, "c"), (300, 440)]
    k = p.garment(smooth(mirror(half)), c, "linen")
    p.add(f'<path d="M270 104 L330 104 L332 122 Q300 130 268 122 Z" fill="{dark(c, .1)}" stroke="{dark(c, .45)}" stroke-opacity=".5"/>')
    p.clipped(k, f'<line x1="300" y1="120" x2="300" y2="440" stroke="{dark(c, .45)}" stroke-width="2" opacity=".6"/>')
    buttons(p, [150, 190, 230, 270, 310, 350], r=5, fill=GOLD, stroke="#7a5b12")
    p.add(f'<rect x="326" y="330" width="52" height="8" rx="2" fill="{dark(c, .15)}"/><rect x="222" y="330" width="52" height="8" rx="2" fill="{dark(c, .15)}"/>'
          f'<rect x="326" y="190" width="40" height="7" rx="2" fill="{dark(c, .15)}"/>')
    return k


def sherwani(p, c):
    k = tunic(p, c, length=530, flare=112, sleeve="long", neck="mandarin", pattern="buta", trim=GOLD, placket=False, slits=False)
    p.clipped(k, f'<rect x="294" y="118" width="12" height="420" fill="{GOLD}" opacity=".85"/>')
    buttons(p, [150, 186, 222, 258, 294, 330], r=5, fill=GOLD_LIGHT, stroke="#7a5b12")
    return k


def hoodie(p, c):
    hanger(p)
    p.add(f'<path d="M244 112 C232 44 368 44 356 112 Z" fill="{dark(c, .2)}" filter="url(#ds)"/>')
    half = [(300, 128), (334, 104, "c"), (382, 116)] + sleeves("long") + [(394, 290), (398, 426, "c"), (300, 432)]
    k = p.garment(smooth(mirror(half)), c)
    p.add(f'<path d="M246 324 L354 324 L376 398 L224 398 Z" fill="{dark(c, .06)}" stroke="{dark(c, .45)}" stroke-opacity=".5" stroke-width="1.6"/>'
          '<path d="M290 128 L287 206 M310 128 L313 206" stroke="#f2f2f2" stroke-width="4" stroke-linecap="round"/>'
          '<rect x="283" y="204" width="8" height="12" rx="2" fill="#bbb"/><rect x="309" y="204" width="8" height="12" rx="2" fill="#bbb"/>')
    band(p, k, 404, 440, dark(c, .1), f'<rect x="0" y="404" width="600" height="36" fill="url(#{p.pattern("rib", c)})"/>')
    cuff(p, k, "long", dark(c, .12), 16)
    p.fold("M352 260 C358 300 352 340 360 400", c, .25)
    return k


def knitwear(p, c, *, cardigan=False):
    hanger(p)
    dip = (300, 236) if cardigan else (300, 132)
    half = [dip, (332, 104, "c"), (380, 116)] + sleeves("long") + [(392, 290), (396, 430, "c"), (300, 436)]
    k = p.garment(smooth(mirror(half)), c, "knit")
    if cardigan:
        p.clipped(k, f'<path d="M332 104 L300 236 L268 104" fill="none" stroke="{dark(c, .15)}" stroke-width="14"/>'
                     f'<rect x="293" y="236" width="14" height="210" fill="{dark(c, .12)}"/>')
        buttons(p, [262, 302, 342, 382, 420], r=5, fill=light(c, .5))
    else:
        p.clipped(k, f'<path d="M332 104 C326 128 314 132 300 132 C286 132 274 128 268 104" fill="none" stroke="{dark(c, .15)}" stroke-width="14"/>')
    band(p, k, 406, 442, dark(c, .1), f'<rect x="0" y="406" width="600" height="36" fill="url(#{p.pattern("rib", c)})"/>')
    cuff(p, k, "long", dark(c, .12), 18)
    return k


def puffer(p, c):
    hanger(p)
    half = [(300, 118), (332, 98, "c"), (384, 116)] + sleeves("long") + [(398, 290), (402, 436, "c"), (300, 442)]
    k = p.garment(smooth(mirror(half)), c)
    lines = "".join(f'<path d="M0 {y} Q300 {y + 10} 600 {y}" fill="none" stroke="{dark(c, .3)}" stroke-width="3" opacity=".5"/>'
                    f'<path d="M0 {y + 6} Q300 {y + 16} 600 {y + 6}" fill="none" stroke="{light(c, .4)}" stroke-width="3" opacity=".35"/>'
                    for y in range(150, 440, 38))
    p.clipped(k, lines)
    p.add(f'<path d="M266 98 L334 98 L338 124 Q300 132 262 124 Z" fill="{dark(c, .12)}"/>'
          '<line x1="300" y1="122" x2="300" y2="440" stroke="#d9d9d9" stroke-width="5"/>'
          '<line x1="300" y1="122" x2="300" y2="440" stroke="#888" stroke-width="1.5" stroke-dasharray="2 2"/>'
          '<rect x="296" y="128" width="9" height="20" rx="3" fill="#cfcfcf" stroke="#777"/>')
    cuff(p, k, "long", dark(c, .15), 14)
    return k


def vest(p, c):
    hanger(p)
    half = [(300, 178), (338, 96, "c"), (366, 96, "c"), (384, 150), (398, 214, "c"), (400, 300), (396, 440, "c"), (300, 446)]
    k = p.garment(smooth(mirror(half)), c, "rib")
    p.clipped(k, f'<path d="M338 96 C334 150 318 178 300 178 C282 178 266 150 262 96" fill="none" stroke="{dark(c, .1)}" stroke-width="10"/>')
    return k


def blouse(p, c):
    hanger(p)
    half = [(300, 182), (340, 104, "c"), (384, 116), (428, 150), (448, 208, "c"), (412, 218, "c"), (390, 190, "c"),
            (386, 272, "c"), (300, 290)]
    k = p.garment(smooth(mirror(half)), c, "buta")
    p.clipped(k, f'<path d="M340 104 C336 150 320 182 300 182 C280 182 264 150 260 104" fill="none" stroke="{GOLD}" stroke-width="10"/>'
                 f'<path d="M0 280 Q300 304 600 280 L600 320 L0 320 Z" fill="{GOLD}"/>')
    cuff(p, k, "short", GOLD, 12)
    p.fold("M330 200 C340 230 344 250 350 270", c, .3)
    return k


def top(p, c):
    hanger(p)
    half = [(300, 176), (330, 104, "c"), (376, 116), (440, 150), (474, 214, "c"), (420, 220, "c"), (392, 182, "c"),
            (388, 300), (394, 404, "c"), (300, 414)]
    k = p.garment(smooth(mirror(half)), c, "smallcheck" if lum(c) < .85 else None, dark(c, .2))
    p.clipped(k, f'<path d="M330 104 L300 176 L270 104" fill="none" stroke="{dark(c, .15)}" stroke-width="8"/>')
    p.fold("M440 206 q-6 -6 -12 0 q-6 6 -12 0", c, .4, 2)
    p.fold("M352 260 C358 320 350 360 358 398", c, .28)
    return k


def frock(p, c, *, party=False):
    hanger(p)
    for side in (1, -1):
        cx = 300 + side * 76
        p.add(f'<ellipse cx="{cx}" cy="140" rx="34" ry="28" fill="{light(c, .1)}" stroke="{dark(c, .35)}" stroke-opacity=".5" filter="url(#ds)"/>')
    half = [(300, 134), (330, 108, "c"), (360, 116), (372, 160), (360, 232, "c"), (474, 448, "c"), (300, 462)]
    k = p.garment(smooth(mirror(half)), c, "sequin" if party else "floral", "#ffffff")
    for x in (330, 360, 390, 420):
        p.fold(f"M{x - 20} 250 L{x + (x - 300) * .45:.0f} 452", c, .25, 2)
        p.fold(f"M{mx(x - 20)} 250 L{mx(x + (x - 300) * .45):.0f} 452", c, .25, 2)
    band(p, k, 222, 242, dark(c, .2) if not party else GOLD)
    p.clipped(k, f'<path d="M330 108 C326 128 314 134 300 134 C286 134 274 128 270 108" fill="none" stroke="#ffffff" stroke-width="6"/>')
    if party:
        p.add(f'<path d="M190 300 Q300 330 410 300 L488 470 Q300 500 112 470 Z" fill="{light(c, .55)}" opacity=".45"/>')
    bow = dark(c, .3) if not party else GOLD
    p.add(f'<path d="M300 232 C280 214 262 222 266 236 C270 250 286 244 300 232 C314 244 330 250 334 236 C338 222 320 214 300 232 Z" fill="{bow}"/>'
          f'<circle cx="300" cy="232" r="6" fill="{dark(bow, .2)}"/>'
          f'<path d="M296 236 L286 268 M304 236 L314 268" stroke="{bow}" stroke-width="4" stroke-linecap="round"/>')
    return k


def skirt(p, c, *, top, waist, hem, half_w, pattern=None, border=GOLD, heavy=False):
    half = [(300, top, "c"), (300 + waist, top, "c"), (300 + half_w, hem, "c"), (300, hem + 12)]
    k = p.garment(smooth(mirror(half)), c, pattern)
    for i in range(1, 6):
        x = 300 + waist * i / 6
        p.fold(f"M{x:.0f} {top + 14} L{300 + half_w * i / 6:.0f} {hem - 4}", c, .22, 2)
        p.fold(f"M{mx(x):.0f} {top + 14} L{mx(300 + half_w * i / 6):.0f} {hem - 4}", c, .22, 2)
    h = 58 if heavy else 34
    band(p, k, hem - h, hem + 14, border, zigzag(hem - h // 2, dark(border, .35), 60, 540, 8, 14, 2))
    if heavy:
        band(p, k, hem - h - 34, hem - h - 24, border)
    band(p, k, top, top + 14, border)
    return k


def lehenga(p, c, choli_c, *, bridal=False, small=False, dupatta=True):
    if small:
        p.add('<g transform="translate(60 40) scale(.8)">')
    skirt(p, c, top=236, waist=72, hem=540, half_w=216 if bridal else 200, pattern="buta" if bridal else "sequin", heavy=bridal)
    half = [(300, 176), (338, 104, "c"), (382, 116), (424, 148), (440, 198, "c"), (408, 206, "c"), (388, 186, "c"),
            (380, 220, "c"), (300, 228)]
    k = p.garment(smooth(mirror(half)), choli_c, "buta")
    p.clipped(k, f'<path d="M338 104 C334 150 318 176 300 176 C282 176 266 150 262 104" fill="none" stroke="{GOLD}" stroke-width="9"/>')
    cuff(p, k, "short", GOLD, 10)
    if dupatta:
        dc = light(choli_c, .25)
        p.add(f'<path d="M232 110 L276 102 C372 196 470 300 528 470 L478 486 C438 330 340 214 232 110 Z" fill="{dc}" opacity=".45"/>'
              f'<path d="M276 102 C372 196 470 300 528 470" fill="none" stroke="{GOLD}" stroke-width="5" opacity=".9"/>'
              f'<path d="M232 110 C340 214 438 330 478 486" fill="none" stroke="{GOLD}" stroke-width="5" opacity=".9"/>')
    if small:
        p.add('</g>')


def anarkali(p, c, dupatta_c):
    hanger(p)
    half = [(300, 146), (334, 104, "c"), (378, 114)] + sleeves("34") + [(372, 236, "c"), (500, 524, "c"), (300, 538)]
    k = p.garment(smooth(mirror(half)), c, "sequin")
    for i in range(1, 7):
        p.fold(f"M{300 + 72 * i / 7:.0f} 246 L{300 + 200 * i / 7:.0f} 520", c, .22, 2)
        p.fold(f"M{mx(300 + 72 * i / 7):.0f} 246 L{mx(300 + 200 * i / 7):.0f} 520", c, .22, 2)
    band(p, k, 226, 244, GOLD)
    band(p, k, 492, 545, GOLD, zigzag(518, dark(GOLD, .35), 80, 520, 8, 14, 2))
    p.clipped(k, f'<path d="M334 104 C328 134 314 146 300 146 C286 146 272 134 266 104" fill="none" stroke="{GOLD}" stroke-width="10"/>')
    cuff(p, k, "34", GOLD)
    dc = dupatta_c
    p.add(f'<path d="M234 110 L276 102 C370 196 470 300 526 470 L478 486 C438 330 340 214 234 110 Z" fill="{dc}" opacity=".42"/>'
          f'<path d="M276 102 C370 196 470 300 526 470" fill="none" stroke="{GOLD}" stroke-width="5"/>')


def saree(p, c, c2, *, style):
    """A saree draped over a hanger: patterned body, wide side borders and a rich pallu with tassels."""
    pat = {"banarasi": "buta", "georgette": "floral", "cotton": "smallcheck"}[style]
    border = GOLD if style == "banarasi" else c2
    hanger(p)
    p.garment("M214 116 L408 116 C414 230 404 380 420 486 L232 486 C244 380 226 230 214 116 Z", dark(c, .14), pat,
              "#ffffff" if style == "georgette" else None, False)
    front = "M178 112 L386 112 C394 230 378 380 398 520 L168 520 C186 380 172 230 178 112 Z"
    k = p.garment(front, c, pat, "#ffffff" if style == "georgette" else None)
    edge = dark(border, .3) if style == "banarasi" else light(border, .45)
    p.clipped(k, f'<path d="M178 112 C172 230 186 380 168 520" fill="none" stroke="{border}" stroke-width="34"/>'
                 f'<path d="M386 112 C394 230 378 380 398 520" fill="none" stroke="{border}" stroke-width="34"/>'
                 f'<path d="M196 112 C190 230 204 380 186 520" fill="none" stroke="{edge}" stroke-width="2"/>'
                 f'<path d="M368 112 C376 230 360 380 380 520" fill="none" stroke="{edge}" stroke-width="2"/>')
    pallu_pat = p.pattern({"banarasi": "paisley", "georgette": "floral", "cotton": "buta"}[style], border,
                          dark(c, .1) if style == "banarasi" else "#ffffff")
    p.clipped(k, f'<rect x="0" y="414" width="600" height="110" fill="{border}"/>'
                 f'<rect x="0" y="414" width="600" height="110" fill="url(#{pallu_pat})"/>'
                 + zigzag(424, edge, 160, 410, 8, 14, 2.5) + zigzag(500, edge, 160, 410, 8, 14, 2.5))
    for x in (236, 282, 330):
        p.fold(f"M{x} 140 C{x - 6} 260 {x + 8} 360 {x - 4} 410", c, .3)
    for x in range(172, 398, 9):
        p.add(f'<line x1="{x}" y1="520" x2="{x + 1}" y2="546" stroke="{border}" stroke-width="3" stroke-linecap="round"/>'
              f'<circle cx="{x + 1}" cy="548" r="2.5" fill="{dark(border, .2)}"/>')


def suit_material(p, c, c2):
    k1 = p.garment("M150 344 Q150 330 164 330 L436 330 Q450 330 450 344 L450 472 Q450 486 436 486 L164 486 Q150 486 150 472 Z", c, "block", "#ffffff")
    k2 = p.garment("M172 284 Q172 272 184 272 L416 272 Q428 272 428 284 L428 336 L172 336 Z", c2, None)
    k3 = p.garment("M192 230 Q192 218 204 218 L396 218 Q408 218 408 230 L408 280 L192 280 Z", light(c, .35), "floral", c)
    p.clipped(k3, f'<rect x="192" y="266" width="216" height="14" fill="{GOLD}"/>')
    p.add(f'<rect x="372" y="214" width="16" height="276" fill="{GOLD}" opacity=".95"/>'
          f'<path d="M380 216 C356 190 344 206 356 218 C366 228 376 222 380 216 C384 222 394 228 404 218 C416 206 404 190 380 216 Z" fill="{GOLD}"/>')


def drape(p, c, *, kind):
    p.add('<line x1="150" y1="116" x2="450" y2="116" stroke="#8a5a32" stroke-width="12" stroke-linecap="round"/>'
          '<circle cx="150" cy="116" r="10" fill="#6d4524"/><circle cx="450" cy="116" r="10" fill="#6d4524"/>')
    pat = {"dupatta": "multi" if c == COLOURS["multi"] else "floral", "shawl": "paisley", "stole": "stripes"}[kind]
    back = "M230 112 L410 112 L420 470 L240 470 Z"
    p.garment(back, dark(c, .12), pat, None, False)
    front = "M188 112 L372 112 C380 220 368 380 384 500 L176 500 C192 380 180 220 188 112 Z"
    k = p.garment(front, c, pat, "#ffffff" if kind == "dupatta" else None)
    trim = GOLD if kind == "dupatta" else dark(c, .25)
    band(p, k, 462, 504, trim, zigzag(482, light(trim, .45), 176, 384, 6, 12, 2) if kind == "dupatta" else "")
    p.fold("M240 140 C232 260 246 360 236 488", c, .28)
    p.fold("M318 140 C326 260 314 360 326 488", c, .28)
    for x in range(180, 386, 8):
        p.add(f'<line x1="{x}" y1="500" x2="{x + 1}" y2="524" stroke="{trim}" stroke-width="2.5" stroke-linecap="round"/>')


def nightsuit(p, c):
    bottoms(p, c, kind="pyjama", top=330, pattern="dots")
    k = tunic(p, c, length=380, flare=108, sleeve="long", neck="v", pattern="dots", placket=False, slits=False, waist=386)
    p.clipped(k, '<line x1="300" y1="196" x2="300" y2="390" stroke="#ffffff" stroke-width="3"/>')
    buttons(p, [226, 266, 306, 346], r=5, fill="#ffffff")
    p.add('<path d="M334 104 L300 196 L266 104" fill="none" stroke="#ffffff" stroke-width="4"/>'
          f'<rect x="322" y="236" width="40" height="44" rx="3" fill="none" stroke="#ffffff" stroke-width="3"/>')


def romper(p, c):
    hanger(p)
    half = [(300, 148), (334, 118, "c"), (370, 128), (420, 166), (432, 206, "c"), (402, 214, "c"), (380, 196),
            (384, 330), (380, 420, "c"), (338, 432), (318, 452, "c"), (300, 456)]
    k = p.garment(smooth(mirror(half)), c, "stars" if lum(c) > .7 else "dots", "#86bfe8" if lum(c) > .9 else "#ffffff")
    p.clipped(k, f'<path d="M334 118 C328 140 314 148 300 148 C286 148 272 140 266 118" fill="none" stroke="{dark(c, .15)}" stroke-width="9"/>')
    buttons(p, [436], x=286, r=4)
    buttons(p, [436], x=314, r=4)
    buttons(p, [178, 206], r=4)


def socks(p, c1, c2):
    sock = "M0 0 L78 0 L78 186 C78 212 94 224 124 228 L180 234 C212 238 212 290 180 294 L58 294 C18 294 0 264 0 226 Z"
    for i, (col, tx, ty) in enumerate(((c1, 150, 110), (c2, 236, 150))):
        cid = p.nid("s")
        p.defs.append(f'<clipPath id="{cid}"><path d="{sock}"/></clipPath>')
        g = p.grad(col, 0, 210)
        ribs = "".join(f'<line x1="{x}" y1="0" x2="{x}" y2="46" stroke="{dark(col, .3)}" stroke-width="2" opacity=".45"/>' for x in range(8, 78, 9))
        p.add(f'<g transform="translate({tx} {ty})"><path d="{sock}" fill="url(#{g})" filter="url(#ds)" stroke="{dark(col, .4)}" stroke-opacity=".5" stroke-width="2"/>'
              f'<g clip-path="url(#{cid})"><rect x="0" y="0" width="78" height="46" fill="{dark(col, .12)}"/>{ribs}'
              f'<ellipse cx="22" cy="268" rx="44" ry="40" fill="{dark(col, .25)}" opacity=".6"/>'
              f'<ellipse cx="196" cy="264" rx="34" ry="44" fill="{dark(col, .25)}" opacity=".6"/></g></g>')


def hankies(p, c):
    cols = ["#f4f6f9", "#eef2f7", "#ffffff"]
    edge = ["#5b7fb8", "#8a9199", "#c43d4b"]
    for i, ang in enumerate((-12, -2, 9)):
        g = p.grad(cols[i], 190, 410)
        p.add(f'<g transform="rotate({ang} 300 330)" filter="url(#ds)"><rect x="190" y="220" width="220" height="220" rx="4" fill="url(#{g})" stroke="#c9ced6"/>'
              f'<rect x="202" y="232" width="196" height="196" fill="none" stroke="{edge[i]}" stroke-width="4"/>'
              f'<rect x="210" y="240" width="180" height="180" fill="none" stroke="{edge[i]}" stroke-width="1.5"/></g>')
    p.add('<rect x="186" y="304" width="228" height="34" rx="3" fill="#c49a6c" transform="rotate(-2 300 320)"/>'
          '<rect x="186" y="304" width="228" height="34" rx="3" fill="none" stroke="#8a6a44" stroke-width="1.5" transform="rotate(-2 300 320)"/>')


def petticoat(p, c):
    hanger(p)
    k = skirt(p, c, top=124, waist=84, hem=528, half_w=160, border=light(c, .3) if lum(c) < .5 else dark(c, .15))
    scallops = "".join(f'<circle cx="{x}" cy="536" r="9" fill="none" stroke="{light(c, .55) if lum(c) < .5 else dark(c, .2)}" stroke-width="3"/>'
                       for x in range(150, 452, 18))
    p.add(scallops + '<path d="M296 134 q-8 40 -16 64 M304 134 q8 40 16 64" fill="none" stroke="#f0e6d0" stroke-width="3"/>'
          '<circle cx="280" cy="200" r="4" fill="#d4b483"/><circle cx="320" cy="200" r="4" fill="#d4b483"/>')


# ---------------------------------------------------------------- product -> drawing
def draw(pid, name, colours):
    c = COLOURS.get(colours[0].lower(), "#8a1c40")
    c2 = COLOURS.get(colours[1].lower(), light(c, .4)) if len(colours) > 1 else light(c, .4)
    p = Pic(c)
    n = name.lower()
    if "floral cotton kurti" in n:
        tunic(p, c, pattern="floral", c2="#ffffff", trim=light(c, .55))
    elif "rayon a-line" in n:
        tunic(p, c, flare=138, pattern="block", c2=GOLD_LIGHT, trim=GOLD)
    elif "chikankari" in n:
        tunic(p, c, pattern="chikan", sleeve="34", trim=None)
    elif "anarkali" in n:
        anarkali(p, c, light(c2, .2))
    elif "designer party kurta" in n:
        bottoms(p, c, kind="palazzo", top=330)
        tunic(p, c, length=470, pattern="buta", trim=GOLD)
    elif "salwar suit" in n:
        bottoms(p, c2, kind="salwar", top=330)
        tunic(p, c, length=470, pattern="floral", c2="#ffffff", trim=light(c2, .1))
        p.add(f'<path d="M232 110 L276 102 C372 196 470 300 528 470 L478 486 C438 330 340 214 232 110 Z" fill="{c2}" opacity=".45"/>')
    elif "unstitched suit" in n:
        suit_material(p, c, c2)
    elif "banarasi" in n:
        saree(p, c, c2, style="banarasi")
    elif "georgette" in n:
        saree(p, c, c2, style="georgette")
    elif "saree" in n:
        saree(p, c, c2, style="cotton")
    elif "bridal lehenga" in n:
        lehenga(p, c, dark(c, .1), bridal=True)
    elif "ready-to-wear lehenga" in n:
        lehenga(p, c, light(c, .15))
    elif "kids lehenga" in n:
        lehenga(p, c, c2, small=True)
    elif "leggings" in n:
        bottoms(p, c, kind="leggings")
    elif "palazzo" in n:
        bottoms(p, c, kind="palazzo")
    elif "casual top" in n:
        top(p, c)
    elif "blouse" in n:
        blouse(p, c)
    elif "dupatta" in n:
        drape(p, c, kind="dupatta")
    elif "shawl" in n:
        drape(p, c, kind="shawl")
    elif "stole" in n:
        drape(p, c, kind="stole")
    elif "nightsuit" in n:
        nightsuit(p, c)
    elif "cardigan" in n:
        knitwear(p, c, cardigan=True)
    elif "checked" in n:
        shirt(p, c, pattern="checks", c2="#1f2d57" if "red" in colours[0].lower() else "#ffffff")
    elif "linen shirt" in n:
        shirt(p, c, pattern="linen")
    elif "shirt" in n and "t-shirt" not in n:
        shirt(p, c)
    elif "polo" in n:
        tshirt(p, c, polo=True)
    elif "boys t-shirt and shorts" in n:
        bottoms(p, c2, kind="shorts", top=330)
        tshirt(p, c, scale=.82, dy=-24)
    elif "t-shirt" in n:
        tshirt(p, c)
    elif "slim fit jeans" in n:
        bottoms(p, c, kind="slim")
    elif "jeans" in n:
        bottoms(p, c, kind="jeans")
    elif "trousers" in n:
        bottoms(p, c, kind="trousers")
    elif "kurta pyjama" in n or "festive kurta" in n:
        bottoms(p, "#f6f2e8" if lum(c) < .85 else light(c, .4), kind="pyjama", top=330)
        festive = "silk" in n
        tunic(p, c, length=476, flare=104, sleeve="long", neck="mandarin", placket=True,
              pattern="buta" if festive else ("linen" if lum(c) > .8 else None), trim=GOLD if festive else None)
    elif "sherwani" in n:
        sherwani(p, c)
    elif "nehru" in n:
        nehru(p, c)
    elif "blazer" in n:
        blazer(p, c)
    elif "track pants" in n:
        bottoms(p, c, kind="track")
    elif "hoodie" in n:
        hoodie(p, c)
    elif "sweater" in n:
        knitwear(p, c)
    elif "vest" in n:
        vest(p, c)
    elif "boxer" in n:
        bottoms(p, c, kind="boxer", top=150, pattern="checks")
    elif "party frock" in n:
        frock(p, c, party=True)
    elif "frock" in n:
        frock(p, c)
    elif "romper" in n:
        romper(p, c)
    elif "winter jacket" in n:
        puffer(p, c)
    elif "handkerchief" in n:
        hankies(p, c)
    elif "socks" in n:
        socks(p, c, c2)
    elif "petticoat" in n:
        petticoat(p, c)
    else:
        tunic(p, c)
    return p.svg()


def main():
    seed = SEED.read_text(encoding="utf-8")
    products = re.findall(r"\((\d+), \d+, '([^']+)', '[^']*', '\d+', '\w+', '\w+', (?:TRUE|FALSE)\)", seed)
    colours = {}
    for pid, col in re.findall(r"\(\d+, (\d+), '[^']+', '\d+', '[^']+', '([^']+)',", seed):
        colours.setdefault(pid, [])
        if col not in colours[pid]:
            colours[pid].append(col)
    rows, preview = [], "--preview" in sys.argv
    if preview:
        (ROOT / "preview").mkdir(exist_ok=True)
    for pid, name in products:
        svg = draw(pid, name, colours.get(pid, ["Maroon"]))
        if preview:
            (ROOT / "preview" / f"{pid}.svg").write_text(svg, encoding="utf-8")
        rows.append(f"  ({pid}, convert_to('{svg.replace(chr(39), chr(39) * 2)}', 'UTF8'))")
    OUT.write_text(
        "-- V7__better_sample_pictures.sql — catalogue-style sample pictures for the 50 seeded products.\n"
        "-- Drawn by scripts/make_sample_pictures.py (no photos copied from the internet).\n"
        "-- Only pictures that are still samples are replaced; real photos uploaded by the Owner are never touched.\n\n"
        "UPDATE product_image AS pi\n"
        "SET data = v.svg, content_type = 'image/svg+xml', thumb = NULL, updated_at = CURRENT_TIMESTAMP\n"
        "FROM (VALUES\n" + ",\n".join(rows) + "\n) AS v(product_id, svg)\n"
        "WHERE pi.product_id = v.product_id AND pi.is_placeholder = TRUE;\n",
        encoding="utf-8")
    print(f"Wrote {OUT.relative_to(ROOT)} with {len(rows)} pictures")


if __name__ == "__main__":
    main()
