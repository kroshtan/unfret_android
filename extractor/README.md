# The page reader (the extension's extractor, bundled)

The app reads pages with the **browser extension's own code**: `src/extract/` of
[kroshtan/unfret_extension](https://git.kroshtan.com/kroshtan/unfret_extension), the same TypeScript that the
extension injects into a tab, including its privacy sanitiser (`clean.ts`, `business.ts`). It is bundled with
esbuild (the extension's pinned version, 0.28.2) into one readable file,
`app/src/main/assets/unfret-extract.js`, which the app evaluates in its WebView.

| File | What |
| --- | --- |
| `EXTENSION_COMMIT` | the extension commit the bundle is built from |
| `entry.ts` | `window.__unfretAndroid.start()` runs `extractPage(document, location.href, { live: true })` (the extension's call); `poll()` returns the state as JSON, because `evaluateJavascript` can't wait for a promise |
| `build.mjs` | the bundler (`--check` compares with the committed bundle) |
| `fetch-extension.sh` | puts the source at that commit in `.extension/` (git-ignored), from the forge's archive |
| `test.mjs` | runs the bundle the way the app does over the extension's fixture pages (jsdom): the same PageFacts as the extension's parity expectations, and none of the planted personal details |

## Refreshing it after the extension changes

```bash
git -C ../unfret_extension log -1 --format=%H origin/main > extractor/EXTENSION_COMMIT   # or any commit
make extractor        # fetch that commit, rebuild app/src/main/assets/unfret-extract.js
make test lint        # fixtures, and the bundle check CI runs
git add extractor/EXTENSION_COMMIT app/src/main/assets/unfret-extract.js
```

The build is reproducible: CI rebuilds the bundle from `EXTENSION_COMMIT` and fails if it differs from the committed
file, so the asset can't drift from the extension's source. When the extension's extractor gains a field the server
needs, refreshing this is all the app needs; the request shape (`url`, `page_facts`, `page_content`) is the
extension's.
