#!/usr/bin/env bash
# One screenshot per screen on a booted emulator.
#
# Launches each activity directly (the debug manifest makes them exported)
# instead of tapping the bottom bar: GitHub runners here have no /dev/kvm, so
# the emulator runs under software rendering and is too slow/unstable for
# coordinate taps.
set -euo pipefail

adb install -r app/build/outputs/apk/debug/app-debug.apk
mkdir -p screenshots
adb shell settings put secure anr_show_background 0 || true

shoot () {
  local activity="$1" out="$2"
  adb shell am force-stop com.xerxes.videodroid || true
  sleep 2
  adb shell am start -W -n "com.xerxes.videodroid/.${activity}" || true
  sleep 10
  adb exec-out screencap -p > "screenshots/${out}"
  echo "captured ${out} ($(stat -c%s "screenshots/${out}") bytes)"
}

shoot MainActivity    01-download.png
shoot QueueActivity   02-queue.png
shoot LibraryActivity 03-library.png

ls -l screenshots
DISTINCT=$(md5sum screenshots/*.png | awk '{print $1}' | sort -u | wc -l)
echo "distinct screenshots: ${DISTINCT}"
if [ "$DISTINCT" -lt 3 ]; then
  echo "::error::screens are identical - capture did not work"
  exit 1
fi
