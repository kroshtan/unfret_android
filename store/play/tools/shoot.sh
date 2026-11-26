#!/usr/bin/env bash
# The store screenshots, inside the Android SDK container (run by store/play/screenshots.sh, which says how): the
# play build, then the direct download's release build, on an Android 13 emulator (1080×1920, 420 dpi), talking to
# tools/mock_server.py as unfret.net. Writes the raw captures to $OUT (play-*.png, direct-*.png);
# store/play/graphics.mjs turns them into the store's PNGs.
#
# Expects: /dev/kvm, the checkout at /work, the signed play APK at $APK and the signed release APK at $DIRECT_APK,
# HOME=/work/.home.
set -euo pipefail
cd /work
OUT=${OUT:-.home/play-raw}
APK=${APK:-.home/play-shots.apk}
DIRECT_APK=${DIRECT_APK:-.home/direct-shots.apk}
STATE=.home/play-shots
export ANDROID_SDK_ROOT=/work/.home/sdk ANDROID_HOME=/work/.home/sdk ANDROID_AVD_HOME=$STATE/avd
export ANDROID_USER_HOME=/work/.home/.android
IMAGE="system-images;android-33;default;x86_64"
PAUSED=$STATE/paused
API_LOG=$STATE/api.log
UI="python3 store/play/tools/ui.py"
# The made-up shops (mock_server.py): the Unfret test shop at a made-up address, and three plain product pages.
SHOP_URL="https://nebulora-home.example/products/galaxy-star-projector"
LEVEL0_URL="https://lumen-and-oak.example/products/walnut-desk-lamp"
LEVEL2_URL="https://restwell-home.example/products/ergonomic-office-chair"
LEVEL3_URL="https://zentrova-audio.example/products/x3-wireless-earbuds"
HOSTS="unfret.net nebulora-home.example lumen-and-oak.example restwell-home.example zentrova-audio.example"
mkdir -p "$OUT" "$STATE/avd/shots.avd" "$ANDROID_SDK_ROOT"

# The system image (an image without Google Play, so its system partition can be written), once, into the
# git-ignored .home/sdk, beside the container's own emulator and adb.
if [[ ! -f $ANDROID_SDK_ROOT/system-images/android-33/default/x86_64/system.img ]]; then
  cp -r /opt/android-sdk-linux/licenses "$ANDROID_SDK_ROOT/"
  yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" "$IMAGE" emulator > /dev/null
fi
ln -sfn /opt/android-sdk-linux/platform-tools "$ANDROID_SDK_ROOT/platform-tools"

cat > "$STATE/avd/shots.ini" <<EOF
avd.ini.encoding=UTF-8
path=/work/$STATE/avd/shots.avd
target=android-33
EOF
cat > "$STATE/avd/shots.avd/config.ini" <<EOF
AvdId=shots
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=3072
disk.dataPartition.size=4G
image.sysdir.1=system-images/android-33/default/x86_64/
tag.id=default
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.mainKeys=no
showDeviceFrame=no
EOF

# A throwaway CA, and a certificate for unfret.net and the made-up shops' hosts signed by it. Nothing is kept: a new
# pair each run, in the container only.
tls=$(mktemp -d)
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$tls/ca.key" -out "$tls/ca.pem" -days 2 \
  -subj "/CN=Unfret store screenshots (throwaway)" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2> /dev/null
openssl req -newkey rsa:2048 -nodes -keyout "$tls/server.key" -out "$tls/server.csr" -subj "/CN=unfret.net" 2> /dev/null
printf "subjectAltName=%s\nextendedKeyUsage=serverAuth\n" "$(printf 'DNS:%s,' $HOSTS | sed 's/,$//')" > "$tls/ext.cnf"
openssl x509 -req -in "$tls/server.csr" -CA "$tls/ca.pem" -CAkey "$tls/ca.key" -CAcreateserial \
  -out "$tls/server.pem" -days 2 -extfile "$tls/ext.cnf" 2> /dev/null
ca_name="$(openssl x509 -in "$tls/ca.pem" -noout -subject_hash_old).0"

rm -f "$PAUSED" "$API_LOG"
python3 store/play/tools/mock_server.py "$tls/server.pem" "$tls/server.key" store/play/tools/fixtures "$PAUSED" "$API_LOG" \
  > "$STATE/mock.log" 2>&1 &
