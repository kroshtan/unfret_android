# Releasing

Two kinds of build:

- **Debug** (`make apk`): signed with a throwaway debug key kept in the checkout's git-ignored `.home/.android/` (in
  CI a fresh one each run), with the dev-server switch. For testing only: it can't update a release install and is
  never published.
- **Release** (the "Release APK" workflow): built unsigned by Gradle (`make release-apk`), signed with Unfret's
  release key by `scripts/sign-release.sh` (apksigner, v2 and v3 schemes), always talking to unfret.net. It is
  attached to a Forgejo release and offered on [unfret.net/android](https://unfret.net/android).
- **App bundle for Google Play** (the same workflow, once the upload key is set up): the `play` build (the release
  without prices, "Get checks" or the checkout link) as an Android App Bundle, built unsigned by Gradle
  (`make release-bundle`), signed with the Play *upload* key by `scripts/sign-bundle.sh` (jarsigner), attached to
  the same Forgejo release, and released on Google Play by `scripts/play-upload.py` (the production track unless
  the run says otherwise). Not for a direct install: Play serves APKs it builds from it, signed with the release key
  ("Google Play" below).

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
| `ANDROID_UPLOAD_KEYSTORE_B64` | the Play upload keystore, base64: `base64 -w0 unfret-android-upload.p12` ("Google Play" below) |
| `ANDROID_UPLOAD_KEYSTORE_PASSWORD` | its store password |
| `ANDROID_UPLOAD_KEY_ALIAS` | `upload` |
| `ANDROID_UPLOAD_KEY_PASSWORD` | its key's password (for PKCS12, the store password) |
| `PLAY_SERVICE_ACCOUNT_JSON` | the JSON key of the Play service account `unfret-release@unfret-play.iam.gserviceaccount.com` ("Releasing on Google Play" below) |

They reach only the signing and Play steps, which run on the deploy runner (`kroshtan-deploy`), never the public one. A
keystore is written to a private temporary folder and deleted when the step ends; the passwords reach apksigner and
jarsigner through the environment (`--ks-pass env:…`, `-storepass:env …`), never a command line. While one of the
first four is missing the workflow builds and checks the unsigned APK, says signing isn't set up, and passes. While
one of the `ANDROID_UPLOAD_*` four is missing it says the upload key isn't set up and skips the app bundle
altogether (no build, no signature, nothing attached); the APK is released as before.

Actions → **Release APK** → Run workflow, with `ref` `main` (or an existing tag such as `v0.1.0`, to build it again),
`dry_run` on first, and `play_track` (`production`, the default; `internal`; or `none`, no Google Play). It:

1. checks the commit is on main with CI passed, that `v<versionName>` is a release version (above) and doesn't name
   another commit, and that `versionCode` is above every published release's, betas included;
2. builds the release APK unsigned (`assembleRelease`) in CI's pinned image;
3. runs `scripts/sign-release.sh`: the APK is `net.unfret.android` at this version and not debuggable, asks for no
   permission beyond those in `scripts/allowed-permissions` (INTERNET and the support library's own; a new one needs
   its explanation on unfret.net/android first); zipalign, apksigner sign (v2 + v3, no v1 or v4),
   `apksigner verify --print-certs` (both schemes, one signer, the pinned certificate); writes
   `unfret-<version>.apk`, `SHA256SUMS` and `release.json` (version, versionCode, commit, size, SHA-256,
   certificate, minSdk, permissions, date, the unsigned APK's SHA-256, and `prerelease`);
4. with the upload key set up: builds the app bundle unsigned (`bundle<Type>` of the workflow's `BUNDLE_BUILD_TYPE`,
   below) and runs `scripts/sign-bundle.sh`: the same checks of package, version, not debuggable and
   `scripts/allowed-permissions` (on the bundle's base module, through aapt2); jarsigner (SHA256withRSA) with the
   upload key; `jarsigner -verify` (every entry signed, one signer); refuses a bundle signed with the release key's
   certificate (`release-cert.sha256`); prints the upload certificate's SHA-256 (never the key); writes
   `unfret-<version>.aab` and adds its line to `SHA256SUMS`;
5. without `dry_run`: creates the release `v<version>` (and its tag) if needed, as a pre-release for a beta, and
   attaches the files (and the app bundle when there is one). An APK already attached is never replaced: the same
   file again is skipped, a different one stops the run. An app bundle already attached stays (jarsigner's signature
   differs on every run, in its timestamps), and `SHA256SUMS` names that one; a bundle attached to a release that
   didn't have one yet replaces its `SHA256SUMS` with one that lists both;
6. with an app bundle, `PLAY_SERVICE_ACCOUNT_JSON` set and `play_track` not `none`: runs `scripts/play-upload.py`,
   which releases the bundle on that Google Play track ("Releasing on Google Play" below). With `dry_run` it only
   checks against Play, uploading and committing nothing.

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

## Google Play

Unfret is on Google Play since 0.1.0 (versionCode 3, sent for review 2026-11-20 by hand). Since then the "Release
APK" workflow uploads each release's app bundle itself ("Releasing on Google Play" below). How it was set up:

1. A Google Play developer account (one-time fee) under Kroshtan; identity verification takes days.
2. Create the app `net.unfret.android` (the application ID can never change) with **Play App Signing**. To keep a
   Play install and a direct install able to update each other, give Play the key above as the *app signing* key
   (Play Console's "use an existing app signing key", exported with Google's PEPK tool) and make a separate *upload*
   key (a lost upload key can be reset). If Play generates its own signing key instead, the two kinds of install
   can't update each other: say so on unfret.net/android when the app is listed.
3. Store listing from the README; its images (icon, feature graphic, phone screenshots of the play build) are in
   [store/play/](../store/play/README.md). Privacy policy URL: publish [PRIVACY.md](../PRIVACY.md) on unfret.net.
   Data safety form: data collected: none stored by the app; sent to unfret.net: "Web browsing / page content the
   user chooses to check" for app functionality, not shared with third parties, encrypted in transit; no ads,
   no analytics. Content rating questionnaire; target audience adults.
4. Payments: the Play build is the `play` build type (`Storefront`): it sells nothing, shows no price and points
   nowhere to buy, no "Get checks" and no link to the checkout (no Play Billing needed); a gift code is redeemed in
   the app. Its words are `src/play/res`'s over `src/main/res`'s, and StorefrontTest fails on a Play string that
   speaks of buying. Re-check Google's current payments policy before the first submission.
5. Make the upload key and set its four repo secrets (below); the next "Release APK" run attaches
   `unfret-<version>.aab`, signed with it.
6. The service account for the Android Publisher API and its repo secret (below), so the workflow uploads the bundle
   itself. The first upload of an app has to be by hand in Play Console (the API can't create the app): 0.1.0 was.

### Releasing on Google Play

After the bundle is signed (and, on a real run, attached to the Forgejo release), the "Release APK" workflow's step
"Release the app bundle on Google Play" runs `scripts/play-upload.py` (python3 and openssl only, through the Android
Publisher API v3), in one *edit*:

1. `edits.insert`, then reads the track and the bundles Play already has;
2. `bundles.upload`: `unfret-<version>.aab` (Play checks its upload-key signature and reads its versionCode, which
   must be the one in `app/build.gradle.kts`);
3. `tracks.update`: one release on the track, named after `versionName` (`0.1.1`), with this versionCode, status
   `completed` (to everyone once Play approves it), and release notes in `en-US`: this version's `## <version>`
   section of CHANGELOG.md as plain text, at most 500 characters (Play's limit): paragraphs and bullets whole while
   they fit, else a bullet's bold lead-in alone. A real run stops if CHANGELOG.md has no section for the version;
4. `edits.commit`: the release goes to Play's review, as one sent from Play Console would. A release still in review
   is replaced by the new one.

Pick the track with the run's `play_track` input: `production` (the default), `internal` (internal testing; testers
listed in Play Console), or `none` (no Google Play this run; upload the attached bundle by hand if needed).

- **Dry run** (`dry_run` on): signs in, opens an edit, reads the track and Play's bundles, checks the versionCode is
  new, prints the release it would make with its notes, and deletes the edit. Nothing is uploaded or committed (the
  API has no validate-only upload, so the bundle itself is checked only on a real run).
- **A versionCode Play has already** is never uploaded again, and nothing is retried. Already on the chosen track (a
  re-run of a release that went out): the step says so and passes. Anywhere else (another track, or an upload whose
  edit wasn't committed): the step fails and says to bump `versionCode` and `versionName`, or to release that bundle
  on the track in Play Console. Any other API error fails the step; the edit is deleted, so nothing half-done stays.
- **Not set up**: without `PLAY_SERVICE_ACCOUNT_JSON` the step says Google Play upload isn't set up and passes; the
  bundle is attached to the Forgejo release as before, for an upload by hand. Without an app bundle (upload key not
  set up) there is nothing to send and the step doesn't run.

Re-running a failed Play step: run the workflow again with `ref` set to the tag (`v0.1.1`). The APK and bundle
already attached stay, and the attached bundle is the one sent to Play.

#### The service account

`unfret-release@unfret-play.iam.gserviceaccount.com`, in the Google Cloud project `unfret-play` with the Google
Play Android Developer API enabled, invited in Play Console (Users and permissions) with release permissions.
Its JSON key is the repo secret `PLAY_SERVICE_ACCOUNT_JSON` (the whole file, as is); the owner keeps a
copy in `~/kroshtan_secrets/unfret-play-service-account.json`, outside every repository. The script reads it from
that variable (or a path in `PLAY_SERVICE_ACCOUNT_FILE`, for a local dry run), never prints it, and hands its private
key to openssl through a file in a private temporary folder that is deleted at once. A leaked key: delete it in
Google Cloud (IAM → Service accounts → Keys), make a new one, and replace the secret.

A local check against Play, uploading nothing:

```bash
PLAY_SERVICE_ACCOUNT_FILE=~/kroshtan_secrets/unfret-play-service-account.json \
  scripts/play-upload.py --track production --dry-run dist/release/unfret-<version>.aab
```

### The upload key

Play checks every app bundle uploaded against the upload key's certificate, then signs the APKs it serves with the
app signing key (the release key above). So the upload key is a separate key: if it is lost or leaks, Play Console
can reset it (the app's signature on phones doesn't change). `scripts/sign-bundle.sh` refuses a bundle signed with
the release key's certificate. Like the release key it is made by the owner, never by CI or an assistant, and never
committed:

```bash
# On the owner's machine, never in a checkout. Play asks for an RSA upload key valid past 22 October 2033.
keytool -genkeypair -v -storetype PKCS12 -keystore ~/kroshtan_secrets/unfret-android-upload.p12 -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Kroshtan upload, O=Kroshtan, C=NL"
keytool -list -v -keystore ~/kroshtan_secrets/unfret-android-upload.p12 -alias upload   # SHA-256 fingerprint
keytool -export -rfc -keystore ~/kroshtan_secrets/unfret-android-upload.p12 -alias upload \
  -file ~/kroshtan_secrets/unfret-android-upload-cert.pem   # what Play Console asks for
```

Keep its password like the release key's. Register the certificate in Play Console (App integrity, when choosing
"use an existing app signing key"), then set `ANDROID_UPLOAD_KEYSTORE_B64` (`base64 -w0 unfret-android-upload.p12`),
`ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS` (`upload`) and `ANDROID_UPLOAD_KEY_PASSWORD` (the
store password, for PKCS12). The workflow's "Sign and verify the app bundle" step prints the certificate's SHA-256:
it must match the upload certificate Play Console shows.

### Which build goes to Play

`BUNDLE_BUILD_TYPE` at the top of `.forgejo/workflows/release-apk.yaml` names the build type whose bundle goes to
Play (Gradle's `bundle<Type>`, output `app/build/outputs/bundle/<type>/app-<type>.aab`); the Makefile's
`BUNDLE_BUILD_TYPE` (`make release-bundle`) is the same and kept in step. It is `play`: the release without prices,
"Get checks" or the checkout link (item 4), so `bundlePlay`, `app/build/outputs/bundle/play/app-play.aab`. Never the
`release` build type's bundle: it has "Get checks".
