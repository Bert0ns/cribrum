#!/usr/bin/env bash
# Copies the image folders of the connected Android device into lab/data/device/,
# keeping the device's folder layout and modification times (docs/design.md, section 10).
# Usage: lab/scripts/pull-device-images.sh
set -euo pipefail

dest="$(cd "$(dirname "$0")/.." && pwd)/data/device"
folders=(DCIM Pictures Download Documents Screenshots)
# Messaging apps moved under Android/media/ with Android 11; upgraded phones may still use the old place.
for whatsapp in WhatsApp/Media Android/media/com.whatsapp/WhatsApp/Media; do
  folders+=(
    "$whatsapp/WhatsApp Images"  # Sent/ and Private/ have .nomedia: the gallery never shows them
    "$whatsapp/WhatsApp Stickers"
    "$whatsapp/WhatsApp Documents"
    "$whatsapp/.Statuses"
  )
done
for telegram in Telegram Android/media/org.telegram.messenger/Telegram; do
  folders+=("$telegram/Telegram Images" "$telegram/Telegram Documents")
done

adb get-state >/dev/null  # fails early when no device is connected

for folder in "${folders[@]}"; do
  if ! adb shell "test -d '/sdcard/$folder'"; then
    echo "skip: /sdcard/$folder not found"
    continue
  fi
  parent="$dest/$(dirname "$folder")"
  mkdir -p "$parent"
  adb pull -a "/sdcard/$folder" "$parent"
done

echo "Images copied to $dest"
