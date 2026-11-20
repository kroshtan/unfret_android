#!/usr/bin/env bash
# The Play store screenshots, inside the Android SDK container (run by store/play/screenshots.sh, which says how):
# the play build on an Android 13 emulator (1080×1920, 420 dpi), talking to tools/mock_server.py as unfret.net.
# Writes the raw captures to $OUT; store/play/graphics.mjs turns them into the store's PNGs.
#
# Expects: /dev/kvm, the checkout at /work, the signed play APK at $APK, HOME=/work/.home.
set -euo pipefail
cd /work
OUT=${OUT:-.home/play-raw}
APK=${APK:-.home/play-shots.apk}
STATE=.home/play-shots
export ANDROID_SDK_ROOT=/work/.home/sdk ANDROID_HOME=/work/.home/sdk ANDROID_AVD_HOME=$STATE/avd
export ANDROID_USER_HOME=/work/.home/.android
IMAGE="system-images;android-33;default;x86_64"
HOLD=$STATE/hold
UI="python3 store/play/tools/ui.py"
SHOP_URL="https://nebulora-home.example/products/galaxy-star-projector"
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

# A throwaway CA, and a certificate for unfret.net and the made-up shop's host signed by it. Nothing is kept: a new
# pair each run, in the container only.
tls=$(mktemp -d)
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$tls/ca.key" -out "$tls/ca.pem" -days 2 \
  -subj "/CN=Unfret store screenshots (throwaway)" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2> /dev/null
openssl req -newkey rsa:2048 -nodes -keyout "$tls/server.key" -out "$tls/server.csr" -subj "/CN=unfret.net" 2> /dev/null
printf "subjectAltName=DNS:unfret.net,DNS:nebulora-home.example\nextendedKeyUsage=serverAuth\n" > "$tls/ext.cnf"
openssl x509 -req -in "$tls/server.csr" -CA "$tls/ca.pem" -CAkey "$tls/ca.key" -CAcreateserial \
  -out "$tls/server.pem" -days 2 -extfile "$tls/ext.cnf" 2> /dev/null
ca_name="$(openssl x509 -in "$tls/ca.pem" -noout -subject_hash_old).0"

rm -f "$HOLD"
python3 store/play/tools/mock_server.py "$tls/server.pem" "$tls/server.key" store/play/tools/fixtures "$HOLD" \
  > "$STATE/mock.log" 2>&1 &
mock=$!

emulator -avd shots -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -writable-system \
  -gpu swiftshader_indirect > "$STATE/emulator.log" 2>&1 &
emu=$!
trap 'adb emu kill > /dev/null 2>&1 || true; kill $mock $emu 2> /dev/null || true; rm -rf "$tls"' EXIT

booted() {
  adb wait-for-device
  for _ in $(seq 150); do
    [[ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" == 1 ]] && return 0
    sleep 2
  done
  echo "the emulator didn't boot; see $STATE/emulator.log" >&2
  exit 1
}

# The CA into the system's store, and both hosts to the mock (10.0.2.2: the container, from the emulator).
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
adb shell "printf '10.0.2.2 unfret.net\n10.0.2.2 nebulora-home.example\n' >> /system/etc/hosts"

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

adb install -r "$APK" > /dev/null
adb shell monkey -p net.unfret.android -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1

# First start: the intro, then the app makes its own pass with 5 free checks (from the mock).
$UI tap "I agree" 30
$UI tap "Got it" 60
adb shell input swipe 540 600 540 1700 300
shot home

# A link shared to Unfret, as from a browser or a marketplace app: the page opens inside the app.
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$SHOP_URL" \
  -n net.unfret.android/.CheckActivity > /dev/null
$UI wait "Quick check" 60
shot shared-page 5

# The free quick check.
$UI tap "Quick check"
$UI wait "Think twice" 30
shot quick-result 2

# From the quick result, a deep check of the same page: held mid-way for the screenshot.
touch "$HOLD"
$UI tap "Run a deep check"
$UI wait "8 steps done" 30
shot check-running 1
rm "$HOLD"
$UI wait "Think twice" 30
shot deep-result 3
$UI top "What we noticed" 250
shot deep-signals
$UI top "What we checked" 250
shot deep-checklist
