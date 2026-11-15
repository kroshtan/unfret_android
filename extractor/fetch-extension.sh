#!/usr/bin/env bash
# Put a copy of kroshtan/unfret_extension at the commit in EXTENSION_COMMIT into extractor/.extension
# (git-ignored), from the forge's archive of that commit. Only read: nothing is ever pushed there.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
commit="$(tr -d '[:space:]' < "$here/EXTENSION_COMMIT")"
dest="$here/.extension"
repo="${UNFRET_EXTENSION_REPO:-https://git.kroshtan.com/kroshtan/unfret_extension}"

if [[ -f "$dest/.commit" && "$(cat "$dest/.commit")" == "$commit" ]]; then
  echo "extractor/.extension is unfret_extension@$commit"
  exit 0
fi
rm -rf "$dest"
mkdir -p "$dest"
curl -fsSL "$repo/archive/$commit.tar.gz" | tar -xz -C "$dest" --strip-components=1
echo "$commit" > "$dest/.commit"
echo "fetched unfret_extension@$commit into extractor/.extension"
