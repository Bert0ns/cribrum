# /// script
# requires-python = ">=3.12"
# dependencies = ["perfetto"]
# ///
"""Prints the home-ready and category-open times from a Perfetto trace of Cribrum.

Usage: uv run android/scripts/trace_times.py TRACE [PACKAGE]

measure.sh calls it on the trace it records: a cold start, a wait on the home, and
one category opened. The section names are in android/README.md.
"""

import sys

from perfetto.trace_processor import TraceProcessor

# Suspending sections are async ones, on process tracks, and the others are on thread tracks.
QUERY = """
with app as (select upid, start_ts from process where name = '{package}'),
sections as (
  select s.name, s.ts, s.dur from slice s
    join thread_track t on s.track_id = t.id join thread using (utid)
    where thread.upid in (select upid from app)
  union all
  select s.name, s.ts, s.dur from slice s
    join process_track t on s.track_id = t.id
    where t.upid in (select upid from app)
),
page as (select ts, dur from sections where name = 'Review.page' order by ts limit 1)
select
  coalesce((select min(start_ts) from app), (select min(ts) from sections)) as start,
  (select max(ts + dur) from sections
    where name = 'Review.overview' and ts < coalesce((select ts from page), 1 << 62)) as home_end,
  (select dur from page) as page_dur
"""


def ms(ns: int | None) -> str:
    return "unknown" if ns is None else f"{ns / 1e6:.0f} ms"


def main() -> None:
    trace = sys.argv[1]
    package = sys.argv[2] if len(sys.argv) > 2 else "io.github.bert0ns.cribrum"
    with TraceProcessor(trace=trace) as tp:
        row = next(iter(tp.query(QUERY.format(package=package))))
    # The start is the fork of the process when the trace has it, else the app's first section.
    home = None if None in (row.start, row.home_end) else row.home_end - row.start
    print(f"home ready: {ms(home)}")
    print(f"category open: {ms(row.page_dur)}")


if __name__ == "__main__":
    main()
