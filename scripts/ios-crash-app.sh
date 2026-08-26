#!/usr/bin/env bash
# Builds and runs the iOS crash application in the simulator, and it exists so B-27 can be met without
# an Xcode project.
#
# WHY NOT A PROJECT. The ordinary way to run Kotlin on a device is a `.framework` plus an Xcode
# project linking it. That is several thousand lines of `.pbxproj` no Kotlin change can keep correct,
# for an application whose entire job is to start a reporter and throw. Kotlin/Native emits a Mach-O
# executable, and a simulator `.app` is a directory with an `Info.plist` and a binary in it — so the
# thing that crashes is built by the same compiler, from the same source set, as the reporter it is
# testing.
#
# Usage:
#   scripts/ios-crash-app.sh                      # against a katcher on localhost:8092
#   KATCHER_ENDPOINT=http://host:8092 scripts/ios-crash-app.sh
set -euo pipefail

cd "$(dirname "$0")/.."

BUNDLE_ID="io.konekt.crash"
DEVICE="${KONEKT_SIMULATOR:-konekt-iphone}"
KATCHER_ENDPOINT="${KATCHER_ENDPOINT:-http://127.0.0.1:8092}"
KATCHER_KEY="${KATCHER_KEY:-konekt-client}"
KONEKT_RELEASE="${KONEKT_RELEASE:-ios-dev}"

# LOCAL=1: an Apple target does not build on the Linux box, which is the one exception the repository's
# build rules name.
LOCAL=1 ./gradlew :client:linkCrashDebugExecutableIosSimulatorArm64 -q

BIN="client/build/bin/iosSimulatorArm64/debugExecutable/KonektCrash.kexe"
[ -f "$BIN" ] || { echo "no executable at $BIN"; exit 1; }

APP="$(mktemp -d)/Konekt.app"
mkdir -p "$APP"
cp "$BIN" "$APP/KonektCrash"

# The minimum a simulator will install and launch. `CFBundleExecutable` has to name the file actually
# in the bundle, and the identifier has to match what `simctl launch` is given — a mismatch in either
# is an install that succeeds and a launch that says the bundle does not exist.
cat > "$APP/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>KonektCrash</string>
  <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
  <key>CFBundleName</key><string>Konekt</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSRequiresIPhoneOS</key><true/>
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
  <key>MinimumOSVersion</key><string>15.0</string>
</dict>
</plist>
PLIST

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true

# NO `uninstall` BEFORE `install`, and putting one there is how this took an afternoon. Uninstalling
# deletes the app's data container, and the data container is where katcher saves a crash report for
# the NEXT launch to upload — which is how every crash reporter works, because the process that
# crashed cannot send anything. So a script that uninstalled each time guaranteed the report was never
# delivered while printing "Worker woke up. Checking disk..." to say it had looked.
#
# `install` over an existing bundle replaces the binary and keeps the container, which is what is
# wanted. Wiping deliberately is `simctl uninstall` by hand.
xcrun simctl install "$DEVICE" "$APP"

echo "launching against $KATCHER_ENDPOINT as release '$KONEKT_RELEASE'"
# `SIMCTL_CHILD_` IS NOT DECORATION. `simctl launch` treats trailing `NAME=value` arguments as
# arguments to the process, not as environment; the only way to set a variable the app can read is a
# `SIMCTL_CHILD_`-prefixed variable in simctl's OWN environment. Without the prefix the app starts
# with none of these set — which is exactly what happened the first time, and the reporter's refusal
# is what said so rather than a silent run against nothing.
#
# `--console` so the app's own output and its crash are visible here rather than only in the device
# log. The launch exits non-zero when the app throws, which is the expected outcome and not a failure
# of this script.
SIMCTL_CHILD_KATCHER_ENDPOINT="$KATCHER_ENDPOINT" \
SIMCTL_CHILD_KATCHER_KEY="$KATCHER_KEY" \
SIMCTL_CHILD_KONEKT_RELEASE="$KONEKT_RELEASE" \
SIMCTL_CHILD_KONEKT_ENVIRONMENT="${KONEKT_ENVIRONMENT:-simulator}" \
SIMCTL_CHILD_KONEKT_UPLOAD_WAIT_MS="${KONEKT_UPLOAD_WAIT_MS:-}" \
xcrun simctl launch --console \
  --terminate-running-process "$DEVICE" "$BUNDLE_ID" || true

echo "crashed; katcher has a second or two to receive it"
