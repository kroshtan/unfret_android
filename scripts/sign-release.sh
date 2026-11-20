#!/usr/bin/env bash
# Sign the unsigned release APK with Unfret's release key, check it, and write what the release publishes:
#   <out>/unfret-<version>.apk, <out>/SHA256SUMS and <out>/release.json (the facts unfret.net/android shows, with
#   "prerelease": true for a beta such as 0.1.0-beta.1).
#
#   scripts/sign-release.sh app/build/outputs/apk/release/app-release-unsigned.apk dist/release
#
# Runs in the Makefile's Android image (apksigner, zipalign and aapt2 from build-tools 36.0.0, and python3); the
# "Release APK" workflow runs it (docs/RELEASING.md). Before signing it checks the APK is the release build of this
# version (package, versionName and versionCode, not debuggable) and asks for no permission beyond those
# unfret.net/android explains. After signing, `apksigner verify` must pass with the v2 and v3 schemes, one signer, and
# that signer's certificate the one pinned in docs/release-cert.sha256.
#
# The key comes from the environment: ANDROID_KEYSTORE_B64 (the keystore, base64) or ANDROID_KEYSTORE_FILE (its
# path), ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD. The passwords reach apksigner as
# `env:` references, never on a command line; a keystore written from ANDROID_KEYSTORE_B64 lives in a private
# temporary folder that is deleted on exit. RELEASE_COMMIT names the commit (default: git rev-parse HEAD).
set -euo pipefail

unsigned="${1:?usage: sign-release.sh <unsigned.apk> <out dir>}"
out="${2:?usage: sign-release.sh <unsigned.apk> <out dir>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
bt="${ANDROID_HOME:?ANDROID_HOME is not set (run this in the Android image)}/build-tools/36.0.0"

# The permissions unfret.net/android explains (unfret_backend: unfret.android_release.PERMISSIONS), in
# scripts/allowed-permissions. A new one needs its explanation there first.
mapfile -t allowed < <(grep -v -e '^#' -e '^$' "$root/scripts/allowed-permissions")

fail() { echo "::error::$*" >&2; exit 1; }

version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$root/app/build.gradle.kts")"
version_code="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$root/app/build.gradle.kts")"
# major.minor.patch, or a beta with a pre-release suffix (0.1.0-beta.1); the same pattern as the release workflow and
# unfret_backend's unfret.android_release.
release_version='^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}(-[0-9A-Za-z]{1,16}(\.[0-9A-Za-z]{1,16}){0,3})?$'
[[ "$version" =~ $release_version ]] || fail "versionName '$version' is not a release version (0.1.0, 0.1.0-beta.1)"
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "versionCode '$version_code' is not a number"
commit="${RELEASE_COMMIT:-$(git -C "$root" rev-parse HEAD)}"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "'$commit' is not a commit"
pinned="$(tr -d '[:space:]' < "$root/docs/release-cert.sha256")"
[[ "$pinned" =~ ^[0-9a-f]{64}$ ]] || fail "docs/release-cert.sha256 does not hold a SHA-256 digest"

# What is being signed.
badging="$("$bt/aapt2" dump badging "$unsigned")"
grep -qxF "package: name='net.unfret.android' versionCode='$version_code' versionName='$version'" \
  <<< "$(sed -n "1s/ platformBuildVersionName=.*//p" <<< "$badging")" \
  || fail "$unsigned is not net.unfret.android $version ($version_code): $(head -1 <<< "$badging")"
