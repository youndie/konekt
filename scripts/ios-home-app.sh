#!/usr/bin/env bash
# Builds and runs the iOS home application in the simulator: the composition root of B-43 on the
# platform the product is actually for.
#
# It is the same `KonektApp` the desktop runner opens, with twelve lines of UIKit under it — no Xcode
# project, for the reason `ios-crash-app.sh` gives. See `HomeEntryPoint.kt`.
#
# The simulator's `127.0.0.1` is the HOST's loopback, so a stand on this machine needs no address. A
# stand elsewhere needs a tunnel and `KONEKT_URL`.
#
# Usage:
#   scripts/ios-home-app.sh
#   KONEKT_URL=http://127.0.0.1:18080 scripts/ios-home-app.sh
set -euo pipefail

cd "$(dirname "$0")/.."

BUNDLE_ID="io.konekt.home"
DEVICE="${KONEKT_SIMULATOR:-konekt-iphone}"
KONEKT_URL="${KONEKT_URL:-http://127.0.0.1:8080}"

# LOCAL=1: an Apple target does not build on the Linux box, the one exception the build rules name.
LOCAL=1 ./gradlew :client:linkHomeDebugExecutableIosSimulatorArm64 -q

BIN="client/build/bin/iosSimulatorArm64/homeDebugExecutable/KonektHome.kexe"
[ -f "$BIN" ] || { echo "no executable at $BIN"; exit 1; }

APP="$(mktemp -d)/KonektHome.app"
mkdir -p "$APP"
cp "$BIN" "$APP/KonektHome"

cat > "$APP/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>KonektHome</string>
  <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
  <key>CFBundleName</key><string>konekt</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSRequiresIPhoneOS</key><true/>
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
  <key>MinimumOSVersion</key><string>15.0</string>
  <!-- Compose Multiplatform REFUSES TO START WITHOUT THIS, and says so by name: without the key it
       throws a sanity check on the main queue, the window never appears, and the app sits alive on
       the springboard with nothing on screen. Xcode's templates carry it, so a hand-written bundle
       is the one place it goes missing. It disables the 60fps cap on high-refresh iPhones. -->
  <key>CADisableMinimumFrameDurationOnPhone</key><true/>
  <!-- WITHOUT THIS THE APPLICATION IS LETTERBOXED, and that is not a cosmetic loss.
       A bundle with no launch screen tells iOS it was built for a legacy screen, so the system runs
       it in a compatibility canvas: black bands above and below, rounded corners, and a window
       smaller than the display. The brand's ground then reaches the edges of that canvas and not the
       edges of the screen — two strips of every screen a served kit cannot repaint, which is the one
       thing this product's white-label claim is about (\`B-94\`).
       It took three measurements to find. The bands survived turning the frame's inset padding on and
       off, and painting the UIWindow magenta did not tint them — because they are drawn by the SYSTEM
       outside the app's window, not by the app.
       An EMPTY dictionary is the whole declaration: it says "this app supports whatever screen it is
       given" and nothing about what to draw while launching. -->
  <key>UILaunchScreen</key><dict/>
  <!-- THE SCENE MANIFEST, and it is why this bundle has a status bar at all.
       Without it the app runs on the legacy app-delegate lifecycle, and SpringBoard composites no
       status bar for it: no clock, no signal, no battery, on every screen. Nothing about the app says
       so — instrumenting the delegate answered \`windowScene=true\`, \`connectedScenes=1\`,
       \`appStatusBarHidden=false\`, \`vcPrefersHidden=false\`. The app believed it had a visible bar and
       the system drew none, which is why four other fixes were tried first and none of them was it
       (\`B-95\`): the plist's \`UIStatusBarStyle\` is read through a property modern iOS ignores, and a
       container view controller answering \`preferredStatusBarStyle\` changed nothing either.
       NO SCENE DELEGATE AND NO CONFIGURATIONS. Declaring the manifest is the whole of it: UIKit
       creates a default scene, the delegate's own window is adopted, and the entry point is
       unchanged. A delegate class would have to be named here as a string, which means pinning
       Kotlin/Native's Objective-C name for it in a plist — a coupling worth avoiding for nothing.
       THE BAR'S CONTENT FOLLOWS THE SYSTEM APPEARANCE, not the served kit: \`default\` resolves to dark
       content in light mode and light in dark. This build's palette is a client setting
       (\`KONEKT_DARK\`) and the two can therefore disagree — a real limitation, and not one worth a
       static override, which is what was already tried and removed. -->
  <key>UIApplicationSceneManifest</key>
  <dict><key>UIApplicationSupportsMultipleScenes</key><false/></dict>
  <!-- A simulator talking to a stand over plain HTTP. A shipped application would not carry this,
       and a stand that made it unnecessary would be a stand with TLS nobody asked for. -->
  <key>NSAppTransportSecurity</key>
  <dict><key>NSAllowsArbitraryLoads</key><true/></dict>
</dict>
</plist>
PLIST

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true

xcrun simctl install "$DEVICE" "$APP"

echo "launching against $KONEKT_URL"
SIMCTL_CHILD_KONEKT_URL="$KONEKT_URL" \
SIMCTL_CHILD_TRACY_ENDPOINT="${TRACY_ENDPOINT:-}" \
SIMCTL_CHILD_TRACY_KEY="${TRACY_KEY:-}" \
SIMCTL_CHILD_KONEKT_RELEASE="${KONEKT_RELEASE:-ios-dev}" \
xcrun simctl launch --terminate-running-process "$DEVICE" "$BUNDLE_ID"
