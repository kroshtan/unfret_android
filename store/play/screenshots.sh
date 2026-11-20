#!/usr/bin/env bash
# The Google Play listing's images (store/play/README.md): builds the play build, takes the phone screenshots on an
# emulator (tools/shoot.sh, in the Android SDK container the Makefile pins), then renders the icon, the feature
# graphic and the final screenshots (graphics.mjs). Run from anywhere: `store/play/screenshots.sh`, or `make
# play-store-images`.
#
# Needs docker, /dev/kvm (the emulator), node and Playwright's Chromium on the host (`npm ci` in store/play, then
# `npx playwright install chromium`). The first run downloads an Android 13 system image (about 1 GB) into .home/sdk.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
image="$(sed -n 's/^ANDROID_IMAGE := //p' Makefile)"
kvm_group="$(stat -c %g /dev/kvm)"
run=(docker run --rm -u "$(id -u):$(id -g)" -e HOME=/work/.home -e GRADLE_USER_HOME=/work/.gradle-home
  -e ANDROID_USER_HOME=/work/.home/.android -v "$root:/work" -w /work)

# The play build, unsigned, as `bundlePlay` builds it for Play, signed with a throwaway key so the emulator installs
# it (the key is made here, kept in the git-ignored .home, and signs nothing else).
"${run[@]}" "$image" ./gradlew --console=plain assemblePlay
"${run[@]}" "$image" bash -c '
  set -euo pipefail
  bt=/opt/android-sdk-linux/build-tools/36.0.0
  ks=.home/play-shots.keystore
  [[ -f $ks ]] || keytool -genkeypair -keystore $ks -storepass screenshots -keypass screenshots -alias shots \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Unfret store screenshots only" > /dev/null 2>&1
  $bt/zipalign -f 4 app/build/outputs/apk/play/app-play-unsigned.apk .home/play-aligned.apk
  $bt/apksigner sign --ks $ks --ks-pass pass:screenshots --out .home/play-shots.apk .home/play-aligned.apk'

rm -rf .home/play-raw
"${run[@]}" --device /dev/kvm --group-add "$kvm_group" --sysctl net.ipv4.ip_unprivileged_port_start=0 \
  "$image" bash store/play/tools/shoot.sh

node store/play/graphics.mjs .home/play-raw
