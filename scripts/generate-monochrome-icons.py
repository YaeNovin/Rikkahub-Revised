"""Derive adaptive-icon monochrome PNGs from the foreground alpha mask.

The launcher monochrome layer deliberately keeps the foreground silhouette and
removes its source RGB colours. It is not a byte-for-byte copy of the coloured
foreground asset, so Android themed launchers can tint it independently.
"""

from __future__ import annotations

import binascii
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"


def paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    return a if pa <= pb and pa <= pc else b if pb <= pc else c


def read_rgba(path: Path) -> tuple[int, int, bytes]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"not a PNG: {path}")
    pos = 8
    width = height = None
    compressed = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        kind = data[pos + 4 : pos + 8]
        payload = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", payload)
            if (depth, color_type, interlace) != (8, 6, 0):
                raise ValueError(f"unsupported PNG format: {path}")
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            break
    assert width is not None and height is not None
    raw = zlib.decompress(compressed)
    stride = width * 4
    rows = []
    previous = bytearray(stride)
    cursor = 0
    for _ in range(height):
        filter_type = raw[cursor]
        encoded = raw[cursor + 1 : cursor + 1 + stride]
        cursor += stride + 1
        row = bytearray(encoded)
        for i in range(stride):
            left = row[i - 4] if i >= 4 else 0
            up = previous[i]
            up_left = previous[i - 4] if i >= 4 else 0
            if filter_type == 1:
                row[i] = (row[i] + left) & 255
            elif filter_type == 2:
                row[i] = (row[i] + up) & 255
            elif filter_type == 3:
                row[i] = (row[i] + ((left + up) // 2)) & 255
            elif filter_type == 4:
                row[i] = (row[i] + paeth(left, up, up_left)) & 255
            elif filter_type != 0:
                raise ValueError(f"unsupported PNG filter {filter_type}: {path}")
        rows.append(row)
        previous = row
    return width, height, b"".join(rows)


def write_rgba(path: Path, width: int, height: int, rgba: bytes) -> None:
    raw = b"".join(b"\x00" + rgba[row * width * 4 : (row + 1) * width * 4] for row in range(height))

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


for source in sorted(ROOT.glob("mipmap-*/ic_launcher_foreground.png")):
    target = source.with_name("ic_launcher_monochrome.png")
    width, height, rgba = read_rgba(source)
    monochrome = bytearray(rgba)
    for i in range(0, len(monochrome), 4):
        alpha = monochrome[i + 3]
        monochrome[i : i + 3] = b"\xff\xff\xff" if alpha else b"\x00\x00\x00"
    write_rgba(target, width, height, bytes(monochrome))
    print(f"wrote {target}")
