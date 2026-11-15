"""Renders 1920x1080 mockups of Ambient layouts from real assets: a bundled photo, the app banners
cropped from a TV screenshot (src/clean-dock.png), Inter and the TV's Google Sans.
Run: python3 -I mockups/render.py  ->  mockups/out/*.png"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC, OUT = os.path.join(HERE, "src"), os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)
W, H = 1920, 1080

INTER = os.path.join(SRC, "Inter-Regular.ttf")
GS = os.path.join(SRC, "GoogleSans-Regular.ttf")
GSM = os.path.join(SRC, "GoogleSans-Medium.ttf")
font = lambda path, size: ImageFont.truetype(path, size)


def photo(name="09.jpg"):
    im = Image.open(os.path.join(ROOT, "assets", "wallpapers", name)).convert("RGB")
    s = max(W / im.width, H / im.height)
    im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    l, t = (im.width - W) // 2, (im.height - H) // 2
    return im.crop((l, t, l + W, t + H)).convert("RGBA")


def banners():
    dock = Image.open(os.path.join(SRC, "clean-dock.png")).convert("RGBA")
    out = []
    for i in range(6):
        x = 118 + i * 286
        out.append(dock.crop((x + 6, 853, x + 250, 985)))  # inset past the rounded corners
    return out


def rounded_mask(size, r):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), r, fill=255)
    return m


def gradient(base, top_alpha, bottom_alpha, y0=0, y1=H, color=(0, 0, 0)):
    g = Image.new("RGBA", (W, H), color + (0,))
    d = ImageDraw.Draw(g)
    for y in range(y0, y1):
        a = top_alpha + (bottom_alpha - top_alpha) * (y - y0) / max(1, y1 - y0 - 1)
        d.line((0, y, W, y), fill=color + (int(a),))
    base.alpha_composite(g)


def hgradient(base, left_alpha, right_alpha, x0=0, x1=W):
    g = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    for x in range(x0, x1):
        a = left_alpha + (right_alpha - left_alpha) * (x - x0) / max(1, x1 - x0 - 1)
        d.line((x, 0, x, H), fill=(0, 0, 0, int(a)))
    base.alpha_composite(g)


def outline(base, box, r, color, width):
    """A see-through rounded outline, blended (ImageDraw on RGBA overwrites alpha instead)."""
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(box, r, outline=color, width=width)
    base.alpha_composite(layer)


def glass(base, box, r, tint=(16, 18, 22, 90), rim=(255, 255, 255, 34), blur=28):
    x0, y0, x1, y1 = box
    region = base.crop(box).filter(ImageFilter.GaussianBlur(blur))
    layer = Image.new("RGBA", region.size)
    layer.alpha_composite(region)
    layer.alpha_composite(Image.new("RGBA", region.size, tint))
    hl = Image.new("RGBA", region.size, (0, 0, 0, 0))  # soft light along the top
    d = ImageDraw.Draw(hl)
    for y in range(region.size[1] // 2):
        d.line((0, y, region.size[0], y), fill=(255, 255, 255, int(22 * (1 - y / (region.size[1] / 2)))))
    layer.alpha_composite(hl)
    base.paste(layer, (x0, y0), rounded_mask(region.size, r))
    outline(base, (x0, y0, x1 - 1, y1 - 1), r, rim, 2)


def shadow(base, box, r, blur=22, alpha=120, dy=12):
    x0, y0, x1, y1 = box
    pad = blur * 3
    s = Image.new("RGBA", (x1 - x0 + 2 * pad, y1 - y0 + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(s).rounded_rectangle((pad, pad, pad + x1 - x0, pad + y1 - y0), r, fill=(0, 0, 0, alpha))
    s = s.filter(ImageFilter.GaussianBlur(blur))
    base.alpha_composite(s, (x0 - pad, y0 - pad + dy))


def card(base, img, box, r, focused=False, dim=1.0):
    x0, y0, x1, y1 = box
    im = img.resize((x1 - x0, y1 - y0), Image.LANCZOS)
    if dim < 1:
        im = Image.blend(Image.new("RGBA", im.size, (0, 0, 0, 255)), im, dim)
    base.paste(im, (x0, y0), rounded_mask(im.size, r))
    if focused:
        ImageDraw.Draw(base).rounded_rectangle((x0, y0, x1 - 1, y1 - 1), r, outline=(255, 255, 255, 255), width=5)


def text(base, xy, s, f, fill=(255, 255, 255, 255), anchor="la", shadow_a=110):
    sh = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).text((xy[0], xy[1] + 3), s, font=f, fill=(0, 0, 0, shadow_a), anchor=anchor)
    base.alpha_composite(sh.filter(ImageFilter.GaussianBlur(10)))
    ImageDraw.Draw(base).text(xy, s, font=f, fill=fill, anchor=anchor)


def icon_wifi(d, cx, cy, s, fill):
    for i, rr in enumerate((s * 0.85, s * 0.58, s * 0.31)):
        d.arc((cx - rr, cy - rr, cx + rr, cy + rr), 225, 315, fill=fill, width=max(3, int(s * 0.13)))
    d.ellipse((cx - s * 0.1, cy - s * 0.1, cx + s * 0.1, cy + s * 0.1), fill=fill)


def icon_widgets(d, cx, cy, s, fill):
    q = s * 0.42
    for dx, dy in ((-1, 1), (1, 1), (-1, -1)):
        x, y = cx + dx * q * 0.62, cy + dy * q * 0.62
        d.rounded_rectangle((x - q / 2, y - q / 2, x + q / 2, y + q / 2), 3, fill=fill)
    x, y = cx + q * 0.62, cy - q * 0.62
    d.polygon([(x, y - q * 0.62), (x + q * 0.62, y), (x, y + q * 0.62), (x - q * 0.62, y)], fill=fill)


def icon_gear(d, cx, cy, s, fill, bg):
    import math
    r = s * 0.38
    for k in range(8):
        a = k * math.pi / 4
        d.line((cx, cy, cx + math.cos(a) * r * 1.3, cy + math.sin(a) * r * 1.3), fill=fill, width=int(s * 0.22))
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill)
    d.ellipse((cx - r * 0.42, cy - r * 0.42, cx + r * 0.42, cy + r * 0.42), fill=bg)


def status(base, right, top, with_name=True, style="glass", size=64):
    """Wi-Fi chip with the network name, then Features and Settings, right-aligned."""
    f = font(GSM, 26)
    name_w = int(ImageDraw.Draw(base).textlength("Home Wi-Fi", font=f)) if with_name else 0
    chip_w = (size + name_w + 18) if with_name else size
    x = right - size - 16 - size - 16 - chip_w
    boxes = [(x, top, x + chip_w, top + size), (x + chip_w + 16, top, x + chip_w + 16 + size, top + size),
             (x + chip_w + 32 + size, top, x + chip_w + 32 + 2 * size, top + size)]
    for b in boxes:
        if style == "glass":
            glass(base, b, size // 2, tint=(10, 12, 16, 110), rim=(255, 255, 255, 50), blur=20)
    d = ImageDraw.Draw(base)
    c = (255, 255, 255, 240)
    icon_wifi(d, boxes[0][0] + size // 2 + 2, top + size * 0.62, size * 0.36, c)
    if with_name:
        d.text((boxes[0][0] + size - 4, top + size // 2), "Home Wi-Fi", font=f, fill=c, anchor="lm")
    icon_widgets(d, (boxes[1][0] + boxes[1][2]) / 2, top + size / 2, size * 0.42, c)
    icon_gear(d, (boxes[2][0] + boxes[2][2]) / 2, top + size / 2, size * 0.42, c, (40, 44, 50, 255))


def time_font(size):
    return font(INTER, size)


# ------------------------------------------------------------------ 1: lock screen

def lock_screen(b):
    im = photo()
    gradient(im, 120, 0, 0, 420)
    gradient(im, 0, 150, 640, H)
    text(im, (W // 2, 78), "Sunday 15 November", font(GSM, 32), fill=(255, 255, 255, 225), anchor="mt")
    text(im, (W // 2, 150), "19:37", time_font(200), anchor="mt", shadow_a=90)
    status(im, W - 56, 52, with_name=False, size=60)
    cw, ch, gap, pad = 236, 133, 26, 24
    total = 6 * cw + 5 * gap + 2 * pad
    x0 = (W - total) // 2
    box = (x0, H - 66 - ch - 2 * pad, x0 + total, H - 66)
    glass(im, box, 44)
    for i, img in enumerate(b):
        x = x0 + pad + i * (cw + gap)
        y = box[1] + pad
        if i == 3:
            g = int(cw * 0.06)
            gy = int(ch * 0.06)
            shadow(im, (x - g, y - gy, x + cw + g, y + ch + gy), 22, alpha=150)
            card(im, img, (x - g, y - gy, x + cw + g, y + ch + gy), 22, focused=True)
        else:
            card(im, img, (x, y, x + cw, y + ch), 20, dim=0.88)
    return im


# ------------------------------------------------------------------ 2: shelf

def shelf(b):
    im = photo()
    gradient(im, 90, 0, 0, 260)
    text(im, (W - 64, 56), "19:37", font(GSM, 46), anchor="ra")
    text(im, (W - 64, 112), "Sunday 15 November", font(GS, 24), fill=(255, 255, 255, 210), anchor="ra")
    status(im, W - 64 - 260, 58, with_name=True, size=56)
    top = 640
    glass(im, (-4, top, W + 4, 930), 0, tint=(14, 16, 20, 110), rim=(255, 255, 255, 30), blur=34)
    cw, ch, gap = 268, 151, 34
    x0 = (W - (6 * cw + 5 * gap)) // 2
    y = top + 52
    for i, img in enumerate(b):
        x = x0 + i * (cw + gap)
        if i == 0:
            g, gy = int(cw * 0.07), int(ch * 0.07)
            shadow(im, (x - g, y - gy, x + cw + g, y + ch + gy), 22, alpha=160, dy=16)
            card(im, img, (x - g, y - gy, x + cw + g, y + ch + gy), 22, focused=True)
            text(im, (x + cw // 2, y + ch + gy + 26), "tvo", font(GSM, 28), anchor="mt")
        else:
            card(im, img, (x, y, x + cw, y + ch), 20)
    # The next row peeks in under the shelf.
    sheet = Image.open(os.path.join(SRC, "sheet.png")).convert("RGBA")
    more = [sheet.crop((122, 199, 381, 339)), sheet.crop((417, 206, 652, 332)), sheet.crop((700, 206, 935, 332))]
    row = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for i, img in enumerate(more):
        x = x0 + i * (cw + gap)
        card(row, img, (x, 968, x + cw, 968 + ch), 20)
    row.putalpha(row.getchannel("A").point(lambda a: a * 0.5))
    im.alpha_composite(row)
    return im


# ------------------------------------------------------------------ 3: editorial

def editorial(b):
    im = photo()
    hgradient(im, 170, 0, 0, 1100)
    gradient(im, 0, 140, 700, H)
    text(im, (72, 66), "ÉTRETAT · NORMANDY", font(GSM, 22), fill=(255, 255, 255, 190))
    status(im, W - 64, 52, with_name=True, size=56)
    text(im, (66, 590), "19:37", time_font(250), shadow_a=80)
    text(im, (78, 880), "Sunday 15 November", font(GSM, 36), fill=(255, 255, 255, 230))
    cw, ch, gap, pad = 176, 99, 18, 18
    total = 6 * cw + 5 * gap + 2 * pad
    box = (W - 64 - total, H - 64 - ch - 2 * pad, W - 64, H - 64)
    glass(im, box, 34)
    for i, img in enumerate(b):
        x = box[0] + pad + i * (cw + gap)
        y = box[1] + pad
        if i == 2:
            g, gy = int(cw * 0.07), int(ch * 0.07)
            shadow(im, (x - g, y - gy, x + cw + g, y + ch + gy), 18, alpha=150)
            card(im, img, (x - g, y - gy, x + cw + g, y + ch + gy), 18, focused=True)
        else:
            card(im, img, (x, y, x + cw, y + ch), 16, dim=0.88)
    return im


# ------------------------------------------------------------------ 4: floating cards

def floating(b):
    im = photo()
    gradient(im, 130, 0, 0, 360)
    gradient(im, 0, 170, 620, H)
    text(im, (96, 70), "Sunday 15 November", font(GSM, 30), fill=(255, 255, 255, 220))
    text(im, (88, 110), "19:37", time_font(150), shadow_a=90)
    status(im, W - 72, 62, with_name=True, size=58)
    cw, ch, gap = 252, 142, 40
    x0 = (W - (6 * cw + 5 * gap)) // 2
    y = H - 110 - ch
    for i, img in enumerate(b):
        x = x0 + i * (cw + gap)
        if i == 4:
            g, gy = int(cw * 0.08), int(ch * 0.08)
            shadow(im, (x - g, y - gy, x + cw + g, y + ch + gy), 24, alpha=170, dy=18)
            card(im, img, (x - g, y - gy, x + cw + g, y + ch + gy), 24, focused=True)
        else:
            shadow(im, (x, y, x + cw, y + ch), 22, alpha=110, blur=16, dy=10)
            card(im, img, (x, y, x + cw, y + ch), 22)
    return im


if __name__ == "__main__":
    b = banners()
    for name, fn in (("1-lock-screen", lock_screen), ("2-shelf", shelf),
                     ("3-editorial", editorial), ("4-floating-cards", floating)):
        fn(b).convert("RGB").save(os.path.join(OUT, name + ".png"))
        print("saved", name)


# ------------------------------------------------------------------ the current layout and rebalanced versions
# Geometry measured from the TV (1920x1080): dock panel 86..1834 x 822..1015, cards 256x144 at 118 + i*286.

DOCK = (86, 822, 1834, 1015)
CARD_X0, CARD_STEP, CARD_Y, CW, CH = 118, 286, 848, 256, 144


def app_photo():
    """The photo as the app prepares it: shade on the left for the clock and at the bottom for the dock."""
    im = photo()
    hgradient(im, 140, 0, 0, W // 2)
    gradient(im, 0, 102, int(H * 0.66), H)
    return im


def app_dock(im, focus=3):
    x0, y0, x1, y1 = DOCK
    panel = Image.new("RGBA", (x1 - x0, y1 - y0), (16, 18, 20, 82))
    im.paste(panel, (x0, y0), Image.composite(panel, Image.new("RGBA", panel.size), rounded_mask(panel.size, 40)).getchannel("A"))
    outline(im, (x0, y0, x1 - 1, y1 - 1), 40, (255, 255, 255, 36), 2)
    for i, img in enumerate(banners()):
        x = CARD_X0 + i * CARD_STEP
        if i == focus:
            g, gy = int(CW * 0.05), int(CH * 0.05)
            card(im, img, (x - g, CARD_Y - gy, x + CW + g, CARD_Y + CH + gy), 24, focused=True)
        else:
            card(im, img, (x, CARD_Y, x + CW, CARD_Y + CH), 22, dim=0.85)


def app_status(im, right, top):
    status(im, right, top, with_name=True, size=72)


def clock_block(im, x, y, align="left"):
    """Date above the time, as in the app (Google Sans Medium 34 px, Inter 172 px)."""
    anchor_d, anchor_t = ("la", "la") if align == "left" else ("ma", "ma")
    dx = 8 if align == "left" else 0
    text(im, (x + dx, y), "Sunday 15 November", font(GSM, 34), fill=(255, 255, 255, 220), anchor=anchor_d)
    text(im, (x, y + 52), "19:37", time_font(172), anchor=anchor_t, shadow_a=90)


def layout_current():
    im = app_photo()
    clock_block(im, 120, 72)
    app_status(im, W - 72, 72)
    app_dock(im)
    return im


def layout_aligned():
    """Same places, one grid: left edges on the first card, right edges on the last card, and the
    same 65 px margin at the top as under the dock."""
    im = app_photo()
    clock_block(im, CARD_X0 - 6, 65)
    app_status(im, CARD_X0 + 5 * CARD_STEP + CW, 65)
    app_dock(im)
    return im


def layout_low_clock():
    """The clock moves down to sit on the dock, left-aligned with its first card; the top-left opens
    up for the photo and the weight sits at the bottom with the dock."""
    im = app_photo()
    clock_block(im, CARD_X0 - 6, 560)
    app_status(im, CARD_X0 + 5 * CARD_STEP + CW, 65)
    app_dock(im)
    return im


def layout_centred():
    """One centre line: date and time centred at the top over the centred dock; the buttons stay
    top-right, aligned with the dock's last card."""
    im = app_photo()
    clock_block(im, W // 2, 65, align="centre")
    app_status(im, CARD_X0 + 5 * CARD_STEP + CW, 65)
    app_dock(im)
    return im


def render_relocations():
    for name, fn in (("r0-current", layout_current), ("r1-aligned", layout_aligned),
                     ("r2-clock-on-dock", layout_low_clock), ("r3-centred", layout_centred)):
        fn().convert("RGB").save(os.path.join(OUT, name + ".png"))
        print("saved", name)


# ------------------------------------------------------------------ refinements of "Aligned"
LEFT = CARD_X0                      # first card's left edge
RIGHT = CARD_X0 + 5 * CARD_STEP + CW  # last card's right edge
MARGIN = H - DOCK[3]                # 65: under the dock, mirrored at the top
BTN = 72


def cap_top(f, s="19:37"):
    """Distance from the baseline up to the top of the digits."""
    return -f.getbbox(s, anchor="ls")[1]


def shared_line():
    """Time and date on one line, vertically centred with the buttons: one top row, mirrored margins."""
    im = app_photo()
    mid = MARGIN + BTN / 2
    ft, fd = time_font(120), font(GSM, 34)
    base = mid + cap_top(ft) / 2
    text(im, (LEFT - 4, base), "19:37", ft, anchor="ls", shadow_a=90)
    tw = ImageDraw.Draw(im).textlength("19:37", font=ft)
    dbase = mid + cap_top(fd, "S") / 2
    text(im, (LEFT + tw + 28, dbase), "Sunday 15 November", fd, fill=(255, 255, 255, 215), anchor="ls")
    app_status(im, RIGHT, MARGIN)
    app_dock(im)
    return im


def mirrored_band():
    """The top block is as tall as the dock and as far from the top as the dock is from the bottom;
    the buttons sit on the time's baseline, so both sides end on one line."""
    im = app_photo()
    top, bottom = MARGIN, MARGIN + (DOCK[3] - DOCK[1])
    ft, fd = time_font(150), font(GSM, 32)
    text(im, (LEFT + 4, top + cap_top(fd, "S")), "Sunday 15 November", fd, fill=(255, 255, 255, 215), anchor="ls")
    text(im, (LEFT - 4, bottom), "19:37", ft, anchor="ls", shadow_a=90)
    app_status(im, RIGHT, bottom - BTN)
    app_dock(im)
    return im


def compact_bar():
    """A light top bar (time, date | network, buttons) on one centre line; the dock carries the weight."""
    im = app_photo()
    mid = MARGIN + BTN / 2
    ft, fd = font(GSM, 56), font(GS, 30)
    text(im, (LEFT, mid + cap_top(ft) / 2), "19:37", ft, anchor="ls")
    tw = ImageDraw.Draw(im).textlength("19:37", font=ft)
    d = ImageDraw.Draw(im)
    x = LEFT + tw + 24
    d.line((x, mid - 18, x, mid + 18), fill=(255, 255, 255, 120), width=2)
    text(im, (x + 24, mid + cap_top(fd, "S") / 2), "Sunday 15 November", fd, fill=(255, 255, 255, 215), anchor="ls")
    app_status(im, RIGHT, MARGIN)
    app_dock(im)
    return im


def render_refinements():
    for name, fn in (("a1-shared-line", shared_line), ("a2-mirrored-band", mirrored_band),
                     ("a3-compact-bar", compact_bar)):
        fn().convert("RGB").save(os.path.join(OUT, name + ".png"))
        print("saved", name)


# ------------------------------------------------------------------ effects from the focused app
import colorsys


def accent(img):
    """The banner's most vivid colour (what the effect is tinted with)."""
    small = img.convert("RGB").resize((24, 14), Image.LANCZOS)
    best, score = (120, 120, 160), -1
    for px in small.getdata():
        h, s, v = colorsys.rgb_to_hsv(*(c / 255 for c in px))
        sc = s * s * v
        if sc > score:
            best, score = px, sc
    h, s, v = colorsys.rgb_to_hsv(*(c / 255 for c in best))
    r, g, b = colorsys.hsv_to_rgb(h, min(1, s * 1.1), max(v, 0.85))
    return int(r * 255), int(g * 255), int(b * 255)


def focus_box(i):
    x = CARD_X0 + i * CARD_STEP
    return x, CARD_Y, x + CW, CARD_Y + CH


def radial(size, center, radius, color, alpha, squash=1.0):
    """A soft radial light, drawn small and blurred (as the app would: cheap)."""
    w, h = size
    s = 8
    g = Image.new("RGBA", (w // s, h // s), color + (0,))
    d = ImageDraw.Draw(g)
    cx, cy, r = center[0] / s, center[1] / s, radius / s
    for k in range(40, 0, -1):
        rr = r * k / 40
        a = int(alpha * (1 - k / 40) ** 1.6)
        d.ellipse((cx - rr, cy - rr * squash, cx + rr, cy + rr * squash), fill=color + (a,))
    return g.resize((w, h), Image.BILINEAR).filter(ImageFilter.GaussianBlur(20))


def screen_with(effect, focus=3):
    im = app_photo()
    b = banners()
    col = accent(b[focus])
    effect(im, b[focus], col, focus)
    # Compact bar, as on the TV now.
    mid = MARGIN + BTN / 2
    ft, fd = font(GSM, 56), font(GS, 30)
    text(im, (LEFT, mid + cap_top(ft) / 2), "19:37", ft, anchor="ls")
    tw = ImageDraw.Draw(im).textlength("19:37", font=ft)
    x = LEFT + tw + 24
    ImageDraw.Draw(im).line((x, mid - 18, x, mid + 18), fill=(255, 255, 255, 120), width=2)
    text(im, (x + 24, mid + cap_top(fd, "S") / 2), "Sunday 15 November", fd, fill=(255, 255, 255, 215), anchor="ls")
    app_status(im, RIGHT, MARGIN)
    app_dock(im, focus=focus)
    return im


def fx_glow(im, img, col, i):
    """A coloured light rising from behind the focused app."""
    x0, y0, x1, y1 = focus_box(i)
    im.alpha_composite(radial(im.size, ((x0 + x1) // 2, y0 + 60), 760, col, 215, squash=0.7))


def fx_wash(im, img, col, i):
    """The whole lower half of the photo takes on the app's colour."""
    g = Image.new("RGBA", im.size, col + (0,))
    d = ImageDraw.Draw(g)
    for y in range(H // 3, H):
        a = int(120 * ((y - H / 3) / (H * 2 / 3)) ** 1.4)
        d.line((0, y, W, y), fill=col + (a,))
    im.alpha_composite(g)


def fx_bloom(im, img, col, i):
    """The focused banner itself, enlarged and blurred into the background like light from a screen."""
    x0, y0, x1, y1 = focus_box(i)
    cx = (x0 + x1) // 2
    big = img.convert("RGBA").resize((1200, 675), Image.BILINEAR).filter(ImageFilter.GaussianBlur(70))
    layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
    layer.alpha_composite(big, (max(-300, min(W - 900, cx - 600)), 360))
    mask = radial(im.size, (cx, 760), 700, (255, 255, 255), 255, squash=0.6).getchannel("A")
    layer.putalpha(Image.composite(layer.getchannel("A"), Image.new("L", im.size, 0), mask))
    # Brighten the bloom like light, then lay it over the photo.
    layer = Image.merge("RGBA", [*(ch.point(lambda v: min(255, int(v * 1.8 + 20))) for ch in layer.split()[:3]),
                                 layer.getchannel("A")])
    im.alpha_composite(layer)
    im.alpha_composite(radial(im.size, (cx, 820), 600, col, 90, squash=0.6))


def fx_spotlight(im, img, col, i):
    """The photo dims except a soft pool of warm light above the focused app."""
    x0, y0, x1, y1 = focus_box(i)
    dim = Image.new("RGBA", im.size, (0, 0, 0, 120))
    hole = radial(im.size, ((x0 + x1) // 2, y0 - 60), 560, (255, 255, 255), 255, squash=0.85).getchannel("A")
    dim.putalpha(Image.eval(hole, lambda a: int(120 * (1 - a / 255))))
    im.alpha_composite(dim)
    im.alpha_composite(radial(im.size, ((x0 + x1) // 2, y0 - 40), 420, col, 70, squash=0.8))


def render_effects_more():
    screen_with(fx_glow, focus=2).convert("RGB").save(os.path.join(OUT, "e1b-glow-smarttube.png"))
    print("saved e1b")


def render_effects():
    for name, fx in (("e0-none", lambda *a: None), ("e1-glow", fx_glow), ("e2-wash", fx_wash),
                     ("e3-bloom", fx_bloom), ("e4-spotlight", fx_spotlight)):
        screen_with(fx).convert("RGB").save(os.path.join(OUT, name + ".png"))
        print("saved", name)


# ------------------------------------------------------------------ round 2: effects close to the focused app

def focused_rect(i):
    x0, y0, x1, y1 = focus_box(i)
    g, gy = int(CW * 0.05), int(CH * 0.05)
    return x0 - g, y0 - gy, x1 + g, y1 + gy


def screen_post(before=None, after=None, focus=3, rim=None):
    im = app_photo()
    b = banners()
    col = accent(b[focus])
    if before:
        before(im, b[focus], col, focus)
    mid = MARGIN + BTN / 2
    ft, fd = font(GSM, 56), font(GS, 30)
    text(im, (LEFT, mid + cap_top(ft) / 2), "19:37", ft, anchor="ls")
    tw = ImageDraw.Draw(im).textlength("19:37", font=ft)
    x = LEFT + tw + 24
    ImageDraw.Draw(im).line((x, mid - 18, x, mid + 18), fill=(255, 255, 255, 120), width=2)
    text(im, (x + 24, mid + cap_top(fd, "S") / 2), "Sunday 15 November", fd, fill=(255, 255, 255, 215), anchor="ls")
    app_status(im, RIGHT, MARGIN)
    app_dock(im, focus=focus)
    if rim:
        rim(im, col)
    if after:
        after(im, b[focus], col, focus)
    return im


def fx_lift_shadow(im, img, col, i):
    """The focused card casts a deep soft shadow onto the dock, as if it rose off it."""
    x0, y0, x1, y1 = focused_rect(i)
    shadow(im, (x0 + 10, y0 + 24, x1 - 10, y1 + 18), 26, blur=26, alpha=200, dy=10)


def fx_light_bar(im, img, col, i):
    """A thin bar of the app's colour glows under the focused card, on the dock's floor."""
    x0, y0, x1, y1 = focused_rect(i)
    cx = (x0 + x1) // 2
    im.alpha_composite(radial(im.size, (cx, y1 + 8), 260, col, 230, squash=0.08))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((cx - 60, y1 + 6, cx + 60, y1 + 11), 3, fill=col + (255,))


def fx_reflection(im, img, col, i):
    """A faint mirror image of the focused banner on the dock's glass, below it."""
    x0, y0, x1, y1 = focused_rect(i)
    ref = img.convert("RGBA").resize((x1 - x0, y1 - y0)).transpose(Image.FLIP_TOP_BOTTOM)
    fade = Image.new("L", ref.size, 0)
    d = ImageDraw.Draw(fade)
    for y in range(ref.size[1]):
        d.line((0, y, ref.size[0], y), fill=int(70 * (1 - y / (ref.size[1] * 0.5)) if y < ref.size[1] * 0.5 else 0))
    ref.putalpha(Image.composite(fade, Image.new("L", ref.size, 0), rounded_mask(ref.size, 22)))
    layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
    layer.alpha_composite(ref, (x0, y1 + 8))
    im.alpha_composite(layer)


def rim_tint(im, col):
    """The dock's thin border takes the focused app's colour."""
    x0, y0, x1, y1 = DOCK
    outline(im, (x0, y0, x1 - 1, y1 - 1), 40, col + (170,), 3)
    glow = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).rounded_rectangle((x0, y0, x1 - 1, y1 - 1), 40, outline=col + (120,), width=10)
    im.alpha_composite(glow.filter(ImageFilter.GaussianBlur(12)))


def render_effects2():
    for name, kw in (("f1-lift-shadow", dict(after=None, before=None)),
                     ("f2-light-bar", dict(after=fx_light_bar)),
                     ("f3-reflection", dict(after=fx_reflection)),
                     ("f4-dock-rim", dict(rim=rim_tint))):
        im = screen_post(**kw)
        if name == "f1-lift-shadow":  # the shadow sits under the card: redraw the card on top
            im = app_photo()
            screen = screen_post()
            im = screen
            fx_lift_shadow(im, None, None, 3)
            x0, y0, x1, y1 = focused_rect(3)
            card(im, banners()[3], (x0, y0 - 6, x1, y1 - 6), 24, focused=True)
        im.convert("RGB").save(os.path.join(OUT, name + ".png"))
        print("saved", name)


# ------------------------------------------------------------------ round 3

def fx_halo(im, img, col, i):
    """A soft white light around the focused card's edge, no colour."""
    x0, y0, x1, y1 = focused_rect(i)
    g = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ImageDraw.Draw(g).rounded_rectangle((x0 - 4, y0 - 4, x1 + 4, y1 + 4), 28, outline=(255, 255, 255, 170), width=12)
    im.alpha_composite(g.filter(ImageFilter.GaussianBlur(14)))
    card(im, img, (x0, y0, x1, y1), 24, focused=True)


def fx_shine(im, img, col, i):
    """A diagonal band of light caught across the focused banner (it sweeps once on focus)."""
    x0, y0, x1, y1 = focused_rect(i)
    w, h = x1 - x0, y1 - y0
    band = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    d = ImageDraw.Draw(band)
    for k in range(-60, 61):
        a = int(70 * (1 - abs(k) / 60) ** 2)
        x = int(w * 0.55) + k
        d.line((x - h // 2, h, x + h // 2, 0), fill=(255, 255, 255, a), width=2)
    band.putalpha(Image.composite(band.getchannel("A"), Image.new("L", band.size, 0), rounded_mask(band.size, 24)))
    im.alpha_composite(band, (x0, y0))


def fx_dot(im, img, col, i):
    """A small white dot under the focused app, like a running-app indicator."""
    x0, y0, x1, y1 = focused_rect(i)
    cx = (x0 + x1) // 2
    ImageDraw.Draw(im).ellipse((cx - 6, y1 + 9, cx + 6, y1 + 21), fill=(255, 255, 255, 235))


def depth_dock(focus=3):
    """The focused card full size and bright; the others smaller and dimmer, receding."""
    im = app_photo()
    mid = MARGIN + BTN / 2
    ft, fd = font(GSM, 56), font(GS, 30)
    text(im, (LEFT, mid + cap_top(ft) / 2), "19:37", ft, anchor="ls")
    tw = ImageDraw.Draw(im).textlength("19:37", font=ft)
    x = LEFT + tw + 24
    ImageDraw.Draw(im).line((x, mid - 18, x, mid + 18), fill=(255, 255, 255, 120), width=2)
    text(im, (x + 24, mid + cap_top(fd, "S") / 2), "Sunday 15 November", fd, fill=(255, 255, 255, 215), anchor="ls")
    app_status(im, RIGHT, MARGIN)
    x0, y0, x1, y1 = DOCK
    panel = Image.new("RGBA", (x1 - x0, y1 - y0), (16, 18, 20, 82))
    im.paste(panel, (x0, y0), Image.composite(panel, Image.new("RGBA", panel.size), rounded_mask(panel.size, 40)).getchannel("A"))
    outline(im, (x0, y0, x1 - 1, y1 - 1), 40, (255, 255, 255, 36), 2)
    for k, img in enumerate(banners()):
        bx, by, bx1, by1 = focus_box(k)
        cx, cy = (bx + bx1) / 2, (by + by1) / 2
        if k == focus:
            s, dim = 1.08, 1.0
        else:
            s, dim = 0.86 - 0.03 * min(3, abs(k - focus) - 1), 0.62
        w, h = CW * s, CH * s
        card(im, img, (int(cx - w / 2), int(cy - h / 2), int(cx + w / 2), int(cy + h / 2)), 22,
             focused=(k == focus), dim=dim)
    return im


def render_effects3():
    for name, fx in (("g1-halo", fx_halo), ("g2-shine", fx_shine), ("g4-dot", fx_dot)):
        screen_post(after=fx).convert("RGB").save(os.path.join(OUT, name + ".png"))
        print("saved", name)
    depth_dock().convert("RGB").save(os.path.join(OUT, "g3-depth.png"))
    print("saved g3-depth")
