#!/usr/bin/env bash
# Sign the unsigned Google Play app bundle (AAB, the `play` build type) with Unfret's Google Play upload key, check it, and write
#   <out>/unfret-<version>.aab, and its line in <out>/SHA256SUMS (added next to the APK's, which
#   scripts/sign-release.sh writes first).
#
#   scripts/sign-bundle.sh app/build/outputs/bundle/play/app-play.aab dist/release
#
# The bundle is for Google Play only, never a direct install: Play checks the upload key's signature, then signs the
# APKs it serves with the app signing key, which is Unfret's release key (docs/RELEASING.md, "Google Play"). So this
# signs with the upload key and never the release key, and refuses a bundle signed with the release key's certificate.
#
# Runs in the Makefile's Android image (aapt2 from build-tools 36.0.0, the JDK's jarsigner and keytool, and python3);
# the "Release APK" workflow runs it. Before signing it checks the bundle is the release build of this version
# (package, versionName and versionCode, not debuggable) and asks for no permission beyond scripts/allowed-permissions,
# as the APK's check does. jarsigner signs it (SHA256withRSA; Play asks for an RSA upload key) and `jarsigner -verify`
# must pass with one signer. It prints the upload certificate's SHA-256, which Play Console shows as the upload key
# certificate; never the key.
#
# The key comes from the environment: ANDROID_UPLOAD_KEYSTORE_B64 (the keystore, base64) or
# ANDROID_UPLOAD_KEYSTORE_FILE (its path), ANDROID_UPLOAD_KEYSTORE_PASSWORD, ANDROID_UPLOAD_KEY_ALIAS and
# ANDROID_UPLOAD_KEY_PASSWORD. The passwords reach jarsigner as `:env` references, never on a command line; a keystore
# written from ANDROID_UPLOAD_KEYSTORE_B64 lives in a private temporary folder that is deleted on exit.
set -euo pipefail

unsigned="${1:?usage: sign-bundle.sh <unsigned.aab> <out dir>}"
out="${2:?usage: sign-bundle.sh <unsigned.aab> <out dir>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
bt="${ANDROID_HOME:?ANDROID_HOME is not set (run this in the Android image)}/build-tools/36.0.0"

fail() { echo "::error::$*" >&2; exit 1; }

mapfile -t allowed < <(grep -v -e '^#' -e '^$' "$root/scripts/allowed-permissions")
version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$root/app/build.gradle.kts")"
version_code="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$root/app/build.gradle.kts")"
# The same pattern as scripts/sign-release.sh and the release workflow.
release_version='^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}(-[0-9A-Za-z]{1,16}(\.[0-9A-Za-z]{1,16}){0,3})?$'
[[ "$version" =~ $release_version ]] || fail "versionName '$version' is not a release version (0.1.0, 0.1.0-beta.1)"
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "versionCode '$version_code' is not a number"
release_cert="$(tr -d '[:space:]' < "$root/docs/release-cert.sha256")"
[[ "$release_cert" =~ ^[0-9a-f]{64}$ ]] || fail "docs/release-cert.sha256 does not hold a SHA-256 digest"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"

# What is being signed. aapt2 reads a bundle's base module once it is laid out as an APK in proto format (manifest at
# the top, resources.pb next to it), which is how bundletool builds APKs from it.
python3 -I - "$unsigned" "$work/base.apk" <<'PY' || fail "$unsigned is not an unsigned app bundle"
import sys, zipfile

with zipfile.ZipFile(sys.argv[1]) as bundle, zipfile.ZipFile(sys.argv[2], "w") as base:
    names = bundle.namelist()
    if "BundleConfig.pb" not in names or "base/manifest/AndroidManifest.xml" not in names:
        sys.exit(f"{sys.argv[1]}: no BundleConfig.pb or base/manifest/AndroidManifest.xml")
    signed = [n for n in names if n.startswith("META-INF/") and n.upper().endswith((".SF", ".RSA", ".EC", ".DSA"))]
    if signed:
        sys.exit(f"{sys.argv[1]} is signed already: {', '.join(signed)}")
    for name in names:
        if name.startswith("base/") and not name.endswith("/"):
            inner = name.removeprefix("base/")
            base.writestr(inner.removeprefix("manifest/"), bundle.read(name))
PY
badging="$("$bt/aapt2" dump badging "$work/base.apk")"
grep -qxF "package: name='net.unfret.android' versionCode='$version_code' versionName='$version'" \
  <<< "$(sed -n "1s/ platformBuildVersionName=.*//p" <<< "$badging")" \
  || fail "$unsigned is not net.unfret.android $version ($version_code): $(sed -n 1p <<< "$badging")"
