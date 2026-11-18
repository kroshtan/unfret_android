# Releasing

Two kinds of build:

- **Debug** (`make apk`): signed with a throwaway debug key kept in the checkout's git-ignored `.home/.android/` (in
  CI a fresh one each run), with the dev-server switch. For testing only: it can't update a release install and is
  never published.
- **Release** (the "Release APK" workflow): built unsigned by Gradle (`make release-apk`), signed with Unfret's
  release key by `scripts/sign-release.sh` (apksigner, v2 and v3 schemes), always talking to unfret.net. It is
  attached to a Forgejo release and offered on [unfret.net/android](https://unfret.net/android).

## Versions and betas

`versionName` in `app/build.gradle.kts` names the release: `0.1.0` is tagged `v0.1.0`, and a version with a
pre-release suffix, such as `0.1.0-beta.1`, is a **beta**, tagged `v0.1.0-beta.1`. The accepted pattern is
`MAJOR.MINOR.PATCH`, optionally followed by `-` and up to four dot-separated parts of letters and digits (`-beta.1`,
`-rc.2`), with at most three digits per number; the workflow, `scripts/sign-release.sh` and unfret_backend's
`unfret.android_release` check the same pattern. No build metadata (`+…`).

A beta is released like any other version, with two differences:

- its Forgejo release is a **pre-release** (the workflow creates it so, and stops if an existing release's flag
  disagrees);
- its `release.json` says **`"prerelease": true`** (a stable release says `false`). unfret_backend reads this field:
  it must agree with the version (a suffix means `true`), and `/android` then shows a "Beta" label next to the version
  and one sentence: an early version, things may still change, and where to write about what doesn't work. A
  `release.json` without the field counts as a stable release.

The app's About screen adds the same sentence under the version for a beta.

`versionCode` goes up with every release, betas included: a beta's APK installs over, and is updated by, the stable
ones like any other. `0.1.0-beta.1` is the first release, so it keeps `versionCode` 1; the next build, beta or not,
needs 2. The workflow compares against every published release, pre-releases included (Forgejo's
`/releases/latest` leaves them out).

## The release key

The release key signs every update for as long as the app exists: Android installs an update only over an app
signed with the same key, so losing it means people have to uninstall and reinstall (and a new listing on Google
Play), and anyone holding it can publish updates. It is made by the owner, never by CI or an assistant, and never
committed.

It exists (made 2026-11-18): PKCS12, RSA 4096, alias `unfret`, valid until 2054. Its certificate's SHA-256
fingerprint, pinned in [`release-cert.sha256`](release-cert.sha256) (the signing script refuses any other) and shown
on unfret.net/android:

```
3E:71:EF:6B:76:FA:BA:49:41:97:22:AE:99:8A:42:4B:38:A7:E9:05:C6:A4:48:4A:37:F8:A9:6F:E4:5F:66:34
```

How it was made, for a replacement or another app (keytool comes with any JDK; the Makefile's Android image has one):

```bash
# On the owner's machine, never in a checkout. 10,000 days is about 27 years (Google Play asks for a validity that
# ends after 22 October 2033; 25 years or more is the usual advice).
keytool -genkeypair -v -storetype PKCS12 -keystore ~/kroshtan_secrets/unfret-android-release.p12 -alias unfret \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Kroshtan, O=Kroshtan, C=NL"
# keytool asks for the store password (with PKCS12 the key's password is the same). Keep it in a password manager
# or next to the file in ~/kroshtan_secrets/ (outside every repository), and back both up offline, in two places.
keytool -list -v -keystore ~/kroshtan_secrets/unfret-android-release.p12 -alias unfret   # SHA-256 fingerprint
```

Then, once: put the fingerprint (lower case, no colons) in `docs/release-cert.sha256` and in unfret_backend's
`unfret.android_release.SIGNING_CERT_SHA256`, and set the repo secrets below.

## Signing in CI: the "Release APK" workflow

Repo secrets (Settings → Actions → Secrets):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_B64` | the keystore file, base64: `base64 -w0 unfret-android-release.p12` |
| `ANDROID_KEYSTORE_PASSWORD` | the store password |
| `ANDROID_KEY_ALIAS` | `unfret` |
| `ANDROID_KEY_PASSWORD` | the key's password (for PKCS12, the store password) |

They reach only the signing step, which runs on the deploy runner (`kroshtan-deploy`), never the public one. The
keystore is written to a private temporary folder and deleted when the step ends; the passwords reach apksigner
through the environment (`--ks-pass env:…`), never a command line. While a secret is missing the workflow builds and
checks the unsigned APK, says signing isn't set up, and passes.

Actions → **Release APK** → Run workflow, with `ref` `main` (or an existing tag such as `v0.1.0`, to build it again)
and `dry_run` on first. It:

1. checks the commit is on main with CI passed, that `v<versionName>` is a release version (above) and doesn't name
   another commit, and that `versionCode` is above every published release's, betas included;
2. builds the release APK unsigned (`assembleRelease`) in CI's pinned image;
3. runs `scripts/sign-release.sh`: the APK is `net.unfret.android` at this version and not debuggable, asks for no
   permission beyond INTERNET and the support library's own (a new one needs its explanation on unfret.net/android
   first); zipalign, apksigner sign (v2 + v3, no v1 or v4), `apksigner verify --print-certs` (both schemes, one
   signer, the pinned certificate); writes `unfret-<version>.apk`, `SHA256SUMS` and `release.json` (version,
   versionCode, commit, size, SHA-256, certificate, minSdk, permissions, date, the unsigned APK's SHA-256, and
   `prerelease`);
4. without `dry_run`: creates the release `v<version>` (and its tag) if needed, as a pre-release for a beta, and
   attaches the three files. An APK
   already attached is never replaced: the same file again is skipped, a different one stops the run.

### Reproducible

`make release-apk` at the release's commit builds the same unsigned APK byte for byte (its SHA-256 is
`unsigned_sha256` in `release.json`): the images are pinned by digest, Gradle by checksum, dependencies by version,
and the APK carries no commit or time (`vcsInfo` is off; entries are dated 1981). The signature is the only
difference between that file and the published one.

### On unfret.net/android

The page reads `src/unfret/static/android-release.json` in unfret_backend. After a release:

```bash
cd ../unfret_backend
make android-release TAG=v0.1.0-beta.1   # reads release.json from the release, downloads the APK, checks it
git checkout -b android-v0.1.0-beta.1
git add src/unfret/static/android-release.json   # new the first time, so `commit -a` alone would miss it
git commit -m "Android 0.1.0-beta.1 on /android"   # then a pull request; deployed with the site
```

Until that file exists the page says the download is coming soon. The download itself is the release asset on
git.kroshtan.com, over HTTPS.

## Before each release

Bump `versionCode` (always up, betas included) and `versionName` in `app/build.gradle.kts`, update CHANGELOG.md, and refresh the
extractor if the extension changed (`extractor/README.md`).

## Google Play (later)

1. A Google Play developer account (one-time fee) under Kroshtan; identity verification takes days.
2. Create the app `net.unfret.android` (the application ID can never change) with **Play App Signing**. To keep a
   Play install and a direct install able to update each other, give Play the key above as the *app signing* key
   (Play Console's "use an existing app signing key", exported with Google's PEPK tool) and make a separate *upload*
   key (a lost upload key can be reset). If Play generates its own signing key instead, the two kinds of install
   can't update each other: say so on unfret.net/android when the app is listed.
3. Store listing from the README; privacy policy URL: publish [PRIVACY.md](../PRIVACY.md) on unfret.net.
   Data safety form: data collected: none stored by the app; sent to unfret.net: "Web browsing / page content the
   user chooses to check" for app functionality, not shared with third parties, encrypted in transit; no ads,
   no analytics. Content rating questionnaire; target audience adults.
4. Payments: the app sells nothing and shows no prices; checks are bought on the website (no Play Billing needed).
   Don't add in-app links or wording that urge buying outside Play beyond "Get checks" opening the website;
   re-check Google's current payments policy before the first submission.
5. Upload an Android App Bundle (`./gradlew bundleRelease`, signed with the same key) to the internal testing track
   first.
