# Unfret privacy policy (Android app)

_Last updated: 25 November 2026 (no pass: every check is free and tied to no one; a deep check solves a small puzzle on the phone; nothing in Android's backup. Earlier: the app made its own pass; "Unfret pass" wording; contact hello@unfret.net). Draft for the store listing; to be reviewed before publication._

Unfret is made by Kroshtan (Floris van Beers, the Netherlands). The app works like the Unfret browser extension
and follows the same rules; the extension's policy is in its repository, the server's at <https://unfret.net/privacy>.

## What the app reads

Nothing, until you tap **Check this page** or **Quick check**. Sharing a link to Unfret, or pasting one, opens the page in the app's
built-in browser view, over your phone's own internet connection, as any browser would; Unfret's server is not
involved in loading it.

When you tap either, the app reads the page shown, on your phone: product facts (title, brand, seller,
price, ratings, review texts and dates, shop policies, hidden text, checkboxes, countdowns), what the shop or
company publishes about itself (its phone numbers, e-mail addresses and address from its contact, legal or imprint
section, footer or structured data; registration and licence numbers; payment methods it mentions) and the visible
product text. To recognise the apps a shop runs (dropshipping, review-import and countdown apps), it also reads the
names in the page's code (class names, ids and data attribute names, never what they hold) and which other sites the
page refers to (the site, and for a link also its path, never what follows a `?`). On a service company's own site
it also reads the testimonials the company publishes; on a provider's profile on a platform, the platform's figures
and its reviews, without the reviewers' names. On a private seller's ad it reads the ad's text, the item details and
what the platform shows about the seller's account. **The seller's name is never read.**

Before anything leaves your phone, account menus, greetings, forms and input fields, checkout, billing and delivery
areas, and any address that isn't in the business's own contact section are removed from a copy of the page; inside
those areas not even the names in the code are read, and on a private seller's ad no other sites are. These
rules keep your own name, address, e-mail address and phone number on your phone; when in doubt, the app strips.
The page itself is not changed. The code that does this is the browser extension's own.

## What is sent, and to whom

Only to the Unfret API (`unfret.net`, hosted in the EU), when you tap "Check this page" or "Quick check": the page
address, and the product facts and text described above. **Nothing that identifies you**: no pass, no account, no
e-mail, no device identifier (no Android ID, advertising ID, App Set ID or Play Integrity). Requests name the app and
its version (`Unfret-Android/0.1.0`), not your phone.

**A deep check's puzzle.** Before a deep check the app asks `unfret.net` for a small puzzle
(`GET /v1/analyze/challenge`), solves it on the phone (a proof of work that keeps free checks fair) and sends the
answer with the check. The puzzle names no one. To keep free checks fair the server also counts checks per network
for a short time, as described at <https://unfret.net/privacy>; the app sends nothing for that.

When a check screen opens, the app asks `unfret.net` whether free deep checks are paused this month
(`GET /v1/pricing`), with nothing about you.

A result that found the same product on another shop shows both photos as small thumbnails, which the app loads
from `unfret.net` itself (`/v1/images/…`): your phone never contacts the other shop.

The app makes no other requests: no analytics, no trackers, no advertising or crash-reporting services, no Google
Play Services. The page you open is loaded by Android's system WebView, which talks to that page's own servers
like any browser; its usage statistics are switched off for this app.

"Your shop? Object to this result" and "Privacy policy" open unfret.net in your own browser, and so do "Support
Unfret" and the donate link in the version downloaded from unfret.net (the Google Play version has neither);
whatever you do there is covered by the website's policy, not by the app.

## What is stored on your phone

- that you accepted the intro screen (and, in test builds only, which server to use).

Nothing about the pages you check. The built-in browser starts empty for every page; when you close it, its
cookies, site storage, cache and other files are deleted and its process ends. Results are shown, never saved.
Nothing from the app goes into Android's backup or a transfer to a new phone. Uninstalling the app removes
everything.

Versions up to 0.1.0 kept an Unfret pass on the phone (encrypted), and one yes or no in Android's backup. The first
launch of a later version deletes both from the phone, and the Keystore key that sealed the pass.

## On the server

What the server keeps about a check (nothing that links the page to you) is described at
<https://unfret.net/privacy>.

## Questions

Write to [hello@unfret.net](mailto:hello@unfret.net).
