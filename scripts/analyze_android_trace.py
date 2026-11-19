"""Save main-thread frame and lazy-prefetch evidence from a Perfetto capture."""
from __future__ import annotations

import argparse
import csv
import io
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--processor", type=Path, required=True)
    parser.add_argument("--package", default="me.rerere.rikkahub")
    parser.add_argument("--pid", type=int, help="Exact capture PID when the ROM omits process command lines")
    parser.add_argument("--output", type=Path, help="Separate reports when analyzing multiple phases")
    args = parser.parse_args()
    report_dir = args.output or args.trace.parent
    report_dir.mkdir(parents=True, exist_ok=True)
    if not re.fullmatch(r"[A-Za-z0-9_.]+", args.package):
        parser.error("Invalid Android package name")
    process_filter = f"p.pid={args.pid}" if args.pid else f"p.name='{args.package}'"
    main_slices = f"""SELECT s.* FROM slice s
        JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid
        JOIN process p ON t.upid=p.upid WHERE {process_filter} AND t.tid=p.pid"""
    queries = {
        "main-long-slices": f"SELECT name,ROUND(dur/1e6,3) ms,depth FROM ({main_slices}) WHERE dur>16000000 ORDER BY dur DESC LIMIT 60",
        "prefetch-descendants": f"""SELECT name,COUNT(*) count,ROUND(SUM(dur)/1e6,3) total_ms,ROUND(MAX(dur)/1e6,3) max_ms
            FROM descendant_slice((SELECT id FROM ({main_slices}) WHERE name='compose:lazy:prefetch:idle_frame' ORDER BY dur DESC LIMIT 1))
            GROUP BY name ORDER BY MAX(dur) DESC LIMIT 30""",
        "frame-descendants": f"""SELECT name,COUNT(*) count,ROUND(SUM(dur)/1e6,3) total_ms,ROUND(MAX(dur)/1e6,3) max_ms
            FROM descendant_slice((SELECT id FROM ({main_slices}) WHERE name GLOB 'Choreographer#doFrame*' ORDER BY dur DESC LIMIT 1))
            GROUP BY name ORDER BY SUM(dur) DESC LIMIT 45""",
        "frame-timeline": f"""SELECT ROUND((ts-(SELECT start_ts FROM trace_bounds))/1e9,3) seconds,
            name,ROUND(dur/1e6,3) ms FROM ({main_slices})
            WHERE name GLOB 'Choreographer#doFrame*' AND dur>30000000 ORDER BY ts""",
        "render-long-slices": f"""SELECT s.name,ROUND(s.dur/1e6,3) ms,
            ROUND((s.ts-(SELECT start_ts FROM trace_bounds))/1e9,3) seconds
            FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid
            JOIN process p ON t.upid=p.upid WHERE {process_filter} AND t.name='RenderThread'
            AND s.dur>16000000 ORDER BY s.dur DESC LIMIT 40""",
        "thread-cpu": f"""SELECT t.name,ROUND(SUM(s.dur)/1e6,3) cpu_ms
            FROM sched s JOIN thread t ON s.utid=t.utid JOIN process p ON t.upid=p.upid
            WHERE {process_filter} GROUP BY t.utid ORDER BY SUM(s.dur) DESC LIMIT 20""",
        "summary": f"""SELECT
            ROUND(MAX(CASE WHEN name='compose:lazy:prefetch:idle_frame' THEN dur END)/1e6,3) max_prefetch_ms,
            SUM(CASE WHEN name='compose:lazy:prefetch:idle_frame' THEN 1 ELSE 0 END) prefetch_jobs,
            ROUND(MAX(CASE WHEN name GLOB 'Choreographer#doFrame*' THEN dur END)/1e6,3) max_doFrame_ms,
            SUM(CASE WHEN name GLOB 'Choreographer#doFrame*' AND dur>50000000 THEN 1 ELSE 0 END) frames_over_50ms
            FROM ({main_slices})""",
    }
    summary = None
    for name, sql in queries.items():
        result = subprocess.run([str(args.processor.resolve()), "query", str(args.trace.resolve()), sql, "--format", "csv"],
            capture_output=True, check=True, text=True, encoding="utf-8", errors="replace")
        (report_dir / (name + ".csv")).write_text(result.stdout, encoding="utf-8")
        if name == "summary":
            summary = next(csv.DictReader(io.StringIO(result.stdout)))
    (report_dir / "trace-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
