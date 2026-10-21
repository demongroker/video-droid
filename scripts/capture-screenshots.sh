#!/usr/bin/env bash
# Experimental: one screenshot per screen on a booted emulator.
#
# These runners have no /dev/kvm, so the AVD is software-rendered and
# system_server often raises "Process system isn't responding" over the app.
# We therefore verify that OUR activity actually holds focus before accepting a
# frame, and fail honestly instead of shipping a picture of a dialog.
set -euo pipefail

adb install -r app/build/outputs/apk/debug/app-debug.apk
mkdir -p screenshots
adb shell settings put secure anr_show_background 0 || true

# Our own first-run battery prompt (RequestIgnoreBatteryOptimizations) steals
# focus and got MainActivity rejected. Whitelist the app so it never appears.
adb shell dumpsys deviceidle whitelist +com.xerxes.videodroid >/dev/null 2>&1 || true
sleep 20

shoot () {
  local activity="$1" out="$2"
  adb shell am force-stop com.xerxes.videodroid || true
  sleep 3
  adb shell am start -W -n "com.xerxes.videodroid/.${activity}" || true
  sleep 15

  local focus
  focus=$(adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' || true)
  echo "focus: ${focus}"

  # Only press BACK when something else really is on top - pressing it
  # unconditionally closes our own activity back to the launcher.
  if ! echo "${focus}" | grep -q 'com.xerxes.videodroid'; then
    echo "covered by another window, dismissing"
    adb shell input keyevent KEYCODE_BACK || true
    sleep 3
    adb shell am start -W -n "com.xerxes.videodroid/.${activity}" || true
    sleep 6
    focus=$(adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' || true)
    echo "focus after dismiss: ${focus}"
  fi

  if ! echo "${focus}" | grep -q 'com.xerxes.videodroid'; then
    echo "::error::${activity} does not hold focus - a system dialog is covering it"
    return 1
  fi

  adb exec-out screencap -p > "screenshots/${out}"
  echo "captured ${out} ($(stat -c%s "screenshots/${out}") bytes)"
}

fail=0
shoot MainActivity    01-download.png || fail=1
shoot QueueActivity   02-queue.png   || fail=1
shoot LibraryActivity 03-library.png || fail=1

ls -l screenshots || true
if [ "$fail" -ne 0 ]; then
  echo "::warning::emulator screenshots unavailable (no KVM) - artifacts are incomplete"
  exit 1
fi

DISTINCT=$(md5sum screenshots/*.png | awk '{print $1}' | sort -u | wc -l)
echo "distinct screenshots: ${DISTINCT}"
if [ "$DISTINCT" -lt 3 ]; then
  echo "::error::screens are identical - capture did not work"
  exit 1
fi
