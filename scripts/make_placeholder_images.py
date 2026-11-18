"""Generates V5__product_images.sql: the product_image table plus one sample picture (SVG) per seeded product.

The sample pictures are simple drawings in the product's first colour, marked "Sample picture",
so every screen has an image until the Owner uploads real photos from the Product screen.

Run from the csms folder:  python3 scripts/make_placeholder_images.py
"""
import re
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "src/main/resources/db/migration/V2__seed_data.sql"
OUT = ROOT / "src/main/resources/db/migration/V5__product_images.sql"

COLOURS = {
    "blue": "#2f5fb3", "navy": "#1f2f5c", "sky blue": "#6fb3e6", "pink": "#e77fa8", "red": "#c8323c",
    "maroon": "#7a1f33", "wine": "#6b1d3a", "magenta": "#b5267a", "white": "#f4f1ea", "cream": "#efe3c6",
    "beige": "#d9c3a0", "black": "#2b2b2b", "grey": "#8a8f98", "charcoal": "#3d4148", "green": "#2f8a4e",
    "olive": "#6b7a34", "teal": "#1f8a8a", "mustard": "#d4a12a", "yellow": "#e9c63b", "orange": "#e07b2e",
    "peach": "#f2a585", "lavender": "#a993d6", "purple": "#6f3fa0", "brown": "#7a5236", "gold": "#c9a33e",
    "indigo": "#34407a", "khaki": "#b8a77a", "rust": "#b0532a", "mint": "#9fd8bf", "silver": "#b9bec6",
}

# Simple garment outlines on a 400 x 400 canvas (drawn in the product colour).
SHAPES = {
    "tunic": "M150 70 L120 85 L70 150 L100 170 L130 140 L125 330 L275 330 L270 140 L300 170 L330 150 L280 85 L250 70 Q200 110 150 70 Z",
    "shirt": "M155 70 L95 100 L60 175 L100 190 L125 150 L125 330 L275 330 L275 150 L300 190 L340 175 L305 100 L245 70 L200 120 Z",
    "pants": "M135 70 L265 70 L285 330 L225 330 L200 160 L175 330 L115 330 Z",
    "flare": "M165 70 L235 70 L245 150 L320 330 L80 330 L155 150 Z",
    "drape": "M110 80 Q200 40 290 80 L300 120 Q230 100 210 140 L300 330 L100 330 L150 180 Q120 140 110 80 Z",
    "small": "M140 120 L260 120 L260 200 Q260 300 200 300 Q140 300 140 200 Z",
}


def shape_for(name):
    n = name.lower()
    if any(k in n for k in ("saree", "dupatta", "shawl", "stole", "suit material", "handkerchief")):
        return "drape"
    if any(k in n for k in ("lehenga", "frock", "petticoat")):
        return "flare"
    if any(k in n for k in ("jeans", "trouser", "leggings", "palazzo", "track pants", "boxer")):
        return "pants"
    if any(k in n for k in ("kurti", "kurta", "anarkali", "top", "nightsuit", "blouse", "suit set", "sherwani")):
        return "tunic"
    if any(k in n for k in ("socks", "romper")):
        return "small"
    return "shirt"


def lighten(hex_colour, f=0.82):
    r, g, b = (int(hex_colour[i:i + 2], 16) for i in (1, 3, 5))
    return "#%02x%02x%02x" % tuple(int(c + (255 - c) * f) for c in (r, g, b))


def wrap(text, width=22):
    words, lines, line = text.split(), [], ""
    for w in words:
        if len(line) + len(w) + 1 > width and line:
            lines.append(line)
            line = w
        else:
            line = (line + " " + w).strip()
    lines.append(line)
    return lines[:2]


def svg(name, brand, colour_name):
    fill = COLOURS.get(colour_name.lower(), "#8a1c40")
    bg = lighten(fill)
    stroke = "#00000055"
    title = wrap(name)
    text = "".join(
        f'<text x="200" y="{352 + i * 22}" text-anchor="middle" font-family="Arial, sans-serif" '
        f'font-size="19" font-weight="bold" fill="#1f2328">{escape(t)}</text>' for i, t in enumerate(title))
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 400" width="400" height="400">'
        f'<rect width="400" height="400" fill="{bg}"/>'
        f'<path d="{SHAPES[shape_for(name)]}" transform="translate(40 10) scale(0.8)" fill="{fill}" '
        f'stroke="{stroke}" stroke-width="4" stroke-linejoin="round"/>'
        '<rect x="0" y="322" width="400" height="78" fill="#ffffffd9"/>'
        f'{text}'
        f'<text x="12" y="24" font-family="Arial, sans-serif" font-size="13" fill="#1f2328aa">{escape(brand or "")}</text>'
        '<text x="388" y="24" text-anchor="end" font-family="Arial, sans-serif" font-size="12" '
        'fill="#1f2328aa">Sample picture</text>'
        '</svg>'
    )


def main():
    seed = SEED.read_text(encoding="utf-8")
    products = re.findall(r"\((\d+), \d+, '([^']+)', '([^']*)', '\d+', '\w+', '\w+', (?:TRUE|FALSE)\)", seed)
    variants = re.findall(r"\(\d+, (\d+), '[^']+', '\d+', '[^']+', '([^']+)',", seed)
    first_colour = {}
    for pid, colour in variants:
        first_colour.setdefault(pid, colour)
    if len(products) != 50:
        raise SystemExit(f"Expected 50 products in V2, found {len(products)}")

    rows = []
    for pid, name, brand in products:
        body = svg(name, brand, first_colour.get(pid, "")).replace("'", "''")
        rows.append(f"  ({pid}, 'image/svg+xml', convert_to('{body}', 'UTF8'), TRUE)")

    OUT.write_text(
        "-- V5__product_images.sql — product photos stored in the database, so every computer sees the same picture.\n"
        "-- One picture per product. The Owner uploads real photos (JPG/PNG/WebP, max 2 MB, ERR-PRD-006) on the\n"
        "-- Product screen; until then each seeded product has a generated sample picture (is_placeholder = TRUE).\n"
        "-- Generated by scripts/make_placeholder_images.py — edit that script, not the INSERT below.\n\n"
        "CREATE TABLE product_image (\n"
        "  product_id     BIGINT PRIMARY KEY REFERENCES product(product_id),\n"
        "  content_type   VARCHAR(30) NOT NULL\n"
        "                 CONSTRAINT chk_img_type CHECK (content_type IN ('image/jpeg','image/png','image/webp','image/svg+xml')),\n"
        "  data           BYTEA NOT NULL,\n"
        "  is_placeholder BOOLEAN NOT NULL DEFAULT FALSE,\n"
        "  updated_by     BIGINT NULL REFERENCES app_user(user_id),\n"
        "  updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
        "  CONSTRAINT chk_img_size CHECK (octet_length(data) BETWEEN 1 AND 2097152)\n"
        ");\n\n"
        "INSERT INTO product_image (product_id, content_type, data, is_placeholder) VALUES\n"
        + ",\n".join(rows) + ";\n",
        encoding="utf-8")
    print(f"Wrote {OUT.relative_to(ROOT)} with {len(rows)} sample pictures")


if __name__ == "__main__":
    main()
