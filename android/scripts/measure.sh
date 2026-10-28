#!/usr/bin/env bash
# Measures the benchmark build on a phone over adb: cold start, home ready, category open and
# scroll jank, one line per number (android/README.md). It only starts the app, opens one
# category from the home, scrolls it and goes back.
#
# Usage: android/scripts/measure.sh [CATEGORY NAME...]
# ADB overrides the adb command, e.g. a Windows adb.exe under WSL. The files it pushes and pulls
# go through a temporary folder under the current directory, so such an adb can reach them.
set -euo pipefail

read -ra adb <<<"${ADB:-adb}"
here=$(cd "$(dirname "$0")" && pwd)
package=io.github.bert0ns.cribrum
remote=/data/local/tmp/cribrum-measure
# The home's name of the category to open, in English and Italian.
categories=("$@")
[[ ${#categories[@]} -gt 0 ]] || categories=("Similar copies" "Copie simili")
# The trace covers the cold start, the wait for the home and the category open.
trace_ms=20000

local_dir=$(mktemp -d ./cribrum-measure.XXXXXX)
local_dir=${local_dir#./}
cleanup() {
    "${adb[@]}" shell rm -rf "$remote" >/dev/null 2>&1 || true
    rm -rf "$local_dir"
}
trap cleanup EXIT

die() {
    echo "measure: $*" >&2
    exit 1
}

# A Windows adb ends lines with \r.
shell() { "${adb[@]}" shell "$@" | tr -d '\r'; }

[[ $(shell dumpsys window) != *isKeyguardShowing=true* ]] || die "unlock the phone first"
flags=$(shell dumpsys package "$package" | grep -m1 'pkgFlags=' || true)
[[ -n $flags ]] || die "$package is not installed"
[[ $flags != *DEBUGGABLE* ]] || die "install the benchmark build first (android/README.md)"

# A folded Fold shows apps on its cover display: use the display that is on.
viewport=$(shell dumpsys display | grep -o 'DisplayViewport{[^}]*isActive=true[^}]*' | head -n1 || true)
[[ $viewport =~ displayId=([0-9]+) ]] || die "turn the screen on"
display=${BASH_REMATCH[1]}
[[ $viewport =~ logicalFrame=Rect\(0,\ 0\ -\ ([0-9]+),\ ([0-9]+)\) ]] || die "unknown display size: $viewport"
width=${BASH_REMATCH[1]} height=${BASH_REMATCH[2]}

# perfetto reads its config and writes its trace through pipes: it may not open files under /data/local/tmp.
cat >"$local_dir/trace.sh" <<EOF
cat <<CFG | perfetto --txt -c - -o - 2>/dev/null | cat >$remote/trace.pftrace
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
  atrace_apps: "$package"
  atrace_categories: "am"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
} } }
data_sources { config { name: "linux.process_stats" } }
duration_ms: $trace_ms
CFG
EOF
shell mkdir -p "$remote"
"${adb[@]}" push -q "$local_dir/trace.sh" "$remote/trace.sh" >/dev/null

# Dumps the screen and taps the centre of the first node whose text is one of the arguments.
tap_text() {
    shell uiautomator dump "$remote/ui.xml" >/dev/null
    "${adb[@]}" pull -q "$remote/ui.xml" "$local_dir/ui.xml" >/dev/null
    local text node
    for text in "$@"; do
        node=$(grep -o "<node [^>]*text=\"$text\"[^>]*" "$local_dir/ui.xml" | head -n1 || true)
        if [[ $node =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]]; then
            shell input -d "$display" tap $(((BASH_REMATCH[1] + BASH_REMATCH[3]) / 2)) $(((BASH_REMATCH[2] + BASH_REMATCH[4]) / 2))
            return 0
        fi
    done
    return 1
}

shell am force-stop "$package"
"${adb[@]}" shell sh "$remote/trace.sh" &
tracing=$!
# An app reads whether to trace when it starts: start it only once perfetto has turned tracing on.
for _ in $(seq 20); do
    [[ $(shell getprop debug.atrace.tags.enableflags) =~ ^(0x)?0*$ ]] || break
    sleep 0.5
done
cold=$(shell am start -W -n "$package/.MainActivity" | sed -n 's/^TotalTime: //p')
echo "cold start: ${cold:-unknown} ms"

opened=false
for _ in 1 2 3 4 5 6 7 8 9 10; do
    if tap_text "${categories[@]}"; then
        opened=true
        break
    fi
    sleep 1
done
wait "$tracing"
"${adb[@]}" pull -q "$remote/trace.pftrace" "$local_dir/trace.pftrace" >/dev/null
uv run --quiet "$here/trace_times.py" "$local_dir/trace.pftrace" "$package"
$opened || die "no category named ${categories[*]} on the home"

# Swipes long and fast enough to be flings, never taps on a tile.
shell dumpsys gfxinfo "$package" reset >/dev/null
for _ in 1 2 3 4 5; do
    shell input -d "$display" swipe $((width / 2)) $((height * 3 / 4)) $((width / 2)) $((height / 4)) 150
    sleep 1
done
for _ in 1 2 3 4 5; do
    shell input -d "$display" swipe $((width / 2)) $((height / 4)) $((width / 2)) $((height * 3 / 4)) 150
    sleep 1
done
frames=$(shell dumpsys gfxinfo "$package" framestats)
sed -n -e 's/^Total frames rendered: /scroll frames: /p' -e 's/^Janky frames: [0-9]* (\(.*\))/scroll janky frames: \1/p' \
    -e 's/^90th percentile: /scroll p90: /p' -e 's/^99th percentile: /scroll p99: /p' <<<"$frames" | head -n4
shell input -d "$display" keyevent KEYCODE_BACK

# The last scan logs the images it found and its phase times, while the log still holds them.
scan=$(shell logcat -d -s scan-time | sed -n 's/.* scan-time: //p')
if [[ -n $scan ]]; then
    sed -n 's/^images /indexed images: /p' <<<"$scan" | tail -n1
    grep -v '^images ' <<<"$scan" | tail -n7 | sed 's/^/last scan /'
else
    echo "indexed images: unknown (no scan in the log)"
fi
