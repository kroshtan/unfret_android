# Changelog

## 0.1.0 (unreleased)

The first version: debug builds only.

- In Android's share sheet as "Check with Unfret" (a link, or text with a link in it), and openable from the
  launcher with a field to paste a link.
- The page opens in a visible WebView over the phone's own connection (HTTPS only; plain `http://` links are opened
  as `https://`). "Check this page" runs the browser extension's extractor and sanitiser (bundled from
  `unfret_extension@d225b28`, see `extractor/`) and sends the page facts and cleaned text to
  `POST /v1/analyze/stream` with the user's API key, exactly as the extension does; steps are counted while it runs.
- The result screen: the band with its symbol and label, the headline, what stood out and the good signs (good signs
  first, and at most one reason, for a reliable page), "No check was used" when none was; light and dark theme in
  Unfret's colours.
- The API key is pasted once, checked with the server, and kept encrypted with an Android Keystore key. The home
  screen shows the checks left (`GET /v1/credits`). "Get checks" and "Have a code?" open unfret.net's checkout in the
  browser; nothing is sold in the app.
- Privacy: an intro to accept first; the WebView runs in its own process whose data is deleted, and which ends,
  when the page is closed; no backups; no third-party SDKs; requests identify only the app and its version.
- Build: pinned docker images (`make build|apk|test|lint|check|extractor`), Gradle wrapper 9.9.1, AGP 9.5.1,
  compile/target SDK 36, min SDK 26. CI on the public runner.
