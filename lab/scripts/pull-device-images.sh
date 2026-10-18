#!/usr/bin/env bash
# Copies the image folders of the connected Android device into lab/data/device/,
# keeping the device's folder layout and modification times (docs/design.md, section 10).
# Usage: lab/scripts/pull-device-images.sh
set -euo pipefail

dest="$(cd "$(dirname "$0")/.." && pwd)/data/device"
whatsapp=Android/media/com.whatsapp/WhatsApp/Media
folders=(
  DCIM
  Pictures
  Download
  Documents
  Telegram
  Android/media/org.telegram.messenger
  "$whatsapp/WhatsApp Images"  # Sent/ and Private/ have .nomedia: the gallery never shows them
  "$whatsapp/WhatsApp Stickers"
  "$whatsapp/WhatsApp Documents"
  "$whatsapp/.Statuses"
)

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
