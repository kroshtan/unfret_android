#!/usr/bin/env bash
# Publish a signed Unfret Dev build (scripts/sign-dev.sh's <dir>: one unfret-dev-*.apk, SHA256SUMS, dev.json) as the
# forge's rolling pre-release "dev": the previous one and its tag are deleted, and a new one is made on the build's
# commit with the three files. dev.unfret.net/android reads dev.json from
#   https://git.kroshtan.com/kroshtan/unfret_android/releases/download/dev/dev.json
# and links the APK next to it; production's /android never shows it. Never a release key's build: sign-dev.sh
# checked the signer.
#
#   FORGE_TOKEN=… scripts/publish-dev.sh dist/dev
#
# FORGE_API defaults to this repository's API on git.kroshtan.com. CI's publish-dev job runs it on every push to main.
set -euo pipefail

dir="${1:?usage: publish-dev.sh <dir from sign-dev.sh>}"
api="${FORGE_API:-https://git.kroshtan.com/api/v1/repos/kroshtan/unfret_android}"
: "${FORGE_TOKEN:?FORGE_TOKEN is not set}"
tag=dev
auth="Authorization: token $FORGE_TOKEN"
fail() { echo "::error::$*" >&2; exit 1; }
field() { python3 -I -c 'import json, sys; print(json.load(open(sys.argv[2]))[sys.argv[1]])' "$1" "$2"; }

facts="$dir/dev.json"
[[ -f "$facts" && -f "$dir/SHA256SUMS" ]] || fail "$dir has no dev.json or SHA256SUMS: run scripts/sign-dev.sh first"
apk="$(field apk "$facts")"
[[ "$apk" =~ ^unfret-dev-[0-9A-Za-z.-]+\.apk$ && -f "$dir/$apk" ]] || fail "$dir/$apk is missing"
[[ "$(field package "$facts")" == net.unfret.android.dev ]] || fail "$facts is not Unfret Dev's"
version="$(field version "$facts")"
commit="$(field commit "$facts")"
digest="$(field sha256 "$facts")"
cert="$(field cert_sha256 "$facts")"
run="$(field run "$facts")"
[[ "$(sha256sum < "$dir/$apk" | cut -d' ' -f1)" == "$digest" ]] || fail "$apk doesn't match dev.json's SHA-256"

# The previous dev release and its tag go: the tag moves to this build's commit.
code="$(curl -sS -o release.dev.api -w '%{http_code}' -H "$auth" "$api/releases/tags/$tag")"
if [[ "$code" == 200 ]]; then
  [[ "$(field prerelease release.dev.api)" == True ]] || fail "release '$tag' isn't a pre-release: fix it on the forge"
  curl -fsS -o /dev/null -X DELETE -H "$auth" "$api/releases/$(field id release.dev.api)"
  echo "deleted the previous dev release"
elif [[ "$code" != 404 ]]; then
  fail "looking up release $tag: HTTP $code"
fi
rm -f release.dev.api
code="$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE -H "$auth" "$api/tags/$tag")"
[[ "$code" == 204 || "$code" == 404 ]] || fail "deleting tag $tag: HTTP $code"

body="$(VERSION="$version" COMMIT="$commit" DIGEST="$digest" CERT="$cert" RUN="$run" APK="$apk" TAG="$tag" \
  python3 -I - <<'PY'
import json, os

e = os.environ
print(json.dumps({
    "tag_name": e["TAG"],
    "target_commitish": e["COMMIT"],
    "name": f"Unfret Dev {e['VERSION']}",
    "prerelease": True,
    "body": (
        "Unfret Dev: a test build of main, for testers only. Its own app (net.unfret.android.dev, \"Unfret Dev\") "
        "that talks to https://dev.unfret.net only, signed with the dev key, never the release key. Replaced by "
        "every push to main. Get Unfret itself on Google Play or https://unfret.net/android.\n\n"
        f"- `{e['APK']}` SHA-256: `{e['DIGEST']}` (also in SHA256SUMS)\n"
        f"- Signing certificate SHA-256 (dev key): `{e['CERT']}`\n"
        f"- Built from {e['COMMIT']} by CI run {e['RUN']}; dev.json holds these facts for dev.unfret.net/android."
    ),
}))
PY
)"
id="$(curl -fsS -X POST -H "$auth" -H "Content-Type: application/json" -d "$body" "$api/releases" \
  | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["id"])')"
for name in "$apk" SHA256SUMS dev.json; do
  curl -fsS -o /dev/null -X POST -H "$auth" -F "attachment=@$dir/$name" "$api/releases/$id/assets?name=$name"
  echo "attached $name"
done
echo "::notice::Unfret Dev $version ($commit) is the dev pre-release: ${api%/api/v1/repos/*}/kroshtan/unfret_android/releases/tag/$tag"
