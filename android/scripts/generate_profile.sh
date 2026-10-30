#!/usr/bin/env bash
# Generates the Baseline Profile on a phone over adb and writes it to the app's sources
# (android/README.md). It holds the screen for a few minutes. Everything it installs is a separate
# package that it uninstalls at the end, and the app already on the phone is left alone.
#
# Usage: scripts/generate_profile.sh from android/, after
#   ./gradlew :app:assembleNonMinifiedRelease :baselineprofile:assembleNonMinifiedRelease
# ADB overrides the adb command, e.g. a Windows adb.exe under WSL. The files it installs and pulls
# go through a temporary folder under the current directory, so such an adb can reach them.
set -euo pipefail

read -ra adb <<<"${ADB:-adb}"
here=$(cd "$(dirname "$0")" && pwd)
target=io.github.bert0ns.cribrum.profile
runner=io.github.bert0ns.cribrum.baselineprofile
generator=$runner.HomeAndCategoriesGenerator
output=/sdcard/Android/media/$runner
profile=$here/../app/src/main/baselineProfiles/baseline-prof.txt

local_dir=$(mktemp -d ./cribrum-profile.XXXXXX)
local_dir=${local_dir#./}
cleanup() {
    "${adb[@]}" uninstall "$target" >/dev/null 2>&1 || true
    "${adb[@]}" uninstall "$runner" >/dev/null 2>&1 || true
    rm -rf "$local_dir"
}
trap cleanup EXIT

die() {
    echo "generate_profile: $*" >&2
    exit 1
}

# A Windows adb ends lines with \r.
shell() { "${adb[@]}" shell "$@" | tr -d '\r'; }

cp "$here/../app/build/outputs/apk/nonMinifiedRelease/app-nonMinifiedRelease.apk" "$local_dir/app.apk"
cp "$here/../baselineprofile/build/outputs/apk/nonMinifiedRelease/baselineprofile-nonMinifiedRelease.apk" "$local_dir/generator.apk"

[[ $(shell dumpsys window) != *isKeyguardShowing=true* ]] || die "unlock the phone first"
active='DisplayViewport\{[^}]*isActive=true'
[[ $(shell dumpsys display) =~ $active ]] || die "turn the screen on"
# Any Cribrum build, which the generator's start from the launcher would send away.
resumed=$(shell dumpsys activity activities | grep 'ResumedActivity' || true)
[[ -n $resumed ]] || die "no app is in the foreground: turn the screen on"
[[ $resumed != *io.github.bert0ns.cribrum* ]] || die "Cribrum is in the foreground: leave it first"

"${adb[@]}" install -r "$local_dir/app.apk"
"${adb[@]}" install -r -t "$local_dir/generator.apk"
# All files access is what would put real images and the real trash in reach.
package=$(shell dumpsys package "$target")
[[ $package == *"Package [$target]"* ]] || die "$target is not installed"
[[ $package != *MANAGE_EXTERNAL_STORAGE* ]] || die "$target asks for all files access"

# A profile left by an earlier run must not pass for this one.
shell rm -rf "$output"
report=$(shell am instrument -w -e class "$generator" "$runner/androidx.test.runner.AndroidJUnitRunner")
[[ $report == *"OK (1 test)"* ]] || die "the generator failed: $report"
# The name holds the time of the run.
written=$(shell ls "$output")
name=$(grep -m1 -- '-baseline-prof' <<<"$written" || true)
[[ -n $name ]] || die "no profile in $output: $written"
"${adb[@]}" pull -q "$output/$name" "$local_dir/baseline-prof.txt" >/dev/null
[[ -s $local_dir/baseline-prof.txt ]] || die "the generator wrote no profile"
mkdir -p "$(dirname "$profile")"
tr -d '\r' <"$local_dir/baseline-prof.txt" >"$profile"
echo "generate_profile: $(wc -l <"$profile") lines in $profile"
