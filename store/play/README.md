# Google Play listing images

The images for the app's Google Play store listing, and what makes them. Regenerate everything with
`make play-store-images` (or `store/play/screenshots.sh`).

| File | Size | Play Console field | What it is |
| --- | --- | --- | --- |
| `icon-512.png` | 512×512, 32-bit PNG (RGBA) | App icon | The launcher icon's tile (`#1F8A70`) and mark, full bleed: Play adds its own rounding and shadow |
| `feature-graphic-1024x500.png` | 1024×500, 24-bit PNG | Feature graphic | The mark, "Unfret" and the tagline "Check before you pay.", in the style of the extension's promo tiles; no prices, no buying words, no shop or brand names |
| `screenshots/1-home.png` | 1080×1920 (9:16), 24-bit PNG | Phone screenshots | The home screen after the first start: the link box, "Checks are free. No account, no pass." |
| `screenshots/2-shared-page.png` | same | Phone screenshots | A link shared to Unfret: the page opens inside the app, with Check this page and Quick check |
| `screenshots/3-level-4.png` | same | Phone screenshots | A free deep check's result: risk level 4, "May not arrive, or may not be safe", the same product photo on another (made-up) shop |
| `screenshots/4-level-4-why.png` | same | Phone screenshots | The findings behind the level ("Why this level"), worst first, each with its source |
| `screenshots/5-level-3.png` | same | Phone screenshots | Risk level 3, "May not work as described": claims the page's own specs contradict |
| `screenshots/6-wrong-result.png` | same | Phone screenshots | "Wrong result?" on that result: a level picked, a finding marked wrong |
| `screenshots/7-level-2.png` | same | Phone screenshots | Risk level 2, "May cost more than it's worth": generic goods under a new name |
| `screenshots/8-level-0.png` | same | Phone screenshots | Risk level 0, "No concerns found", with what looks good |
| `../direct/screenshots/1-paused.png` | same | **Not for Play** | The direct download (release build) while free deep checks are paused: its pause line and "Support Unfret" link. For unfret.net/android and the press only |

## What the screenshots show

The **play build** (the `play` build type, as `bundlePlay` builds it for Play: no price, no donate or support link,
`X-Unfret-Donate: off` on every request), unchanged except for being signed with a throwaway key so the emulator
installs it, on an Android 13 emulator (no Google Play image) at 1080×1920 and 420 dpi, with a tidy status bar
(Android's demo mode) and gesture navigation. Checks are free with no account, so there is no pass, pack or balance
to show. Nothing about donating is in a Play screenshot: the run fails if any text on screen is about donating
(`tools/ui.py nodonate`, Storefront's words) or if any of the build's API requests lacked `X-Unfret-Donate: off`. So
the pause (whose Play line still says free checks run on donations) is shot only from the direct download's release
build, into `store/direct/screenshots/`.

No real shop, brand or product appears. The shops are made up, on `.example` addresses:

- `nebulora-home.example`: the **Unfret test shop** (dev.unfret.net/test-shop: Unfret's own made-up shop "Nebulora
  Home", every trick on it fake on purpose, with a banner saying so). The app doesn't check pages on unfret.net
  itself, hence the made-up address. Its result, `tools/fixtures/test-shop.json`, is a real free deep check of it
  (made for the extension's store screenshots on dev), with the prices taken out of its words. The shop's
  announcement bar, which names a shipping price, is hidden.
- `zentrova-audio.example` (level 3), `restwell-home.example` (level 2), `lumen-and-oak.example` (level 0): plain
  product pages the mock makes; their results (`tools/fixtures/level-*.json`) are made up by hand in the API's shape.

The app talks to a stand-in for unfret.net (`tools/mock_server.py`): the emulator's hosts file points unfret.net and
the shops' addresses at it, and a throwaway CA, made for each run and put into the emulator's system certificate
store, lets the app trust it. It answers the free-check calls with canned responses: the price list and the
proof-of-work challenge (4 bits, so solving is instant; paused while the run says so), the findings catalogue
(`tools/fixtures/catalogue.json`, dev's) and each shop's result as a stream. The test shop's page and files and the
result's photos are passed through from https://dev.unfret.net, read only.

## How they're made

- `screenshots.sh`: builds the play APK and the release APK and signs them with the throwaway key (both in the Android SDK container the
  Makefile pins), runs `tools/shoot.sh` in that container with `/dev/kvm`, then `graphics.mjs`.
- `tools/shoot.sh`: downloads the system image once (about 1 GB, into the git-ignored `.home/sdk`), boots the
  emulator, puts in the CA and the hosts entries, starts the mock, installs the APK and walks through the app
  (`tools/ui.py` finds and taps views by their text), capturing each screen into `.home/play-raw/`; then installs the
  release APK over it and captures the pause.
- `graphics.mjs`: renders the icon and the feature graphic, and writes the captures out as 24-bit PNGs (Play's
  screenshots take no alpha), in headless Chromium.

Needs docker, `/dev/kvm`, and on the host node with Playwright's Chromium (`npm ci` in this folder, then
`npx playwright install chromium`). The icon and the feature graphic alone: `node store/play/graphics.mjs`.
