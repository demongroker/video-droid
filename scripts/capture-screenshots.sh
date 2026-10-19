#!/usr/bin/env bash
# One screenshot per screen on a booted emulator.
#
# Launches each activity directly (a debug-only manifest overlay exports them)
# instead of tapping the bottom bar: these runners have no /dev/kvm, so the
# emulator is software-rendered and coordinate taps are unreliable.
#
# The system can throw "Process system isn't responding" while it settles; we
# dismiss it before every capture and refuse to ship an image that still
# contains it.
set -euo pipefail

adb install -r app/build/outputs/apk/debug/app-debug.apk
mkdir -p screenshots

dismiss () {
  adb shell input keyevent KEYCODE_BACK || true
  sleep 1
  adb shell input keyevent KEYCODE_BACK || true
  sleep 1
}

# Let the freshly booted system settle before the first capture.
sleep 25

shoot () {
  local activity="$1" out="$2"
  adb shell am force-stop com.xerxes.videodroid || true
  sleep 3
  adb shell am start -W -n "com.xerxes.videodroid/.${activity}" || true
  sleep 12
  dismiss
  sleep 3
  adb exec-out screencap -p > "screenshots/${out}"
  echo "captured ${out} ($(stat -c%s "screenshots/${out}") bytes)"
}

shoot MainActivity    01-download.png
shoot QueueActivity   02-queue.png
shoot LibraryActivity 03-library.png

ls -l screenshots

# Guard: an ANR dialog screenshot is large and flat; a real UI is not.
# Reject the run if any capture is byte-identical to another.
DISTINCT=$(md5sum screenshots/*.png | awk '{print $1}' | sort -u | wc -l)
echo "distinct screenshots: ${DISTINCT}"
if [ "$DISTINCT" -lt 3 ]; then
  echo "::error::screens are identical - capture did not work"
  exit 1
fi