if grep -q "application-debuggable" <<< "$badging"; then fail "$unsigned is a debuggable build"; fi
mapfile -t permissions < <("$bt/aapt2" dump permissions "$work/base.apk" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p")
for permission in "${permissions[@]}"; do
  printf '%s\n' "${allowed[@]}" | grep -qxF "$permission" \
    || fail "the bundle asks for $permission: explain it on unfret.net/android (unfret_backend's PERMISSIONS) and allow it in scripts/allowed-permissions"
done
echo "unsigned bundle: $(sha256sum "$unsigned" | cut -d' ' -f1)  $unsigned"

# Sign. The keystore never touches the checkout.
if [[ -n "${ANDROID_UPLOAD_KEYSTORE_B64:-}" ]]; then
  keystore="$work/upload.keystore"
  (umask 077 && base64 -d > "$keystore" <<< "$ANDROID_UPLOAD_KEYSTORE_B64") \
    || fail "ANDROID_UPLOAD_KEYSTORE_B64 is not base64"
else
  keystore="${ANDROID_UPLOAD_KEYSTORE_FILE:?set ANDROID_UPLOAD_KEYSTORE_B64 or ANDROID_UPLOAD_KEYSTORE_FILE}"
fi
: "${ANDROID_UPLOAD_KEYSTORE_PASSWORD:?}" "${ANDROID_UPLOAD_KEY_ALIAS:?}" "${ANDROID_UPLOAD_KEY_PASSWORD:?}"

mkdir -p "$out"
aab="$out/unfret-$version.aab"
# A JAR signature (v1), the kind an app bundle carries; the APKs Play builds from it get Play's own signature.
jarsigner -keystore "$keystore" -storepass:env ANDROID_UPLOAD_KEYSTORE_PASSWORD \
  -keypass:env ANDROID_UPLOAD_KEY_PASSWORD -sigalg SHA256withRSA -digestalg SHA-256 \
  -signedjar "$aab" "$unsigned" "$ANDROID_UPLOAD_KEY_ALIAS" > "$work/sign.log" \
  || { cat "$work/sign.log" >&2; fail "jarsigner could not sign the bundle"; }

# Verify. A self-signed certificate without a timestamp is what Play expects of an upload key, so jarsigner's
# warnings about those are fine (and why -strict, which makes them errors, is not used); an unsigned or changed entry
# is not.
verify="$(jarsigner -verify -verbose "$aab")" || { echo "$verify" >&2; fail "jarsigner -verify failed"; }
grep -qxF "jar verified." <<< "$verify" || { echo "$verify" >&2; fail "the signed bundle does not verify"; }
# Every entry outside META-INF/ is listed "sm" (signature verified, in the manifest); one without flags is unsigned.
unsigned_entries="$(grep -E '^ +[0-9]+ ' <<< "$verify" | grep -v ' META-INF/' || true)"
if [[ -n "$unsigned_entries" ]] || grep -qi -e "unsigned entries" -e "jar is unsigned" <<< "$verify"; then
  echo "${unsigned_entries:-$verify}" >&2
  fail "the bundle has entries the signature doesn't cover"
fi
certs="$(keytool -printcert -jarfile "$aab")"
[[ "$(grep -c '^Signer #' <<< "$certs")" == 1 ]] || fail "the bundle must have exactly one signer"
upload_cert="$(sed -n 's/^[[:space:]]*SHA256: \([0-9A-F:]*\)$/\1/p' <<< "$certs" | sed -n 1p)"
upload_hex="$(tr -d ':' <<< "$upload_cert" | tr 'A-F' 'a-f')"
[[ "$upload_hex" =~ ^[0-9a-f]{64}$ ]] || fail "no SHA-256 fingerprint for the bundle's signer"
[[ "$upload_hex" != "$release_cert" ]] \
  || fail "the bundle is signed with the release key: the upload key must be a separate key (docs/RELEASING.md)"
grep -E '^(Owner|Valid from):' <<< "$certs" || true
echo "upload certificate SHA-256: $upload_cert ($upload_hex)"

# Its line in SHA256SUMS, next to the APK's.
name="$(basename "$aab")"
digest="$(sha256sum "$aab" | cut -d' ' -f1)"
touch "$out/SHA256SUMS"
grep -v "  $name\$" "$out/SHA256SUMS" > "$work/SHA256SUMS" || true
echo "$digest  $name" >> "$work/SHA256SUMS"
cat "$work/SHA256SUMS" > "$out/SHA256SUMS"
echo "$digest  $aab"
