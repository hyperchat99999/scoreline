"""Turns a generated icon-sheet SVG into tintable Android glyphs.

The Recraft sheets draw solid black shapes with lighter seam shapes painted on top. Android tints
every opaque pixel, so the seams must become real holes: each glyph is rendered through an SVG mask
(dark paths add ink, light paths erase, in paint order) into a white-on-transparent PNG, then wrapped
in a fixed-size layer-list so layouts still see a normal 24dp icon.

Usage: python -I tools/glyphs.py SHEET.svg name1,name2,... [--seams name=width,...]
Names are assigned to glyphs column by column, top to bottom. Needs rsvg-convert on PATH.
"""
import pathlib
import re
import subprocess
import sys
import tempfile

RES = pathlib.Path(__file__).resolve().parent.parent / "res"
PIXELS = 384
MERGE = 40  # glyph parts closer than this (sheet units) belong to one icon


def parse(svg):
    svg = re.sub(r"<metadata>.*?</metadata>", "", svg, flags=re.S)
    paths = []
    for attrs in re.findall(r"<path([^>]*)>", svg):
        fill = re.search(r'fill="rgb\((\d+),(\d+),(\d+)\)"', attrs)
        d = re.search(r'\sd="([^"]*)"', attrs).group(1)
        t = re.search(r'translate\(([-\d.]+),([-\d.]+)\)', attrs)
        tx, ty = (float(t.group(1)), float(t.group(2))) if t else (0.0, 0.0)
        nums = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", d)]
        xs, ys = [n + tx for n in nums[0::2]], [n + ty for n in nums[1::2]]
        rgb = tuple(int(g) for g in fill.groups()) if fill else (0, 0, 0)
        luma = .299 * rgb[0] + .587 * rgb[1] + .114 * rgb[2]
        paths.append(dict(d=d, tx=tx, ty=ty, box=(min(xs), min(ys), max(xs), max(ys)), ink=luma < 110))
    view = [float(v) for v in re.search(r'viewBox="([^"]*)"', svg).group(1).split()]
    return paths, view


def clusters(paths, view):
    area = view[2] * view[3]
    ink = [p for p in paths if p["ink"]]
    groups = []
    for p in ink:
        x0, y0, x1, y1 = p["box"]
        if (x1 - x0) * (y1 - y0) > area * .5:
            continue
        hit = [g for g in groups if overlaps(g["box"], p["box"], MERGE)]
        merged = dict(box=p["box"], members=[p])
        for g in hit:
            groups.remove(g)
            merged["members"] += g["members"]
            merged["box"] = union(merged["box"], g["box"])
        groups.append(merged)
    # Merging can make earlier groups touch; settle until stable.
    changed = True
    while changed:
        changed = False
        for a in groups:
            for b in groups:
                if a is not b and overlaps(a["box"], b["box"], MERGE):
                    a["members"] += b["members"]
                    a["box"] = union(a["box"], b["box"])
                    groups.remove(b)
                    changed = True
                    break
            if changed:
                break
    for p in paths:
        if p["ink"]:
            continue
        cx, cy = (p["box"][0] + p["box"][2]) / 2, (p["box"][1] + p["box"][3]) / 2
        for g in groups:
            x0, y0, x1, y1 = g["box"]
            if x0 <= cx <= x1 and y0 <= cy <= y1:
                g["members"].append(p)
    # Columns: split the x-sorted centres wherever there is a wide gap, then read each top to bottom.
    groups.sort(key=lambda g: (g["box"][0] + g["box"][2]) / 2)
    column, last = 0, None
    for g in groups:
        x = (g["box"][0] + g["box"][2]) / 2
        if last is not None and x - last > view[2] / 8:
            column += 1
        g["column"], last = column, x
    groups.sort(key=lambda g: (g["column"], (g["box"][1] + g["box"][3]) / 2))
    return groups


def overlaps(a, b, margin):
    return not (a[2] + margin < b[0] or b[2] + margin < a[0] or a[3] + margin < b[1] or b[3] + margin < a[1])


def union(a, b):
    return (min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))


def render(group, order, seam, out):
    x0, y0, x1, y1 = group["box"]
    size = max(x1 - x0, y1 - y0) * 1.06
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    vx, vy = cx - size / 2, cy - size / 2
    body = []
    for p in sorted(group["members"], key=order.index):
        transform = f' transform="translate({p["tx"]},{p["ty"]})"'
        if p["ink"]:
            body.append(f'<path{transform} fill="white" d="{p["d"]}"/>')
        else:
            stroke = f' stroke="black" stroke-width="{seam * size:.2f}" stroke-linejoin="round"' if seam else ""
            body.append(f'<path{transform} fill="black"{stroke} d="{p["d"]}"/>')
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vx} {vy} {size} {size}" width="{PIXELS}" height="{PIXELS}">'
           f'<defs><mask id="m" maskUnits="userSpaceOnUse" x="{vx}" y="{vy}" width="{size}" height="{size}">'
           f'<rect x="{vx}" y="{vy}" width="{size}" height="{size}" fill="black"/>{"".join(body)}</mask></defs>'
           f'<rect x="{vx}" y="{vy}" width="{size}" height="{size}" fill="white" mask="url(#m)"/></svg>')
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False, encoding="utf-8") as f:
        f.write(svg)
    subprocess.run(["rsvg-convert", f.name, "-o", str(out)], check=True)
    pathlib.Path(f.name).unlink()


def main():
    sheet, names = pathlib.Path(sys.argv[1]), sys.argv[2].split(",")
    seams = {}
    if len(sys.argv) > 4 and sys.argv[3] == "--seams":
        seams = {k: float(v) for k, v in (item.split("=") for item in sys.argv[4].split(","))}
    paths, view = parse(sheet.read_text(encoding="utf-8"))
    groups = clusters(paths, view)
    if len(groups) != len(names):
        sys.exit(f"found {len(groups)} glyphs, expected {len(names)}")
    (RES / "drawable-nodpi").mkdir(exist_ok=True)
    for name, group in zip(names, groups):
        if name == "-":
            continue
        render(group, paths, seams.get(name, 0), RES / "drawable-nodpi" / f"glyph_{name}.png")
        print("glyph", name, [round(v) for v in group["box"]])


if __name__ == "__main__":
    main()