mock=$!

# A run that was stopped leaves the AVD's locks behind, and the emulator then refuses to start.
find "$STATE/avd" -name "*.lock" -exec rm -rf {} +
emulator -avd shots -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -writable-system \
  -gpu swiftshader_indirect > "$STATE/emulator.log" 2>&1 &
emu=$!
trap 'adb emu kill > /dev/null 2>&1 || true; kill $mock $emu 2> /dev/null || true; rm -rf "$tls"' EXIT

booted() {
  timeout 300 adb wait-for-device || { echo "no emulator; see $STATE/emulator.log" >&2; exit 1; }
  for _ in $(seq 150); do
    [[ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" == 1 ]] && return 0
    sleep 2
  done
  echo "the emulator didn't boot; see $STATE/emulator.log" >&2
  exit 1
}

# The CA into the system's store, and every host to the mock (10.0.2.2: the container, from the emulator).
booted
adb root > /dev/null
sleep 2
adb remount > /dev/null 2>&1 || true
adb reboot
booted
adb root > /dev/null
sleep 2
adb remount > /dev/null
adb push "$tls/ca.pem" "/system/etc/security/cacerts/$ca_name" > /dev/null
adb shell chmod 644 "/system/etc/security/cacerts/$ca_name"
for host in $HOSTS; do adb shell "echo '10.0.2.2 $host' >> /system/etc/hosts"; done

# A tidy status bar (10:00, full battery, Wi-Fi only, no notifications) and gesture navigation.
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 1000
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile hide
demo notifications -e visible false

shot() { sleep "${2:-1.5}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
# A Play screenshot: nothing on screen may be about donating (Google Play's payments policy).
play_shot() { sleep "${2:-1.5}"; $UI nodonate; shot "play-$1" 0; }

# A link shared to Unfret, as from a browser or a marketplace app: the page opens inside the app (a fresh start).
share() {
  adb shell am start -S -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$1" \
    -n net.unfret.android/.CheckActivity > /dev/null
}

# A free deep check of a shared page: the proof of work (made easy by the mock), then the result.
deep_check() {
  share "$1"
  $UI wait "Check this page" 60
  sleep 3
  $UI tap "=Check this page"
  $UI wait "$2" 60
}

# ── The Play build ────────────────────────────────────────────────────────────────────────────────────────────
adb install -r "$APK" > /dev/null
adb shell monkey -p net.unfret.android -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1

# First start: the intro; then the home screen, free checks with no account.
$UI tap "I agree" 30
$UI wait "Checks are free" 30
adb shell input swipe 540 600 540 1700 300
play_shot home

# The test shop shared to Unfret: the page opens inside the app.
share "$SHOP_URL"
$UI wait "Check this page" 60
play_shot shared-page 5

# Its free deep check: level 4, and every finding behind it.
$UI tap "=Check this page"
$UI wait "May not arrive" 60
play_shot level-4 3
$UI top "Why this level" 250
play_shot level-4-why

# A level 3 result, and "Wrong result?" on it: a level picked and a finding marked wrong.
deep_check "$LEVEL3_URL" "May not work as described"
play_shot level-3 2
$UI tap "Wrong result?"
$UI tap "2 · May cost more"
$UI tap "Hard-to-believe claims"
play_shot wrong-result

# A level 2 and a level 0 result.
deep_check "$LEVEL2_URL" "May cost more than"
play_shot level-2 2
deep_check "$LEVEL0_URL" "No concerns found"
play_shot level-0 2

# Every API request of the Play build said X-Unfret-Donate: off.
if grep -q "donate=missing" "$API_LOG"; then
  grep "donate=missing" "$API_LOG" >&2
  echo "the play build sent a request without X-Unfret-Donate: off" >&2
  exit 1
fi

# ── The direct download (release build): free deep checks paused, with its "Support Unfret" ────────────────────
# Not for Google Play: its pause line and link are about donating. For unfret.net/android and the press.
adb install -r "$DIRECT_APK" > /dev/null
touch "$PAUSED"
share "$LEVEL2_URL"
$UI wait "deep checks are paused" 60
shot direct-paused 5
