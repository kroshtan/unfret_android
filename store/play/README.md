# Google Play listing images

The images for the app's Google Play store listing, and what makes them. Regenerate everything with
`make play-store-images` (or `store/play/screenshots.sh`).

| File | Size | Play Console field | What it is |
| --- | --- | --- | --- |
| `icon-512.png` | 512×512, 32-bit PNG (RGBA) | App icon | The launcher icon's tile (`#1F8A70`) and mark, full bleed: Play adds its own rounding and shadow |
| `feature-graphic-1024x500.png` | 1024×500, 24-bit PNG | Feature graphic | The mark, "Unfret" and the tagline "Check before you pay.", in the style of the extension's promo tiles; no prices, no buying words, no shop or brand names |
| `screenshots/1-home.png` | 1080×1920 (9:16), 24-bit PNG | Phone screenshots | The home screen after the first start: the link box and 5 free checks |
| `screenshots/2-shared-page.png` | same | Phone screenshots | A link shared to Unfret: the page opens inside the app, with Check this page and Quick check (free) |
| `screenshots/3-quick-result.png` | same | Phone screenshots | The free quick check's result: Think twice, with what was noticed |
| `screenshots/4-check-running.png` | same | Phone screenshots | A deep check running ("Checking… 8 steps done") |
| `screenshots/5-deep-result.png` | same | Phone screenshots | The deep check's result: the same product photo found on another (made-up) shop |
| `screenshots/6-deep-signals.png` | same | Phone screenshots | The deep result's signals: fake countdown, pre-ticked paid extra, unbelievable discount, what you can do |
| `screenshots/7-deep-checklist.png` | same | Phone screenshots | What we checked: the five areas, each with what stood out |

## What the screenshots show

The **play build** (the `play` build type, as `bundlePlay` builds it for Play: no prices, no "Get checks"), unchanged
except for being signed with a throwaway key so the emulator installs it, on an Android 13 emulator (no Google Play
image) at 1080×1920 and 420 dpi, with a tidy status bar (Android's demo mode) and gesture navigation.

The page checked is the **Unfret test shop** (unfret.net/test-shop: Unfret's own made-up shop "Nebulora Home", every
trick on it fake on purpose, with a banner saying so), shown at the made-up address
`https://nebulora-home.example/products/galaxy-star-projector`: the app doesn't check pages on unfret.net itself. No
real shop, brand or product appears. The shop's announcement bar, which names a shipping price, is hidden.

The app talks to a stand-in for unfret.net (`tools/mock_server.py`): the emulator's hosts file points unfret.net and
the shop's address at it, and a throwaway CA, made for each run and put into the emulator's system certificate store,
lets the app trust it. It answers with canned responses: a new pass with 5 free checks, the balance, and the two check
results in `tools/fixtures/`. `deep.json` is a real deep check of the test shop (captured for the extension's
store screenshots), with the prices taken out of its words (the pre-ticked extra's price, and the other shop's price
in "sold elsewhere for less"); `quick.json` is a quick check of the same page in the API's shape. Everything else (the
test shop's page and files, the result's photos) is passed through from the real https://unfret.net, read only.

## How they're made

- `screenshots.sh`: builds the play APK and signs it with the throwaway key (both in the Android SDK container the
  Makefile pins), runs `tools/shoot.sh` in that container with `/dev/kvm`, then `graphics.mjs`.
- `tools/shoot.sh`: downloads the system image once (about 1 GB, into the git-ignored `.home/sdk`), boots the
  emulator, puts in the CA and the hosts entries, starts the mock, installs the APK and walks through the app
  (`tools/ui.py` finds and taps views by their text), capturing each screen into `.home/play-raw/`. The deep check is
  held mid-stream for the "running" screenshot.
- `graphics.mjs`: renders the icon and the feature graphic, and writes the captures out as 24-bit PNGs (Play's
  screenshots take no alpha), in headless Chromium.

Needs docker, `/dev/kvm`, and on the host node with Playwright's Chromium (`npm ci` in this folder, then
`npx playwright install chromium`). The icon and the feature graphic alone: `node store/play/graphics.mjs`.
