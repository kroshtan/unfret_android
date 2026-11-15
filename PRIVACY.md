# Unfret privacy policy (Android app)

_Last updated: 15 November 2026 (version 0.1.0). Draft for the store listing; to be reviewed before publication._

Unfret is made by Kroshtan (Floris van Beers, the Netherlands). The app works like the Unfret browser extension
and follows the same rules; the extension's policy is in its repository, the server's at <https://unfret.net/privacy>.

## What the app reads

Nothing, until you tap **Check this page**. Sharing a link to Unfret, or pasting one, opens the page in the app's
built-in browser view, over your phone's own internet connection, as any browser would; Unfret's server is not
involved in loading it.

When you tap **Check this page**, the app reads the page shown, on your phone: product facts (title, brand, seller,
price, ratings, review texts and dates, shop policies, hidden text, checkboxes, countdowns), what the shop or
company publishes about itself (its phone numbers, e-mail addresses and address from its contact, legal or imprint
section, footer or structured data; registration and licence numbers; payment methods it mentions) and the visible
product text. On a service company's own site it also reads the testimonials the company publishes; on a provider's
profile on a platform, the platform's figures and its reviews, without the reviewers' names. On a private seller's
ad it reads the ad's text, the item details and what the platform shows about the seller's account. **The seller's
name is never read.**

Before anything leaves your phone, account menus, greetings, forms and input fields, checkout, billing and delivery
areas, and any address that isn't in the business's own contact section are removed from a copy of the page. These
rules keep your own name, address, e-mail address and phone number on your phone; when in doubt, the app strips.
The page itself is not changed. The code that does this is the browser extension's own.

## What is sent, and to whom

Only to the Unfret API (`unfret.net`, hosted in the EU), when you tap "Check this page":

- the page address, and the product facts and text described above;
- your Unfret key (`ufk_…`), which identifies the account whose checks are used.

When you save a key, and when the home screen shows your remaining checks, the app asks `unfret.net` for the
key's balance (with the key). Requests name the app and its version (`Unfret-Android/0.1.0`), not your phone.

The app makes no other requests: no analytics, no trackers, no advertising or crash-reporting services, no Google
Play Services. The page you open is loaded by Android's system WebView, which talks to that page's own servers
like any browser; its usage statistics are switched off for this app.

"Get checks", "Have a code?" and "Privacy policy" open unfret.net in your own browser; whatever you do there is
covered by the website's policy, not by the app.

## What is stored on your phone

- your Unfret key, encrypted with a key kept in the Android Keystore (it can't be read back off the phone, and it
  isn't included in backups or device transfers);
- that you accepted the intro screen (and, in test builds only, which server to use).

Nothing about the pages you check. The built-in browser starts empty for every page; when you close it, its
cookies, site storage, cache and other files are deleted and its process ends. Results are shown, never saved.
**Remove key** removes the key; uninstalling the app removes everything.

## On the server

What the server keeps about a check (the account's balance, and nothing that links the page to your account) is
described at <https://unfret.net/privacy>.

## Questions

See the contact details at kroshtan.com.
