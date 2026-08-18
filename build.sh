#!/bin/bash
set -e
export JAVA_HOME=/home/adnan/tools/jdk-17.0.20+8
export ANDROID_HOME=/home/adnan/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd /home/adnan/video-droid
exec /home/adnan/tools/gradle-8.9/bin/gradle assembleRelease "$@"
