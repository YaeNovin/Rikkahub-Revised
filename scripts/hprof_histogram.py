"""Read Android HPROF class counts and instance payload sizes (not retained/leak sizes)."""
from __future__ import annotations
import argparse
from collections import Counter
import json
import mmap
from pathlib import Path
import struct


def histogram(path: Path):
    with path.open("rb") as source, mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) as data:
        end = data.find(b"\0")
        if end < 0 or not data[:end].startswith(b"JAVA PROFILE"):
            raise ValueError("Unsupported heap header")
        ids = struct.unpack_from(">I", data, end + 1)[0]
        if ids not in (4, 8):
            raise ValueError("Unsupported object ID size")
        sizes = {2: ids, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
        names, classes = {}, {}
        counts, payloads = Counter(), Counter()
        pos = end + 13

        def integer(offset, size):
            return int.from_bytes(data[offset:offset + size], "big")

        def heap(pos, limit):
            while pos < limit:
                tag = data[pos]
                pos += 1
                if tag == 0x20:
                    pos += ids + 4 + 6 * ids + 4
                    count = integer(pos, 2)
                    pos += 2
                    for _ in range(count):
                        value_type = data[pos + 2]
                        pos += 3 + sizes[value_type]
                    count = integer(pos, 2)
                    pos += 2
                    for _ in range(count):
                        value_type = data[pos + ids]
                        pos += ids + 1 + sizes[value_type]
                    count = integer(pos, 2)
                    pos += 2 + count * (ids + 1)
                elif tag == 0x21:
                    cls = integer(pos + ids + 4, ids)
                    size = integer(pos + ids * 2 + 4, 4)
                    counts[cls] += 1
                    payloads[cls] += size
                    pos += ids * 2 + 8 + size
                elif tag == 0x22:
                    count = integer(pos + ids + 4, 4)
                    cls = integer(pos + ids + 8, ids)
                    counts[cls] += 1
                    payloads[cls] += count * ids
                    pos += ids * 2 + 8 + count * ids
                elif tag in (0x23, 0xc3):
                    count = integer(pos + ids + 4, 4)
                    value_type = data[pos + ids + 8]
                    key = f"primitive[{value_type}]"
                    counts[key] += 1
                    payloads[key] += count * sizes[value_type]
                    pos += ids + 9 + (count * sizes[value_type] if tag == 0x23 else 0)
                elif tag == 0xfe:
                    pos += 4 + ids
                elif tag in (0xff, 0x05, 0x07, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x90):
                    pos += ids
                elif tag == 0x01:
                    pos += ids * 2
                elif tag in (0x02, 0x03, 0x08, 0x8e):
                    pos += ids + 8
                elif tag in (0x04, 0x06):
                    pos += ids + 4
                else:
                    raise ValueError(f"Unsupported heap tag {tag:#x} at {pos - 1:#x}")
            if pos != limit:
                raise ValueError("Truncated or malformed heap record")

        while pos < len(data):
            tag, _, length = struct.unpack_from(">BII", data, pos)
            pos += 9
            limit = pos + length
            if limit > len(data):
                raise ValueError("Truncated HPROF")
            if tag == 0x01:
                names[integer(pos, ids)] = data[pos + ids:limit].decode("utf-8", "replace")
            elif tag == 0x02:
                classes[integer(pos + 4, ids)] = integer(pos + 8 + ids, ids)
            elif tag in (0x0c, 0x1c):
                heap(pos, limit)
            pos = limit
        rows = [{"class": names.get(classes.get(cls), str(cls)).replace("/", "."),
                 "count": count, "instance_payload_bytes": payloads[cls]} for cls, count in counts.items()]
        return sorted(rows, key=lambda row: row["instance_payload_bytes"], reverse=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("heap", type=Path)
    args = parser.parse_args()
    rows = histogram(args.heap)
    output = args.heap.with_suffix(".histogram.json")
    output.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    selected = [row for row in rows if row["class"] in ("me.rerere.rikkahub.RouteActivity",
        "me.rerere.rikkahub.RikkaHubApp", "coil3.RealImageLoader", "android.graphics.Bitmap")]
    print(json.dumps({"objects": sum(row["count"] for row in rows), "selected": selected}))


if __name__ == "__main__":
    main()
