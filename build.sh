#!/bin/bash
set -e
export JAVA_HOME=/home/adnan/tools/jdk-17.0.20+8
export ANDROID_HOME=/home/adnan/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"

# Signing: app/build.gradle wires signingConfigs.release only when
# <projectRoot>/videodroid.keystore exists. The real keystore lives outside the repo
# (~/.video-droid-signing) so this links it in. Passwords come from that same file via
# env vars — never hardcode them here.
KS_SRC="/home/adnan/.video-droid-signing/videodroid.keystore"
KS_DST="/home/adnan/video-droid/videodroid.keystore"
CREDS="/home/adnan/.video-droid-signing/CREDENTIALS.txt"

if [ -f "$KS_SRC" ]; then
  [ -e "$KS_DST" ] || ln -s "$KS_SRC" "$KS_DST"
  if [ -f "$CREDS" ]; then
    : "${RELEASE_STORE_PASSWORD:=$(grep -oP '^storePassword\s*:\s*\K\S+' "$CREDS" || true)}"
    : "${RELEASE_KEY_PASSWORD:=$(grep -oP '^keyPassword\s*:\s*\K\S+' "$CREDS" || true)}"
    export RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD
  fi
fi

cd /home/adnan/video-droid

# MUST use the wrapper: the Android Gradle Plugin here requires Gradle >= 8.11.1, while
# /home/adnan/tools/gradle-8.9 is too old and fails with
#   "Minimum supported Gradle version is 8.11.1. Current version is 8.9."
# The wrapper is pinned to 8.11.1 in gradle/wrapper/gradle-wrapper.properties.
#
# lintVitalRelease is skipped by default because it is slow and not needed for a local
# build; pass --with-lint to include it.
LINT_SKIP="-x lintVitalRelease"
for a in "$@"; do [ "$a" = "--with-lint" ] && LINT_SKIP=""; done
set -- "${@/--with-lint/}"

exec ./gradlew assembleRelease $LINT_SKIP "$@"
