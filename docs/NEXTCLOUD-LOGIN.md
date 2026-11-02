# Nextcloud sign-in in Recipes

Two ways in, on the same screen (Settings, Nextcloud): the browser route and the typed route.
Both end with the same thing, an `Account` (server, login name, app password) that is sealed
with the keystore and used for every DAV call with HTTP Basic.

## The typed route (the one that has worked for everyone)

"Type an app password instead" shows two more fields. The person enters the server address, their
login name and an app password they made in Nextcloud (Personal settings, Security, Devices and
sessions). Pressing Sign in builds the account from exactly what was typed (the address gets
`https://` added if missing; `http://` is refused) and calls `Cloud.signIn`, which probes the
Cookbook folder on the server with those credentials. Nothing depends on a browser, on the
server's login page, or on what the server believes its own address to be. A wrong value shows
"refused" or "unreachable" straight away, on the same screen.

## The browser route (Login Flow v2)

Sign in with only the address filled in: the app POSTs `<server>/index.php/login/v2`, opens the
returned `login` URL in the phone's browser (ACTION_VIEW, only if a real browser exists, else it
falls back to the typed fields), then polls the returned endpoint every 2 seconds for up to
20 minutes. When the person has granted access in the browser, the poll returns `server`,
`loginName` and `appPassword`, and those (not what was typed) become the account.

## Why it could fail for that user (from the code; not proven)

I cannot tell which of these it was without a log from their phone.

1. The account is built from the server's reply. The `server` value comes from the Nextcloud's
   own config (overwrite URL, trusted domains, reverse proxy), not from the address typed. If it
   differs from the typed one (an internal name, `http`, another port), the sign-in probe goes to
   that address and fails with "unreachable" or "refused", which reads as a login error.
2. The sign-in happens in a separate app. The Kompakt's browser and a Manta-style e-ink browser
   may not complete the server's login page (JavaScript, cookies, 2FA prompt, the "grant access"
   page), or the person may not come back to Recipes: nothing returns them, the app only polls.
3. The poll hides its failures. Any network error or non-404 problem while polling is swallowed
   (`getOrNull`/`null`), so a failed hand-over looks like "still waiting" and a stuck
   "Finish signing in in the browser" line, with no message and no fall back to typing.
4. The server's own brute-force throttling or a login-flow restriction would show as an error
   page in the browser, outside the app's control.

The only thing the app does differently in the typed route is skip 1 to 4, which fits what the
user saw. Suggested fix when converting: make the typed route the first and obvious choice (or
the only one), keep the browser route as an optional second button, and when it is kept, use the
typed address instead of the reply's `server` and show a message when polling errors.

## Other apps with a Nextcloud browser sign-in (read 2 Nov 2026, not changed)

Each carries its own copy of `LoginFlow` (`index.php/login/v2`) and a typed app-password route,
with the typed route offered only as a second button or after the browser fails to open:

- Memo (oboegaki): `sync/Nextcloud.kt`, started from `ui/SettingsScreen.kt`
- Pictures (garo): `backup/Nextcloud.kt`, started from `ui/GalleryViewModel.kt`; typed fields in `ui/SettingsScreen.kt`
- Shelf (shoka): `cloud/Nextcloud.kt`, `ui/Dialogs.kt`; also reads the QR code Nextcloud shows beside a new app password
- Dream Log (yumecho): `cloud/Nextcloud.kt`, `cloud/DreamSync.kt`; `ui/NextcloudScreen.kt` already lists the app password as a choice

Other apps that talk to a Nextcloud through DAVx5 or a typed account were not checked.
