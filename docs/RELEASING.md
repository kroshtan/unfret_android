# Releasing

Only debug builds exist so far (`make apk`). They are signed with a throwaway debug key kept in the
checkout's git-ignored `.home/.android/` (in CI a fresh one each run), so they can't update a release install and
must never be published.

## The release key (owner, once)

The release key signs every update for as long as the app exists: losing it means a new app listing, and anyone
holding it can publish updates. It is made by the owner, never by CI or an assistant, and never committed.

```bash
# On the owner's machine; keytool comes with any JDK (or: docker run --rm -it -v ~/kroshtan_secrets:/k
#   ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3… keytool …, writing to /k)
keytool -genkeypair -v -keystore ~/kroshtan_secrets/unfret-android-release.jks -alias unfret \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Kroshtan, O=Kroshtan, C=NL"
# Note the store and key passwords in ~/kroshtan_secrets/unfret-android-release.txt; back both files up offline.
keytool -list -v -keystore ~/kroshtan_secrets/unfret-android-release.jks   # the SHA-256 fingerprint, for the README
```

## Google Play

1. A Google Play developer account (one-time fee) under Kroshtan; identity verification takes days.
2. Create the app `net.unfret.android` (the application ID can never change). Use **Play App Signing** with the key
   above as the *upload* key: Google then holds the app signing key, and a lost upload key can be reset.
3. Store listing from this README; privacy policy URL: publish [PRIVACY.md](../PRIVACY.md) on unfret.net.
   Data safety form: data collected: none stored by the app; sent to unfret.net: "Web browsing / page content the
   user chooses to check" for app functionality, not shared with third parties, encrypted in transit; no ads,
   no analytics. Content rating questionnaire; target audience adults.
4. Payments: the app sells nothing and shows no prices; checks are bought on the website (no Play Billing needed).
   Don't add in-app links or wording that urge buying outside Play beyond "Get checks" opening the website;
   re-check Google's current payments policy before the first submission.
5. Upload an Android App Bundle (`./gradlew bundleRelease`, signed as below) to the internal testing track first.

## Direct APK (unfret.net)

`./gradlew assembleRelease` signed with the same key, published with its SHA-256 and the signing certificate's
fingerprint so people can verify it (`apksigner verify --print-certs`).

## Signing in CI (later)

Signing jobs hold secrets, so they never run on the public runner. When wanted:

1. Forgejo repo secrets: `ANDROID_KEYSTORE_B64` (`base64 -w0 unfret-android-release.jks`), `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
2. A manual workflow (`workflow_dispatch`, like the extension's `release-store.yaml`) on a runner trusted with
   secrets, that writes the keystore to a temporary file, passes the values as Gradle properties, builds
   `bundleRelease`/`assembleRelease`, verifies with `apksigner`, attaches the files to the release `v<version>` and
   deletes the keystore.
3. In `app/build.gradle.kts`, a `signingConfigs.release` that reads those properties when present (and stays unset
   otherwise, so local and PR builds keep working without secrets).

Before each release: bump `versionCode` (always up) and `versionName` in `app/build.gradle.kts`, update CHANGELOG.md,
and refresh the extractor if the extension changed (`extractor/README.md`).
