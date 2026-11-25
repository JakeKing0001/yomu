#!/bin/bash
# Builds Yomu for phones and publishes it as a GitHub release, which installed copies pick up and install
# by themselves. The version code is the minutes since 2025, so every build is newer than the last.
set -euo pipefail
cd "$(dirname "$0")/.."

repository="JakeKing0001/yomu"
code=$(( ($(date +%s) - 1735689600) / 60 ))
apk="app/build/outputs/apk/release/app-arm64-v8a-release.apk"
notes="${1:-Aggiornamento di Yomu.}"

./gradlew :app:assembleRelease -PyomuVersionCode="$code" --console=plain -q
cp "$apk" "build/yomu-arm64-v8a.apk"
gh release create "android-v$code" "build/yomu-arm64-v8a.apk" \
    --repo "$repository" \
    --target main \
    --title "Yomu per Android ($code)" \
    --notes "$notes" \
    --latest=false
echo "Published android-v$code"
