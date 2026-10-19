#!/usr/bin/env bash
# Capture one screenshot per tab on a booted emulator.
# Must run as a single script: the emulator-runner executes each `script:` line
# in its own `sh -c`, so shell variables do not survive across lines.
set -euo pipefail

adb install -r app/build/outputs/apk/debug/app-debug.apk
mkdir -p screenshots

adb shell settings put secure anr_show_background 0 || true
adb shell am start -W -n com.xerxes.videodroid/.MainActivity || true
sleep 12

SIZE=$(adb shell wm size | sed 's/.*: //' | tr -d '\r')
W=${SIZE%x*}
H=${SIZE#*x}
Y=$((H - H / 14))
echo "screen ${W}x${H}, tapping the bottom bar at y=${Y}"

adb exec-out screencap -p > screenshots/01-download.png

adb shell input tap $((W / 6)) "$Y"; sleep 6
adb exec-out screencap -p > screenshots/02-queue.png

adb shell input tap $((W * 5 / 6)) "$Y"; sleep 6
adb exec-out screencap -p > screenshots/03-library.png

adb shell input tap $((W / 2)) "$Y"; sleep 6
adb exec-out screencap -p > screenshots/04-download-again.png

ls -l screenshots
DISTINCT=$(md5sum screenshots/0[123]-*.png | awk '{print $1}' | sort -u | wc -l)
echo "distinct tab screenshots: ${DISTINCT}"
if [ "$DISTINCT" -lt 3 ]; then
  echo "::error::tab navigation did not change the screen"
  exit 1
fi
