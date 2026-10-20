# Privacy

Recipes collects nothing about you and sends nothing anywhere of its own accord.

It goes online for one thing: to fetch a recipe page whose address you shared with it or typed
into it. That one page is fetched, as a phone's browser would ask for it; no pictures or scripts
from it are fetched, and nothing about you or your recipes is sent with the request. The site
sees a request for its page, as it would from any browser.

It keeps, in its own private storage on the phone: your recipes, one small file each, which
ingredients you have ticked in each, and whether the letters and page dots are shown beside the
list. None of it is backed up by the app, and uninstalling it removes all of it. Settings can
save every recipe to a file you choose, and bring recipes in from one.

It hands something to another app only when you press for it: a shopping list to Notes (or, with
no Notes on the phone, to the app you pick), a timer to the clock app, a recipe as text or a file
to where you send it, the page a recipe came from to your browser. The About dialog's last line
opens a web address in your browser, and only when you press it.

## Permissions

- **Internet** — to fetch the recipe page you send it, and for nothing else.
- **Set an alarm** (`com.android.alarm.permission.SET_ALARM`) — Android requires it of any app
  that asks the clock app for a timer. It is not one you are asked for, and it lets the app ask
  for a timer, not read your alarms.
- The last is not one you are asked for either: Android's support library adds it to every app
  built with it (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), so that the app's own internal
  messages can reach only the app itself.
