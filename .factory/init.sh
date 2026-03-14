#!/bin/sh
set -e

ROOT="/Users/ratulsarna/Developer/Projects/opencode-pocket"

cd "$ROOT"
chmod +x ./gradlew

# Idempotent sanity checks for worker sessions.
./gradlew --version >/dev/null
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -showBuildSettings >/dev/null