if grep -q "application-debuggable" <<< "$badging"; then fail "$unsigned is a debuggable build"; fi
min_sdk="$(sed -n "s/^minSdkVersion:'\([0-9]*\)'$/\1/p" <<< "$badging")"
[[ -n "$min_sdk" ]] || fail "no minSdkVersion in $unsigned"
mapfile -t permissions < <("$bt/aapt2" dump permissions "$unsigned" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p")
for permission in "${permissions[@]}"; do
  printf '%s\n' "${allowed[@]}" | grep -qxF "$permission" \
    || fail "the APK asks for $permission: explain it on unfret.net/android (unfret_backend's PERMISSIONS) and allow it in scripts/allowed-permissions"
done
unsigned_sha256="$(sha256sum "$unsigned" | cut -d' ' -f1)"

# Sign. The keystore never touches the checkout.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"
if [[ -n "${ANDROID_KEYSTORE_B64:-}" ]]; then
  keystore="$work/release.keystore"
  (umask 077 && base64 -d > "$keystore" <<< "$ANDROID_KEYSTORE_B64") || fail "ANDROID_KEYSTORE_B64 is not base64"
else
  keystore="${ANDROID_KEYSTORE_FILE:?set ANDROID_KEYSTORE_B64 or ANDROID_KEYSTORE_FILE}"
fi
: "${ANDROID_KEYSTORE_PASSWORD:?}" "${ANDROID_KEY_ALIAS:?}" "${ANDROID_KEY_PASSWORD:?}"

mkdir -p "$out"
apk="$out/unfret-$version.apk"
"$bt/zipalign" -P 16 -f 4 "$unsigned" "$work/aligned.apk"
# minSdk 26: v2 and v3 cover every Android the app runs on; no v1 (JAR) signature, no v4 (.idsig) file.
"$bt/apksigner" sign --ks "$keystore" --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
  --min-sdk-version "$min_sdk" --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false --out "$apk" "$work/aligned.apk"

# Verify, as anyone downloading it can.
verify="$("$bt/apksigner" verify --verbose --print-certs --min-sdk-version "$min_sdk" "$apk")"
echo "$verify"
grep -qxF "Verified using v2 scheme (APK Signature Scheme v2): true" <<< "$verify" || fail "no valid v2 signature"
grep -qxF "Verified using v3 scheme (APK Signature Scheme v3): true" <<< "$verify" || fail "no valid v3 signature"
grep -qxF "Number of signers: 1" <<< "$verify" || fail "the APK must have exactly one signer"
mapfile -t digests < <(sed -n 's/.*certificate SHA-256 digest: \([0-9a-f]*\)$/\1/p' <<< "$verify" | sort -u)
[[ "${#digests[@]}" == 1 ]] || fail "expected one signing certificate, found: ${digests[*]:-none}"
[[ "${digests[0]}" == "$pinned" ]] \
  || fail "signed with certificate ${digests[0]}, not the release key's $pinned (docs/release-cert.sha256)"
"$bt/zipalign" -c -P 16 4 "$apk" > /dev/null || fail "the signed APK is not aligned"

# What the release publishes.
(cd "$out" && sha256sum "unfret-$version.apk" > SHA256SUMS)
VERSION="$version" VERSION_CODE="$version_code" COMMIT="$commit" MIN_SDK="$min_sdk" CERT="$pinned" \
  UNSIGNED_SHA256="$unsigned_sha256" APK="$apk" PERMISSIONS="${permissions[*]}" \
  python3 -I - "$out/release.json" <<'PY'
import hashlib, json, os, sys
from datetime import datetime, timezone

env = os.environ
data = open(env["APK"], "rb").read()
facts = {
    "version": env["VERSION"],
    "version_code": int(env["VERSION_CODE"]),
    "commit": env["COMMIT"],
    "size": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
    "cert_sha256": env["CERT"],
    "min_sdk": int(env["MIN_SDK"]),
    "permissions": env["PERMISSIONS"].split(),
    "published": datetime.now(timezone.utc).date().isoformat(),
    # `make release-apk` at this commit builds exactly this, before signing.
    "unsigned_sha256": env["UNSIGNED_SHA256"],
    # A beta: a version with a pre-release suffix (0.1.0-beta.1). unfret.net/android labels it "Beta".
    "prerelease": "-" in env["VERSION"],
}
with open(sys.argv[1], "w") as file:
    json.dump(facts, file, indent=2)
    file.write("\n")
print(json.dumps(facts, indent=2))
PY
