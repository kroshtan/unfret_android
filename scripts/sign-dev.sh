#!/usr/bin/env bash
# Sign an unsigned Unfret Dev APK (the `dev` build type, net.unfret.android.dev) with the dev key, check it, and write
# what dev.unfret.net/android shows:
#   <out>/unfret-dev-<versionName>.apk, <out>/SHA256SUMS and <out>/dev.json
#
#   scripts/sign-dev.sh app/build/outputs/apk/dev/app-dev-unsigned.apk dist/dev
#
# Never the release key: the signer's certificate must be the one pinned in docs/dev-cert.sha256, and never the one in
# docs/release-cert.sha256. Runs in the Makefile's Android image; CI's publish-dev job runs it on every push to main
# (docs/RELEASING.md, "Dev builds").
#
# The key comes from the environment: ANDROID_DEV_KEYSTORE_B64 (the keystore, base64) or ANDROID_DEV_KEYSTORE_FILE
# (its path), and ANDROID_DEV_KEYSTORE_PASSWORD (the store's and the key's; alias "dev"). The password reaches
# apksigner as an `env:` reference, never on a command line; a keystore written from ANDROID_DEV_KEYSTORE_B64 lives in
# a private temporary folder that is deleted on exit. DEV_COMMIT names the commit (default: git rev-parse HEAD),
# DEV_RUN the CI run (default: the versionCode).
set -euo pipefail

unsigned="${1:?usage: sign-dev.sh <unsigned.apk> <out dir>}"
out="${2:?usage: sign-dev.sh <unsigned.apk> <out dir>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
bt="${ANDROID_HOME:?ANDROID_HOME is not set (run this in the Android image)}/build-tools/36.0.0"

fail() { echo "::error::$*" >&2; exit 1; }

pinned="$(tr -d '[:space:]' < "$root/docs/dev-cert.sha256")"
release="$(tr -d '[:space:]' < "$root/docs/release-cert.sha256")"
[[ "$pinned" =~ ^[0-9a-f]{64}$ ]] || fail "docs/dev-cert.sha256 does not hold a SHA-256 digest"
[[ "$pinned" != "$release" ]] || fail "docs/dev-cert.sha256 is the release key's certificate"
commit="${DEV_COMMIT:-$(git -C "$root" rev-parse HEAD)}"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "'$commit' is not a commit"

# What is being signed: Unfret Dev, not debuggable.
badging="$("$bt/aapt2" dump badging "$unsigned")"
first="$(head -1 <<< "$badging")"
[[ "$first" =~ ^package:\ name=\'net\.unfret\.android\.dev\'\ versionCode=\'([0-9]+)\'\ versionName=\'([^\']+)\' ]] \
  || fail "$unsigned is not net.unfret.android.dev: $first"
version_code="${BASH_REMATCH[1]}"
version="${BASH_REMATCH[2]}"
[[ "$version" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}(-[0-9A-Za-z.]{1,40})?-dev\.[0-9]+$ ]] \
  || fail "versionName '$version' is not a dev version (0.1.0-dev.42)"
if grep -q "application-debuggable" <<< "$badging"; then fail "$unsigned is a debuggable build"; fi
grep -q "^application-label:'Unfret Dev'$" <<< "$badging" || fail "$unsigned isn't labelled Unfret Dev"
min_sdk="$(sed -n "s/^minSdkVersion:'\([0-9]*\)'$/\1/p" <<< "$badging")"
[[ -n "$min_sdk" ]] || fail "no minSdkVersion in $unsigned"
run="${DEV_RUN:-$version_code}"
[[ "$run" =~ ^[0-9]+$ ]] || fail "DEV_RUN '$run' is not a number"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"
if [[ -n "${ANDROID_DEV_KEYSTORE_B64:-}" ]]; then
  keystore="$work/dev.p12"
  (umask 077 && base64 -d > "$keystore" <<< "$ANDROID_DEV_KEYSTORE_B64") || fail "ANDROID_DEV_KEYSTORE_B64 is not base64"
else
  keystore="${ANDROID_DEV_KEYSTORE_FILE:?set ANDROID_DEV_KEYSTORE_B64 or ANDROID_DEV_KEYSTORE_FILE}"
fi
: "${ANDROID_DEV_KEYSTORE_PASSWORD:?}"

mkdir -p "$out"
name="unfret-dev-$version.apk"
apk="$out/$name"
"$bt/zipalign" -P 16 -f 4 "$unsigned" "$work/aligned.apk"
"$bt/apksigner" sign --ks "$keystore" --ks-key-alias dev \
  --ks-pass env:ANDROID_DEV_KEYSTORE_PASSWORD --key-pass env:ANDROID_DEV_KEYSTORE_PASSWORD \
  --min-sdk-version "$min_sdk" --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false --out "$apk" "$work/aligned.apk"

verify="$("$bt/apksigner" verify --verbose --print-certs --min-sdk-version "$min_sdk" "$apk")"
grep -qxF "Verified using v2 scheme (APK Signature Scheme v2): true" <<< "$verify" || fail "no valid v2 signature"
grep -qxF "Number of signers: 1" <<< "$verify" || fail "the APK must have exactly one signer"
mapfile -t digests < <(sed -n 's/.*certificate SHA-256 digest: \([0-9a-f]*\)$/\1/p' <<< "$verify" | sort -u)
[[ "${#digests[@]}" == 1 ]] || fail "expected one signing certificate, found: ${digests[*]:-none}"
[[ "${digests[0]}" == "$pinned" ]] || fail "signed with ${digests[0]}, not the dev key's $pinned (docs/dev-cert.sha256)"

(cd "$out" && sha256sum "$name" > SHA256SUMS)
VERSION="$version" VERSION_CODE="$version_code" COMMIT="$commit" RUN="$run" MIN_SDK="$min_sdk" CERT="$pinned" \
  APK="$apk" NAME="$name" python3 -I - "$out/dev.json" <<'PY'
import hashlib, json, os, sys
from datetime import datetime, timezone

env = os.environ
data = open(env["APK"], "rb").read()
facts = {
    "package": "net.unfret.android.dev",
    "apk": env["NAME"],
    "version": env["VERSION"],
    "version_code": int(env["VERSION_CODE"]),
    "run": int(env["RUN"]),
    "commit": env["COMMIT"],
    "size": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
    "cert_sha256": env["CERT"],
    "min_sdk": int(env["MIN_SDK"]),
    "built": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
}
with open(sys.argv[1], "w") as file:
    json.dump(facts, file, indent=2)
    file.write("\n")
print(json.dumps(facts, indent=2))
PY
