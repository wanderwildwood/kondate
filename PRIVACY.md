# Privacy

Recipes collects nothing about you and sends nothing anywhere of its own accord.

It goes online for two things. One is to fetch a recipe page whose address you shared with it or
typed into it. That one page is fetched, saying plainly that the request is from the app Recipes; no pictures or
scripts from it are fetched, and nothing about you or your recipes is sent with the request. The
site sees a request for its page from an app, and may refuse it; the app then offers pasted
text instead and does not try to get round the refusal. Everything is asked over https
only: an address that starts with http is tried as https, and if the site does not answer that
way the app says so and nothing is sent in plain text. Android is told the same, so the app
is not able to send plain http at all.

Recipe text you paste, or share to Recipes from the browser, is read on the phone to make a draft
and is never sent anywhere.

The other is Nextcloud, and only once you have signed in to one in Settings: then your recipes are
synced with your own Nextcloud, in the folder Nextcloud Cookbook uses, and go nowhere else. The
app also asks Cookbook on that server which folder it uses, and after a sync asks it to look at
the folder again; neither carries anything but the request. Sign-in is on the server's own page
in your browser, which gives this phone an app password of its own; you can revoke it from the
server's security settings. Before you sign in, and after you sign out, nothing goes there.

It keeps, in its own private storage on the phone: your recipes, one small file each, which
ingredients you have ticked in each (never synced), whether the letters and page dots are shown
beside the list, and, while you are signed in, the Nextcloud address, your login name, the app
password (sealed with a key held in the phone's keystore, not kept as plain text), and a note of what was last synced. None of it is backed up by the app, and uninstalling it removes all of it. Settings can
save every recipe to a file you choose, and bring recipes in from one.

It hands something to another app only when you press for it: a shopping list to Memo (or, with
no Memo on the phone, to the app you pick), a timer to the clock app, a recipe as text or a file
to where you send it, the page a recipe came from to your browser. The About dialog's last line
opens a web address in your browser, and only when you press it.

## Permissions

- **Internet** — to fetch the recipe page you send it, and to sync with your Nextcloud once you
  have signed in to one; for nothing else.
- **Set an alarm** (`com.android.alarm.permission.SET_ALARM`) — Android requires it of any app
  that asks the clock app for a timer. It is not one you are asked for, and it lets the app ask
  for a timer, not read your alarms.
- The last is not one you are asked for either: Android's support library adds it to every app
  built with it (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), so that the app's own internal
  messages can reach only the app itself.
