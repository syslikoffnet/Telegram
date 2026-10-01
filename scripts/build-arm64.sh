#!/usr/bin/env bash
# Локальная сборка Telegram под arm64-v8a (Poco X6 Pro).
# Требуется: JDK 17, Android SDK (ANDROID_SDK_ROOT), NDK 27.2.12479018, cmake 3.22.1
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD_TYPE="${1:-standalone}"   # standalone | debug
ABI="${ABI:-arm64-v8a}"

case "$BUILD_TYPE" in
  standalone) TASK=":TMessagesProj_AppStandalone:assembleAfatStandalone" ;;
  debug)      TASK=":TMessagesProj_AppStandalone:assembleAfatDebug" ;;
  *) echo "Usage: $0 [standalone|debug]"; exit 1 ;;
esac

./gradlew "$TASK" -PABI_FILTERS="$ABI" --stacktrace
echo
echo "Готовые APK:"
find TMessagesProj_AppStandalone/build/outputs/apk -name '*.apk'
