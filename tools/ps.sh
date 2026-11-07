#!/usr/bin/env bash
# Peg Solitaire dev harness (macOS). Pinned to one emulator: DEV (borrowed with the factory's emu_borrow.py).
#   tools/ps.sh cmd NAME [ARG]     debug broadcast:  am broadcast -a io.embodit.pegsolitaire.DEBUG --es cmd NAME --es a ARG
#   tools/ps.sh shot OUT.png       still capture of the device (pico-cli capture screenshot)
#   tools/ps.sh log [REGEX]        logcat lines of tag PegSolitaire (optionally filtered)
#   tools/ps.sh install            install the debug APK and start the app
set -u
DEV="${DEV:-emulator-5564}"
PKG=io.embodit.pegsolitaire
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  shot) pico-cli capture screenshot --device "$DEV" --out "$2" --replace >/dev/null ;;
  log) "$ADB" -s "$DEV" logcat -d -s PegSolitaire:V | grep -E "${2:-.}" ;;
  install) "$ADB" -s "$DEV" install -r -d "$HERE/../app/build/outputs/apk/debug/app-debug.apk" && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null ;;
  *) sed -n 2,7p "$0" ;;
esac
