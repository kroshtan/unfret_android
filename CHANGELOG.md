# Changelog

## 0.1.0-beta.2 (unreleased)

- **5 free deep checks on a new install**, as the browser extension gives on install. After the intro, the app makes
  its own Unfret pass: `GET /v1/keys/challenge`, the proof of work solved on the phone (on the phone's cores, never
  easier; 18 bits takes well under a second on an emulator, a "Still working" line appears if it takes more than a
  few seconds), then `POST /v1/keys` with `"free": true`. The pass is kept like a pasted one (sealed by the Android
  Keystore), with its backup code, which the home screen shows under **Backup code** (the phone holds the only
  copy). The setup card says "You have 5 free checks" in the extension's welcome words; a pass that came with none
  (the server's daily limit per network) says "There were no free checks to give just now". No pass is made when
  one is on the phone already (pasted, from a link, or from beta.1). After that, a phone without a pass gets **Get
  your free checks** (or **Get a new pass**) on the home screen, also after a pass ended (instead of the website's
  link checker). Free checks are spent before bought ones (the server's order); the home screen says "You have N free
  checks" while all the checks left are free ones.
- **Not twice on a reinstall.** Android Auto Backup now keeps one file: `shared_prefs/free_checks.xml`, a yes that
  this phone received its free checks (`res/xml/backup_rules.xml` for Android 11 and older,
  `res/xml/data_extraction_rules.xml` for cloud backups and device transfers on 12 and later; `allowBackup` is on, and
  the rules include that file only). The pass never goes into a backup. Restored at install time, before the first
  launch, the flag makes the new pass come without free checks, and the app says why: "This phone has had its free
  checks already". No device identifier is used. Testing it by hand with `bmgr`: docs/TESTING-BY-HAND.md.
- **Pass links** (Android App Link, `autoVerify`): the "Use on your phone" QR code on unfret.net
  (`https://unfret.net/pass#pass=ufk_…`, path `/pass` only; debug builds also `dev.unfret.net`) opens the app, which
  reads the pass from the fragment, checks its form, and asks first: "Use this pass here?" with its ID, or, with
  another pass on the phone, "Use the new pass" / "Keep the current one" (the website's and the extension's words).
  The pass is checked with the server before it is kept; switching from the app's own pass moves its free checks
  left (`POST /v1/keys/move-free-checks`). On a first launch the intro comes first, and a pass from a link means no
  free pass is made. The pass is never logged, saved outside the sealed store, or kept in Recents. Verification
  needs unfret.net to serve `/.well-known/assetlinks.json` (the backend's next deploy).
- **Quick checks once the deep checks are used up**: with no checks left, or only free ones while today's are all
  used ("Free deep checks are back tomorrow. Quick checks still work."), the check screen's button becomes **Quick
  check (free)** (`"tier": "quick"`), as in the extension.
- The intro says the app makes its own pass and what Android's backup keeps; it is asked again once (consent
  version 2). PRIVACY.md says the same.
- `versionCode` 2, `versionName` 0.1.0-beta.2.

## 0.1.0-beta.1 (unreleased)

The first version, released as a **beta**.

- **A beta**: `versionName` is `0.1.0-beta.1` (`versionCode` 1). The About screen says under the version that this is
  a beta, an early version that may still change, and to write to hello@unfret.net about what doesn't work. The
  Release APK workflow and `scripts/sign-release.sh` accept a version with a pre-release suffix (tag
  `v0.1.0-beta.1`), publish it as a Forgejo pre-release, and write `"prerelease": true` in its `release.json`
  (`false` for a stable release), which unfret.net/android shows as "Beta". The versionCode check compares against
  every published release, betas included, instead of `/releases/latest`, which leaves pre-releases out
  (docs/RELEASING.md, "Versions and betas").
- **The result card**, as on the website and in the extension: the same product sold elsewhere first, with both
  photos (thumbnails from `unfret.net` itself), each finding with its source ("Source: Domain registry (RDAP)"), "What
  you can do" (one next step), "What we checked" (ticked, marked, or not checked and why; "What we checked · 11
  checks"), and on a clean result "Nothing worrying in 14 checks." The sections are named as on the website ("What
  we noticed", "What looks good"). Results without these fields look as before.
- **Wrong result?** under a result reports it (`POST /v1/reports`) after you choose what the page really is, with
  the same consent text as the extension; **Your shop? Object to this result** opens `unfret.net/objection` in the
  browser. The privacy statement says what a report sends and where the photos come from.
- **The page facts a quick check needs** (the extension's quick-check parity, extension #53 and backend #235): the
  bundled extractor sends `markup_names` (the page's class names, ids and data attribute names, never what they hold)
  and `external_refs` (the other sites the page refers to, never a query string; none on a private seller's ad), so
  a quick check from the phone finds the same app and supplier traces as the server does in a page's HTML.
- In Android's share sheet as "Check with Unfret" (a link, or text with a link in it), and openable from the
  launcher with a field to paste a link.
- The page opens in a visible WebView over the phone's own connection (HTTPS only; plain `http://` links are opened
  as `https://`). "Check this page" runs the browser extension's extractor and sanitiser (bundled from
  `unfret_extension@4068384`, see `extractor/`) and sends the page facts and cleaned text to
  `POST /v1/analyze/stream` with the user's API key, exactly as the extension does; steps are counted while it runs.
- The result screen: the band with its symbol and label, the headline, what stood out and the good signs (good signs
  first, and at most one reason, for a reliable page), "No check was used" when none was; light and dark theme in
  Unfret's colours.
- The API key is pasted once, checked with the server, and kept encrypted with an Android Keystore key. The home
  screen shows the checks left (`GET /v1/credits`). "Get checks" and "Have a code?" open unfret.net's checkout in the
  browser; nothing is sold in the app.
- Privacy: an intro to accept first; the WebView runs in its own process whose data is deleted, and which ends,
  when the page is closed; no backups; no third-party SDKs; requests identify only the app and its version.
- The four verdicts in the website's words: Looks fine, Worth a look, Think twice, Serious concerns (the server's
  label, as before; these words are shown when a result comes without one). A verdict below "Looks fine" always names
  a reason ("Several small signals" when a result has none), and a reason's medium severity is read out as "Notable".
- The website's words for the key: an **Unfret pass**, named by its ID as the site shows it ("Unfret pass ABCDE-FGHIJ
  on this phone"), and its **backup code** (no more "API key" and "recovery code"). Only the words: the key and how
  it is stored are unchanged.
- Build: pinned docker images (`make build|apk|test|lint|check|extractor`), Gradle wrapper 9.9.1, AGP 9.5.1,
  compile/target SDK 36, min SDK 26. CI on the public runner.
