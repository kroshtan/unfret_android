#!/usr/bin/env python3
"""Upload the signed app bundle to Google Play and release it on a track, through the Android Publisher API.

    scripts/play-upload.py --track production dist/release/unfret-0.1.0.aab
    scripts/play-upload.py --track production --dry-run dist/release/unfret-0.1.0.aab

One edit: edits.insert, bundles.upload (the bundle), tracks.update (a release named after versionName, versionCodes
[versionCode], status completed, release notes in en-US from this version's CHANGELOG.md section), edits.commit.
The "Release APK" workflow runs it after the bundle is signed (docs/RELEASING.md, "Google Play").

--dry-run commits nothing and uploads nothing: it signs in, opens an edit, reads the track and the bundles Play has
already, checks the versionCode is new, shows the release it would make (with its notes), and deletes the edit.

A versionCode Play has already is never uploaded again: when the track already has a release with it (a re-run after
a release went out), this says so and exits 0; when Play has it anywhere else, it stops with an error (bump
versionCode, or move that release in Play Console). No retries: an API error stops the run, the edit is deleted.

The service account's JSON key comes from PLAY_SERVICE_ACCOUNT_JSON (its contents) or PLAY_SERVICE_ACCOUNT_FILE (its
path). It is never printed; its private key reaches openssl through a file in a private temporary folder that is
deleted on exit. Needs only python3 and openssl (both in the Makefile's Android image).
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PACKAGE = "net.unfret.android"
ROOT = Path(__file__).resolve().parent.parent
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD_API = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TRACKS = ("production", "internal")
NOTES_LIMIT = 500  # Play's limit per language


class PlayError(Exception):
    pass


def fail(message: str) -> None:
    sys.stdout.flush()
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def read_gradle() -> tuple[str, int]:
    text = (ROOT / "app/build.gradle.kts").read_text()
    name = re.search(r'^ *versionName = "(.*)"$', text, re.MULTILINE)
    code = re.search(r"^ *versionCode = ([0-9]+)$", text, re.MULTILINE)
    if not name or not code:
        fail("app/build.gradle.kts has no versionName or versionCode")
    return name.group(1), int(code.group(1))


def plain(text: str) -> str:
    """Markdown to the plain text Play shows."""
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)  # [words](link) -> words
    text = text.replace("**", "").replace("`", "")
    return re.sub(r"\s+", " ", text).strip()


def release_notes(version: str) -> str:
    """This version's CHANGELOG.md section as plain text in at most 500 characters: its paragraphs and bullets in
    order, each one whole while it fits, else a bullet's bold lead-in alone, else left out (the first one, if nothing
    else fits, cut at its last sentence that does)."""
    lines = (ROOT / "CHANGELOG.md").read_text().splitlines()
    heading = re.compile(rf"^## {re.escape(version)}( |$)")
    start = next((i for i, line in enumerate(lines) if heading.match(line)), None)
    if start is None:
        raise PlayError(f"CHANGELOG.md has no section '## {version}': add one before releasing")
    section = []
    for line in lines[start + 1 :]:
        if line.startswith("## "):
            break
        section.append(line)
    # Paragraphs and bullets, each joined into one line.
    units: list[str] = []
    for line in section:
        if not line.strip():
            units.append("")
        elif line.startswith("- "):
            units.append("• " + line[2:])
        elif units and units[-1]:
            units[-1] += " " + line.strip()
        else:
            units.append(line.strip())
    notes = ""

    def add(text: str) -> bool:
        nonlocal notes
        candidate = f"{notes}\n{text}" if notes else text
        if len(candidate) > NOTES_LIMIT:
            return False
        notes = candidate
        return True

    for unit in (u for u in units if u.strip()):
        if add(plain(unit)):
            continue
        # Too long whole: a bullet's bold lead-in ("- **Pass links.** ...") says what it is about.
        lead = re.match(r"• \*\*(.+?)\*\*", unit)
        if lead and add("• " + plain(lead.group(1))):
            continue
        if not notes:
            # Not even the first one fits: its sentences that do.
            part = ""
            for sentence in re.split(r"(?<=[.!?])\s+", plain(unit)):
                longer = f"{part} {sentence}" if part else sentence
                if len(longer) > NOTES_LIMIT:
                    break
                part = longer
            notes = part if part.removeprefix("•").strip() else plain(unit)[: NOTES_LIMIT - 1] + "…"
    if not notes:
        raise PlayError(f"CHANGELOG.md's section '## {version}' is empty")
    return notes


def load_key() -> dict:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw and os.environ.get("PLAY_SERVICE_ACCOUNT_FILE"):
        raw = Path(os.environ["PLAY_SERVICE_ACCOUNT_FILE"]).read_text()
    try:
        key = json.loads(raw)
        if key.get("type") != "service_account" or not key.get("private_key") or not key.get("client_email"):
            raise ValueError
    except ValueError:
        # Never the contents.
        fail("PLAY_SERVICE_ACCOUNT_JSON is not a Google service account's JSON key")
    return key


def access_token(key: dict, work: Path) -> str:
    """OAuth 2.0 for a service account: a JWT signed (RS256) with its key by openssl, exchanged for a token."""
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(
        json.dumps(
            {
                "iss": key["client_email"],
                "scope": SCOPE,
                "aud": key.get("token_uri") or "https://oauth2.googleapis.com/token",
                "iat": now,
                "exp": now + 3600,
            }
        ).encode()
    )
    pem = work / "key.pem"
    fd = os.open(pem, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(key["private_key"])
    signed = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(pem)],
        input=f"{header}.{claims}".encode(),
        capture_output=True,
        check=False,
    )
    pem.unlink()
    if signed.returncode != 0:
        fail("openssl could not sign with the service account's key")
    assertion = f"{header}.{claims}.{b64url(signed.stdout)}"
    body = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion,
        }
    ).encode()
    request = urllib.request.Request(key.get("token_uri") or "https://oauth2.googleapis.com/token", data=body)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)["access_token"]
    except urllib.error.HTTPError as e:
        fail(f"Google refused the service account's sign-in: HTTP {e.code} {error_text(e)}")
    raise AssertionError


def error_text(e: urllib.error.HTTPError) -> str:
    try:
        body = json.loads(e.read())
    except ValueError:
        return ""
    error = body.get("error")
    if isinstance(error, dict):
        return error.get("message", "")
    return str(body.get("error_description") or error or "")


class Play:
    def __init__(self, token: str):
        self.token = token

    def call(
        self, method: str, url: str, body: dict | None = None, data: bytes | None = None, timeout: int = 60
    ) -> dict:
        headers = {"Authorization": f"Bearer {self.token}"}
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        elif data is not None:
            headers["Content-Type"] = "application/octet-stream"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            raise PlayError(f"{method} {url.split(PACKAGE, 1)[-1] or '/'}: HTTP {e.code} {error_text(e)}") from None


def describe(release: dict) -> str:
    return f"'{release.get('name', '?')}' versionCodes {release.get('versionCodes', [])} ({release.get('status')})"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("bundle", type=Path, help="the signed app bundle, unfret-<version>.aab")
    parser.add_argument("--track", required=True, choices=TRACKS)
    parser.add_argument("--dry-run", action="store_true", help="validate only: upload and commit nothing")
    args = parser.parse_args()

    version, version_code = read_gradle()
    if args.bundle.name != f"unfret-{version}.aab" or not args.bundle.is_file():
        fail(f"{args.bundle} is not this version's signed bundle (unfret-{version}.aab)")
    key = load_key()
    try:
        notes = release_notes(version)
    except PlayError as e:
        if not args.dry_run:
            fail(str(e))
        print(f"::warning::(dry run; a real run stops here) {e}")
        notes = ""

    work = Path(tempfile.mkdtemp(prefix="play-"))
    try:
        play = Play(access_token(key, work))
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print(f"signed in to Google Play as {key['client_email']}")

    edit = None
    try:
        edit = play.call("POST", f"{API}/edits", body={})["id"]
        base = f"{API}/edits/{edit}"
        track = play.call("GET", f"{base}/tracks/{args.track}")
        releases = track.get("releases") or []
        print(f"{args.track} track now: " + ("; ".join(describe(r) for r in releases) or "no release"))
        uploaded = {b["versionCode"] for b in play.call("GET", f"{base}/bundles").get("bundles") or []}
        on_track = [r for r in releases if str(version_code) in (r.get("versionCodes") or [])]
        if on_track:
            # A re-run: this version went out already. Nothing to do, and nothing to undo.
            print(
                f"::notice::versionCode {version_code} is on the {args.track} track already "
                f"({describe(on_track[0])}): nothing uploaded"
            )
            return
        if version_code in uploaded:
            raise PlayError(
                f"Play has versionCode {version_code} already, not on the {args.track} track: bump versionCode and "
                "versionName for a new build, or release that bundle on this track in Play Console"
            )
        highest = max(uploaded, default=0)
        if version_code < highest:
            print(
                f"::warning::versionCode {version_code} is below {highest}, the highest Play has: Play will offer "
                "it only to phones without a newer version"
            )
        release = {
            "name": version,
            "versionCodes": [str(version_code)],
            "status": "completed",
            "releaseNotes": [{"language": "en-US", "text": notes}] if notes else [],
        }
        print(f"release notes (en-US, {len(notes)} characters):\n{notes}")
        if args.dry_run:
            print(
                f"::notice::(dry run) would upload {args.bundle.name} (versionCode {version_code}) and release it "
                f"as '{version}' on the {args.track} track; nothing uploaded or committed"
            )
            return
        size = args.bundle.stat().st_size
        print(f"uploading {args.bundle.name} ({size} bytes)")
        result = play.call(
            "POST", f"{UPLOAD_API}/edits/{edit}/bundles?uploadType=media", data=args.bundle.read_bytes(), timeout=600
        )
        if result.get("versionCode") != version_code:
            raise PlayError(f"Play read versionCode {result.get('versionCode')} from the bundle, not {version_code}")
        print(f"uploaded: versionCode {result['versionCode']}, SHA-256 {result.get('sha256')}")
        play.call("PUT", f"{base}/tracks/{args.track}", body={"track": args.track, "releases": [release]})
        play.call("POST", f"{base}:commit")
        edit = None  # committed: nothing to delete
        print(
            f"::notice::Unfret {version} (versionCode {version_code}) is released on Google Play's {args.track} "
            "track; Play reviews it before it reaches people (Play Console, Publishing overview)"
        )
    except PlayError as e:
        message = str(e)
        if re.search(
            r"version code .* (already been used|already exists)|apkUpgradeVersionConflict", message, re.IGNORECASE
        ):
            message = (
                f"Play has versionCode {version_code} already: bump versionCode and versionName for a new "
                f"build ({message})"
            )
        fail(message)
    finally:
        if edit:
            try:
                play.call("DELETE", f"{API}/edits/{edit}")
                print("edit deleted: nothing committed")
            except PlayError as e:
                print(f"::warning::could not delete edit {edit}: {e} (it expires on its own)")


if __name__ == "__main__":
    main()
