"""Rebuild synthetic wallpaper fixtures; uses no user photos or external images."""
from __future__ import annotations

import math
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
PALETTES = {
    "pink": (0xF8D7E8, 0xC83278, 0x7D367E),
    "night": (0x192447, 0x5553A8, 0x277A91),
    "sunset": (0xF2B369, 0xD3595D, 0x784D8F),
    "pastel": (0xE5F1EF, 0xC2D2EC, 0xF2D1E2),
}


def chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def png(palette: tuple[int, int, int]) -> bytes:
    width, height = 256, 160
    colors = [((c >> 16) & 255, (c >> 8) & 255, c & 255) for c in palette]
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            # Broad curved color bands plus a gradient, including the portrait crop.
            band = int((y / height + .22 * math.sin(x / 31)) * 4) % 3
            mix = .18 * x / width
            raw.extend(round(a * (1 - mix) + b * mix) for a, b in zip(colors[band], colors[(band + 1) % 3]))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def main():
    output = ROOT / "material3/src/test/resources/wallpaper-reference"
    output.mkdir(parents=True, exist_ok=True)
    for name, palette in PALETTES.items():
        (output / f"{name}.png").write_bytes(png(palette))


if __name__ == "__main__":
    main()
