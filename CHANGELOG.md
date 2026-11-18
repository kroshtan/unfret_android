# Changelog

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
