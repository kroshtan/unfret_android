# Testing by hand: free checks, backups and pass links

Two things the unit tests can't run end to end: Android restoring the free checks' flag after a reinstall, and a
pass link opening the app. Both take a phone or an emulator with `adb`, and a **debug build** (`make apk`): it is
debuggable, so `run-as` can read its files, and it can use the dev deployment (dev.unfret.net, test network), so
testing makes passes there rather than on unfret.net.

On an emulator, use an image without Google Play (for example `system-images;android-35;default;x86_64`): its
backups go to Android's local transport, on the device, instead of a Google account.

## Pointing a fresh install at dev.unfret.net

The first launch makes a pass straight after the intro, before the dev-server switch can be turned on. To make it on
dev instead, set the switch's value before the first launch:

```bash
adb install dist/unfret-<version>-debug.apk
adb shell run-as net.unfret.android sh -c 'mkdir -p shared_prefs && echo "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><string name=\"server\">https://dev.unfret.net</string></map>" > shared_prefs/unfret.xml'
```

(Do this after the restore below, never before it: `shared_prefs/unfret.xml` isn't in the backup, so a restore
leaves it alone, but the install that restores the flag starts from an empty app.)

## The free checks, a backup and a restore

What the app backs up is one file, `shared_prefs/free_checks.xml` (`res/xml/backup_rules.xml` for Android 11 and
older, `res/xml/data_extraction_rules.xml` for 12 and later): a yes that this phone received its free checks. Never
the pass, which is in `shared_prefs/unfret.xml`.

1. **Backups on, to a transport that keeps them.** On a phone with a Google account, the account's own transport
   (`bmgr list transports` marks the one in use with `*`); on an emulator without Google Play, the local transport:

   ```bash
   adb shell bmgr enable true
   adb shell bmgr list transports
   adb shell bmgr transport com.android.localtransport/.LocalTransport   # emulator only
   adb shell bmgr autorestore true
   ```

2. **First install.** Install, point it at dev (above), open Unfret and tap **I agree**. The app makes its pass and
   says **You have 5 free checks**. The flag is set:

   ```bash
   adb shell run-as net.unfret.android cat shared_prefs/free_checks.xml   # <boolean name="received" value="true" />
   ```

3. **Back up now** (Android otherwise backs up about once a day, on charge and Wi-Fi):

   ```bash
   adb shell bmgr backupnow net.unfret.android   # "Backup finished with result: Success"
   ```

   On the emulator the backup can be read back (as root: `adb root`), to see that it holds the flag and nothing else:

   ```bash
   adb exec-out cat /data/user/0/com.android.localtransport/files/1/_full/net.unfret.android > backup.tar
   tar tvf backup.tar   # apps/net.unfret.android/_manifest and apps/net.unfret.android/sp/free_checks.xml only
   ```

4. **Reinstall.** Uninstall and install again: Android restores the backup during the install, before the app
   first runs.

   ```bash
   adb uninstall net.unfret.android
   adb install dist/unfret-<version>-debug.apk
   adb shell run-as net.unfret.android ls shared_prefs   # free_checks.xml only: no pass, no intro's yes
   ```

   A restore can also be started by hand, for an installed app (it is stopped for it): `adb shell bmgr list sets`
   gives the backup's token, then `adb shell bmgr restore <token> net.unfret.android`.

5. **First launch after the restore.** Point it at dev again, open Unfret, tap **I agree**. The app makes a pass
   *without* free checks and says why: **This phone has had its free checks already, so your new Unfret pass starts
   without them.** The home screen shows 0 checks; quick checks stay free.

To start over (a phone that never had its free checks): `adb shell bmgr wipe <transport> net.unfret.android`, then
uninstall.

## A pass link

The "Use on your phone" QR code on unfret.net's My checks encodes `https://unfret.net/pass#pass=ufk_…` (on dev:
`https://dev.unfret.net/pass#pass=ufk_…`). The app takes that path only, reads the pass from the fragment, and asks
before using it.

```bash
# Quote the address twice: once for your shell, once for the phone's (the # would start a comment there).
adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE \
  -d "'https://unfret.net/pass#pass=ufk_…'"
```

- Without a pass on the phone: **Use this pass here?** with the pass's ID (ABCDE-FGHIJ), **Use this pass** or **Not
  now**. With another pass: it names the one in use, with **Use the new pass** or **Keep the current one**.
- On a first launch the intro comes first, then the question; a pass from the link means no free pass is made.
- A link with a broken or missing pass: "That link has no Unfret pass in it."
- A debug build also takes `dev.unfret.net` links, and switches to the dev server when it uses such a pass.

Until the link is verified (below), Android may open it in the browser instead; add `-p net.unfret.android` to the
`am start` above to send it to the app anyway.

### Verification

`android:autoVerify` asks Android to check that unfret.net names the app in
`https://unfret.net/.well-known/assetlinks.json`, with the release certificate's SHA-256 (`3E:71:EF:6B:…`, in
[release-cert.sha256](release-cert.sha256)). **It only succeeds once production serves that file (the next backend
deploy)**, and only for a release build: a debug build is signed with another key.

```bash
adb shell pm get-app-links net.unfret.android          # unfret.net: verified (or none / legacy_failure before)
adb shell pm verify-app-links --re-verify net.unfret.android   # ask again, for example after the deploy
```

When it isn't verified, a tap on the link opens the browser, where `/pass` forwards to `/check` (which takes the same
pass); the user can still turn the link on under the app's **Open by default** settings.
