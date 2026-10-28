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
res=$here/../app/src/main/res
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
    if [[ $1 != 0 ]]; then
        [[ -z ${tracing:-} ]] || kill "$tracing" 2>/dev/null || true
        [[ -z ${started:-} ]] || "${adb[@]}" shell am force-stop "$package" >/dev/null 2>&1 || true
    fi
    "${adb[@]}" shell rm -rf "$remote" >/dev/null 2>&1 || true
    rm -rf "$local_dir"
}
trap 'cleanup $?' EXIT

die() {
    echo "measure: $*" >&2
    exit 1
}

# A Windows adb ends lines with \r.
shell() { "${adb[@]}" shell "$@" | tr -d '\r'; }

# The strings with these names, in English and Italian.
strings() {
    local key
    for key; do sed -n "s/.*name=\"$key\">\([^<]*\)<.*/\1/p" "$res/values/strings.xml" "$res/values-it/strings.xml"; done
}

# Only a category title may be tapped, never a button such as Trash or Skip.
mapfile -t titles < <(strings category_exact_duplicate category_near_duplicate category_burst category_blurry \
    category_bad_exposure category_screenshot category_document category_forwarded category_sticker)
for name in "${categories[@]}"; do
    known=false
    for title in "${titles[@]}"; do [[ $name == "$title" ]] && known=true; done
    $known || die "not a category title: $name"
done
mapfile -t back < <(strings back)

[[ $(shell dumpsys window) != *isKeyguardShowing=true* ]] || die "unlock the phone first"
flags=$(shell dumpsys package "$package" | grep -m1 'pkgFlags=' || true)
[[ -n $flags ]] || die "$package is not installed"
[[ $flags != *DEBUGGABLE* ]] || die "install the benchmark build first (android/README.md)"
# The scan is the app's only WorkManager work, and the force-stop below would kill it.
services=$(shell dumpsys activity services "$package")
[[ $services != *SystemJobService* && $services != *SystemForegroundService* ]] ||
    die "a scan is running: wait for it to finish, since a cold start would stop it"

# The display that is on and its size. A folded Fold moves display 0 to its cover screen, where
# uiautomator dump also looks (it ignores --display).
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

# Dumps the screen into ui.xml, and fails rather than leave an older dump there.
dump() {
    rm -f "$local_dir/ui.xml"
    shell rm -f "$remote/ui.xml"
    shell uiautomator dump "$remote/ui.xml" >/dev/null &&
        "${adb[@]}" pull -q "$remote/ui.xml" "$local_dir/ui.xml" >/dev/null &&
        [[ -s $local_dir/ui.xml ]]
}

# The first node of the last dump whose attribute $1 is exactly $2.
node() { grep -o '<node [^>]*' "$local_dir/ui.xml" | grep -F -- "$1=\"$2\"" | head -n1 || true; }

# Taps the centre of the first node whose text is one of the arguments, and sets tapped to that text.
tap_text() {
    dump || return 1
    local text
    for text in "$@"; do
        if [[ $(node text "$text") =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]]; then
            shell input -d "$display" tap $(((BASH_REMATCH[1] + BASH_REMATCH[3]) / 2)) $(((BASH_REMATCH[2] + BASH_REMATCH[4]) / 2))
            tapped=$text
            return 0
        fi
    done
    return 1
}

# The category screen has the category's name as its title and a back button, which the home lacks.
category_open() {
    dump || return 1
    [[ -n $(node text "$tapped") ]] || return 1
    local label
    for label in "${back[@]}"; do [[ -n $(node content-desc "$label") ]] && return 0; done
    return 1
}

shell am force-stop "$package"
started=true
"${adb[@]}" shell sh "$remote/trace.sh" &
tracing=$!
# An app reads whether to trace when it starts: start it only once perfetto has turned tracing on.
for _ in $(seq 20); do
    [[ $(shell getprop debug.atrace.tags.enableflags) =~ ^(0x)?0*$ ]] || break
    sleep 0.5
done
[[ ! $(shell getprop debug.atrace.tags.enableflags) =~ ^(0x)?0*$ ]] || die "perfetto did not start tracing"
cold=$(shell am start -W -n "$package/.MainActivity" | sed -n 's/^TotalTime: //p')
echo "cold start: ${cold:-unknown} ms"

tapped=
for _ in 1 2 3 4 5 6 7 8 9 10; do
    tap_text "${categories[@]}" && break
    sleep 1
done
[[ -n $tapped ]] || die "no category named ${categories[*]} on the home"
sleep 2
category_open || die "$tapped did not open: it may have nothing to review"
wait "$tracing"
tracing=
"${adb[@]}" pull -q "$remote/trace.pftrace" "$local_dir/trace.pftrace" >/dev/null
[[ -s $local_dir/trace.pftrace ]] || die "perfetto wrote no trace"
times=$(uv run --quiet "$here/trace_times.py" "$local_dir/trace.pftrace" "$package")
echo "$times"
[[ $times != *unknown* ]] || die "the trace lacks the app's sections: run the script again"

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

# A scan logs "images N" first, then its phase times: keep the lines from the last "images" on.
scan=$(shell logcat -d -s scan-time | sed -n 's/.* scan-time: //p' | awk '/^images /{last=""} {last=last $0 "\n"} END{printf "%s", last}')
if [[ -n $scan ]]; then
    sed -n 's/^images /indexed images: /p' <<<"$scan"
    grep -v '^images ' <<<"$scan" | sed 's/^/last scan /'
else
    echo "indexed images: unknown (no scan in the log)"
fi
