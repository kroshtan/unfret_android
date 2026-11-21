# Changelog

## Unreleased

- **The page reader reads Amazon's mobile page's price, and reference prices and reviews on every shop**
  (unfret_extension 17fe7a7). The app's WebView gets Amazon's mobile page, whose price the reader missed: a check
  sent no price, so a discount against the "Adviesprijs" (List Price, RRP, UVP) went unseen and the result said
  "Worth a look" where the server's own read said "Think twice". Now the price to pay, the reference price, the
  rating and review count, the seller and the top reviews with their titles and texts; bol.com's price, reference
  price, partner seller, star distribution and reviews; WooCommerce, Judge.me, Loox, Stamped and eBay's reference
  prices and ratings; JSON-LD strike-through prices and separate ratings; a shop's "About us" link.
- **The page reader sends a listing's variant options** (unfret_extension ece8ba0): each picker dimension (size,
  colour, style, amount) with its options, their prices when the page shows them, the selected one and which option
  the price shown belongs to, from Amazon, Walmart, Shopify, WooCommerce, schema.org or the page's own picker, so the
  deep check can tell different products grouped as one listing's sizes, and a price shown for one of them.
- **The page reader sends every product page's gallery first** (unfret_extension 29d4415): the structured data's or
  Open Graph main photo, AliExpress's and Temu's page data, Shopify's product JSON and gallery markup (zoom
  attributes, carousels), one photo per size, at most 12, so the deep check's photo steps see the product's photos
  on any shop, not only on Amazon.
- **The page reader sends an Amazon page's whole gallery** (unfret_extension 167638d, `extractor/EXTENSION_COMMIT`).
  On Amazon's mobile page the photos sent were the main image and the page's other images (banners, pixels); now
  the carousel's large photos, in order, at most 12, so the deep check's photo steps see more than photo 1. The
  refresh also brings the extension's Amazon brand (from the byline, in every language) and seller (the current offer
  box's combined labels) reading.
- **Releases go to Google Play by themselves.** The "Release APK" workflow now uploads the signed app bundle to
  Google Play and releases it (`scripts/play-upload.py`, the Android Publisher API with the service account in the
  repo secret `PLAY_SERVICE_ACCOUNT_JSON`): named after versionName, with this version's CHANGELOG section as its
  release notes, on the track the run's new `play_track` input names (`production` by default, `internal`, or
  `none`). A dry run checks it against Play without uploading; a versionCode Play has already is never sent again
  (docs/RELEASING.md, "Releasing on Google Play").
- **Progress counts checks, as the result does.** While checking, the app said "Checking… 15 steps done" and the
  result then said "What we checked: 9 checks in 4 areas": the progress counted every step of the analysis (reading
  the page, classifying it, triage) and the result only the checks. With a server that marks the steps that are checks
  (`check` on the stream's `step` events), the line now reads "Checking… 9 checks done", ending on the result's
  number (`Progress`); an older server's steps are still counted as steps. A deep check that falls back to a quick
  one counts the quick check's checks from zero.
- The reason shown for a result without any (an older server's) is the server's own "overall" reason in its new
  words, "Nothing clear-cut", instead of "Several small signals". The server now counts and names the small signs
  itself ("Two small signs: …"), and marks their areas in "What we checked".

## 0.1.0 (2026-11-20)

The first stable version, and the first one for Google Play: the direct download (`unfret-0.1.0.apk`, signed with
the release key) and the app bundle for Play (`unfret-0.1.0.aab`, the `play` build, signed with the upload key) come
from the same commit. Since 0.1.0-beta.2:

- **The Google Play build opens the site's Play variant of its four pages**: the privacy policy, the objection
  page, about and the terms open as `<page>?app=play` (`Storefront.infoPage`), which unfret.net serves without the
  site's menu and footer and with no link to buying, the text itself unchanged. The Play build opens no other
  version of them (`Storefront.mayOpen` wants `?app=play`), so no page it opens links to the checkout or `/money`.
  The direct download opens the normal pages, as before.
- **Google Play listing images** in `store/play/`: the 512 px icon, the 1024×500 feature graphic and seven phone
  screenshots of the play build (no prices, no buying words), made on an emulator against a local mock server with
  made-up shops (`make play-store-images`; store/play/README.md).
- No longer a beta: the About screen drops the beta sentence, the Forgejo release is a normal one and its
  `release.json` says `"prerelease": false`.
- `versionCode` 3, `versionName` 0.1.0.

## 0.1.0-beta.2 (2026-11-20)

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
- **The free quick check, always on offer** (the extension's #58): under "Check this page" (a deep check: free checks
  first, then bought ones) a secondary **Quick check (free)**, "Reads this page only, by Unfret's rules.", before
  every check. With no checks left, or only free ones while today's are all used ("Free deep checks are back
  tomorrow. Quick checks still work."), the main button is the quick check and it isn't offered twice; the check
  screen asks for the balance when the page opens. A deep check the server refuses for want of checks becomes a
  quick one, and the result says why ("You have no checks left, so here's a quick check instead."). Quick checks
  work without a pass (`"tier": "quick"`, no `Authorization`), so the home screen opens a link without one. The
  per-minute limit says "Too many quick checks in a minute; wait a moment. No check was used." After a quick result
  the deep-check suggestion works as before (without a pass it leads to the home screen's free checks). Real
  buttons, 48dp, in reading order; the quick button's description includes its hint.
- The intro says the app makes its own pass and what Android's backup keeps; it is asked again once (consent
  version 2). PRIVACY.md says the same.
- **A Google Play build** (the `play` build type, `bundlePlay`): it sells nothing and points nowhere to buy, as
  Google Play's payments policy asks of an app that doesn't use Play Billing. No "Get checks", no line that checks
  are bought on unfret.net, no price (a quick result suggests a deep check only when the pass can run one now, and
  without the server's pack price), and no link to the checkout or `/money` (only the privacy, objection, about and
  terms pages open); its words without buying are in `src/play/res`. **Have a gift code?** redeems a `ufi_…` code
  in the app, onto the pass in use (`POST /v1/invites/redeem`), with the refusals in the app's words; with no checks
  left the home screen says "You have no deep checks left. Quick checks are free." Free checks on install, quick
  checks, a pass pasted or from a link, and the balance are as in the direct download, which is unchanged (its
  release APK is still `assembleRelease`). Decided when the app is built, not by the installer.
- `versionCode` 2, `versionName` 0.1.0-beta.2.

## 0.1.0-beta.1 (2026-11-18)

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
- **The result card says more, as the website and the extension do** (backend #261, extension #61): when part of
  the check didn't finish (the AI read of the page), a line under the band says so ("Part of this check didn't finish
  (the AI read of the page), so this answer may be milder than it should be. It still counted as a check. Checking
  again after an hour may help."); "What we checked" adds up ("What we checked: 16 checks in 5 areas", each area with
  its count, "Who's behind the shop · 4 checks"; a clean area is read out as "nothing to note"); and what the check
  cost is a sentence, the server's `charge_note` ("This check used one of your free checks; 4 are left."; "You
  checked this page recently, so no new check was used." under the band). With an older server, the same words
  without the count.
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
