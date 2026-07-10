#!/usr/bin/env python3
"""Turn AUTOBAHN_TIMING stdout lines into a CSV — the cheap complement to the sampling traces.

Every Autobahn echo case prints one uniform line from AutobahnTestHelpers.kt (echoMessageAndClose
/ echoBinaryMessageAndClose), e.g.:

    AUTOBAHN_TIMING [JVM] case=392 count=1000 connect=12ms echo=8421ms close=3ms total=8440ms avg_msg=8421us

This is emitted on every socket platform but never aggregated. Each profiling leg tees its run
output to a log; this parser folds those lines into one CSV so per-case timing is comparable
across platforms alongside the flamegraphs.

Usage:  autobahn_timing_to_csv.py <run.log> [<run.log> ...] -o timing.csv
        (reads stdin if no input files are given)
"""
import argparse
import csv
import re
import sys

# One regex covers both the text and binary echo sites (identical format strings).
LINE = re.compile(
    r"AUTOBAHN_TIMING\s+\[(?P<agent>[^\]]*)\]\s+"
    r"case=(?P<case>\d+)\s+count=(?P<count>\d+)\s+"
    r"connect=(?P<connect_ms>\d+)ms\s+echo=(?P<echo_ms>\d+)ms\s+"
    r"close=(?P<close_ms>\d+)ms\s+total=(?P<total_ms>\d+)ms\s+"
    r"avg_msg=(?P<avg_msg_us>\d+)us"
)
FIELDS = [
    "agent", "case", "count",
    "connect_ms", "echo_ms", "close_ms", "total_ms", "avg_msg_us",
]


def rows(streams):
    for stream in streams:
        for line in stream:
            m = LINE.search(line)
            if m:
                yield m.groupdict()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("logs", nargs="*", help="run log files (default: stdin)")
    ap.add_argument("-o", "--output", default="-", help="CSV path (default: stdout)")
    args = ap.parse_args()

    streams = [open(p, encoding="utf-8", errors="replace") for p in args.logs] or [sys.stdin]
    out = sys.stdout if args.output == "-" else open(args.output, "w", newline="", encoding="utf-8")
    try:
        writer = csv.DictWriter(out, fieldnames=FIELDS)
        writer.writeheader()
        n = 0
        for row in rows(streams):
            writer.writerow(row)
            n += 1
    finally:
        for s in streams:
            if s is not sys.stdin:
                s.close()
        if out is not sys.stdout:
            out.close()

    # Report to stderr so stdout stays clean when piping the CSV.
    print(f"autobahn_timing_to_csv: wrote {n} row(s)", file=sys.stderr)
    # Not finding any line is worth a non-zero exit in CI (the run produced no timing).
    return 0 if n else 1


if __name__ == "__main__":
    sys.exit(main())
