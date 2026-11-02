#!/bin/bash
# Builds the signed APK and publishes it as a GitHub release named after versionName.
# Phones pick it up within a few hours, or right away from "Check for updates".
set -euo pipefail
cd "$(dirname "$0")"
VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
GRADLE_USER_HOME='D:\Android\.gradle' ./gradlew testDebugUnitTest assembleRelease --console=plain -q
cp app/build/outputs/apk/release/app-release.apk sayang-translate.apk
gh release create "v$VERSION" sayang-translate.apk --title "v$VERSION" --notes "${1:-Sayang Translate $VERSION}"
