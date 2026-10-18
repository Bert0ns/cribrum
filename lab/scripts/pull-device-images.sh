#!/usr/bin/env bash
# Copies the images of the connected Android device into lab/data/device/,
# keeping the device's folder layout and modification times (docs/design.md, section 10).
# Videos, audio and other files stay on the device.
# Usage: lab/scripts/pull-device-images.sh [FOLDER...]
#   FOLDER is relative to /sdcard; without any, the default folders below are copied.
set -euo pipefail

dest="$(cd "$(dirname "$0")/.." && pwd)/data/device"
folders=(DCIM Pictures Download Documents Screenshots)
# Messaging apps moved under Android/media/ with Android 11; upgraded phones may still use the old place.
# WhatsApp's Sent/ and Private/ folders have .nomedia: the gallery never shows them.
folders+=(WhatsApp/Media Android/media/com.whatsapp/WhatsApp/Media)
folders+=(Telegram Android/media/org.telegram.messenger/Telegram)
folders+=(Android/media/com.instagram.android)
if (($#)); then
  folders=("$@")
fi

adb get-state >/dev/null  # fails early when no device is connected

found=()
for folder in "${folders[@]}"; do
  if adb shell "test -d '/sdcard/$folder'"; then
    found+=("'$folder'")
  else
    echo "skip: /sdcard/$folder not found"
  fi
done
((${#found[@]})) || exit 0

# One tar stream instead of one adb pull per file. Gallery thumbnail caches are not photos.
# exec-out mixes stderr into the stream, so the device side must stay silent.
images="-iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' -o -iname '*.gif' -o -iname '*.heic' -o -iname '*.heif'"
mkdir -p "$dest"
adb exec-out "cd /sdcard && find ${found[*]} -type d -name .thumbnails -prune -o -type f \( $images \) -print 2>/dev/null | tar -cf - -T - 2>/dev/null" \
  | tar -xf - -C "$dest"

echo "Images copied to $dest"
