# Unfret privacy policy (Android app)

_Last updated: 19 November 2026 (version 0.1.0-beta.2: the app makes its own pass with free checks; one yes/no in Android's backup; a pass from a link. Earlier: "Unfret pass" wording, free checks, contact hello@unfret.net; the names in the page's code and the other sites it refers to). Draft for the store listing; to be reviewed before publication._

Unfret is made by Kroshtan (Floris van Beers, the Netherlands). The app works like the Unfret browser extension
and follows the same rules; the extension's policy is in its repository, the server's at <https://unfret.net/privacy>.

## What the app reads

Nothing, until you tap **Check this page** or **Quick check (free)**. Sharing a link to Unfret, or pasting one, opens the page in the app's
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

Only to the Unfret API (`unfret.net`, hosted in the EU), when you tap "Check this page" or "Quick check (free)":

- the page address, and the product facts and text described above;
- your Unfret pass (an anonymous key, `ufk_…`: no name, no e-mail, no account), whose checks a deep check uses. A
  quick check uses none; without a pass on the phone it is sent without one.

**Your pass.** Once you accept the intro, the app makes its own pass, as the browser extension does on install: it
asks `unfret.net` for a small puzzle (`GET /v1/keys/challenge`), solves it on the phone (a proof of work that keeps
free passes fair), and sends the answer with one yes or no, whether to include the free checks (`POST /v1/keys`).
Nothing else: no account, no e-mail, no device identifier (no Android ID, advertising ID, App Set ID or Play
Integrity). The server gives free checks only to a few new passes per network per day, and to count that it uses
the network address the request came from, as described at <https://unfret.net/privacy>. No pass is made if you
already have one on the phone. You can also paste a pass you have from unfret.net (a pack you bought, a gift code
from a friend), or open a pass link from the "Use on your phone" QR code there (`https://unfret.net/pass#pass=…`):
the app asks before it uses that pass, and the pass in the link goes nowhere but to `unfret.net`, to check it. When
you switch from the pass the app made to another one of yours, its free checks left move to that pass
(`POST /v1/keys/move-free-checks`, with the old pass).

When you save a pass, and when the home screen shows your remaining checks, the app asks `unfret.net` for the
pass's balance (with the pass). Requests name the app and its version (`Unfret-Android/0.1.0-beta.2`), not your phone.

A result that found the same product on another shop shows both photos as small thumbnails, which the app loads
from `unfret.net` itself (`/v1/images/…`, without your pass): your phone never contacts the other shop.

If you tap **Wrong result?** under a result, choose what the page really is and tap "Send report", that one check's
request (the page address, product facts and text the app sent), its result and your pass go to `unfret.net` for
review, as described at <https://unfret.net/privacy> ("Reporting a wrong result"). Nothing is sent until you tap
"Send report".

The app makes no other requests: no analytics, no trackers, no advertising or crash-reporting services, no Google
Play Services. The page you open is loaded by Android's system WebView, which talks to that page's own servers
like any browser; its usage statistics are switched off for this app.

"Get checks", "Have a code?", "Your shop? Object to this result" and "Privacy policy" open unfret.net in your own browser; whatever you do there is
covered by the website's policy, not by the app.

## What is stored on your phone

- your Unfret pass, encrypted with a key kept in the Android Keystore (it can't be read back off the phone, and it
  isn't included in backups or device transfers); for a pass the app made itself, also its backup code, encrypted the
  same way (the app shows it under **Backup code**, since the phone holds the only copy);
- that you accepted the intro screen, that the first pass was set up (and, in test builds only, which server to use);
- whether this phone already received its free checks (a yes or no; see backups below);
- for a moment, that your stored pass ended: when unfret.net no longer knows it (Unfret retired its old passes, or
  the pass was replaced with its backup code), the app removes it and keeps a yes until the home screen has said so
  once.

**In Android's backup: one yes or no.** If backup is on for your phone, Android keeps a copy of one thing from this
app in your own Google backup (and in a transfer to a new phone): that this phone already received its free checks,
so that reinstalling the app doesn't give them a second time. Nothing else from the app is backed up: never your
pass or its backup code, not the intro, nothing about pages. This yes or no stays on your phone and in your own
backup; it is never sent to Unfret. Turning off backup for your phone, or deleting its backup, deletes it there.

Nothing about the pages you check. The built-in browser starts empty for every page; when you close it, its
cookies, site storage, cache and other files are deleted and its process ends. Results are shown, never saved.
**Remove pass** removes the pass from the phone (its checks stay on the pass); uninstalling the app removes
everything.

## On the server

What the server keeps about a check (the account's balance, and nothing that links the page to your account) is
described at <https://unfret.net/privacy>.

## Questions

Write to [hello@unfret.net](mailto:hello@unfret.net).
