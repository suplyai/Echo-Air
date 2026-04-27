#!/usr/bin/env python3
"""
Convert the potrace-style SVGs in app/src/main/res/raw/ into Android
VectorDrawable XML. The input SVGs all share the same shape:

    <svg width="600pt" height="400pt" viewBox="0 0 600 400">
      <g transform="translate(0,400) scale(0.1,-0.1)" fill="#000000" stroke="none">
        <path d="..."/>
        <path d="..."/>
        ...
      </g>
    </svg>

The translate-then-scale group transform in SVG matches Android's group
transform order (scale, rotate, translate applied in that sequence), so
we replicate it 1:1 with a <group> element. Paths get fillColor=black —
the actual on-screen tint is governed by android:tint on <vector>, set
to ?attr/colorOnSurface so the line art adapts to light/dark theme.
"""
import re
import sys
from pathlib import Path

PATH_RE = re.compile(r'<path\s+d="([^"]+)"', re.DOTALL)
WS_RE = re.compile(r'\s+')

VECTOR_HEADER = """<!--
  Generated from {src} by tools/svg_to_vector.py.
  Source SVG: 600x400 viewBox, single <g transform="translate(0,400) scale(0.1,-0.1)">.
  No XML tint here — the app theme is android:Theme.Material.Light, which
  doesn't define ?attr/colorOnSurface (that's a Material3 attribute, and
  Material3 in this app lives only inside Compose's MaterialTheme).
  Tinting is applied at draw time by HomeScreen via Image(colorFilter=...)
  using MaterialTheme.colorScheme.onSurface, which adapts to light/dark.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="300dp"
    android:height="200dp"
    android:viewportWidth="600"
    android:viewportHeight="400">
    <group
        android:translateX="0"
        android:translateY="400"
        android:scaleX="0.1"
        android:scaleY="-0.1">
"""
VECTOR_FOOTER = """    </group>
</vector>
"""

def convert(svg_path: Path, vector_path: Path) -> None:
    svg = svg_path.read_text()
    paths = PATH_RE.findall(svg)
    if not paths:
        sys.exit(f"no <path d> found in {svg_path}")

    out = [VECTOR_HEADER.format(src=svg_path.name)]
    for d in paths:
        # Collapse whitespace inside the path data so each <path> sits on
        # one line. SVG path syntax is whitespace-tolerant, so this is
        # safe and keeps the diff readable.
        flat = WS_RE.sub(' ', d).strip()
        out.append(f'        <path\n            android:pathData="{flat}"\n            android:fillColor="#FF000000" />\n')
    out.append(VECTOR_FOOTER)
    vector_path.write_text(''.join(out))
    print(f"{svg_path} -> {vector_path}  ({len(paths)} paths)")

if __name__ == "__main__":
    base = Path("app/src/main/res")
    convert(base / "raw" / "uld1.svg", base / "drawable" / "home_hero_a.xml")
    convert(base / "raw" / "uld3.svg", base / "drawable" / "home_hero_b.xml")
