# GIPHY attribution badge (user-sourced)

Same deal as `roast_memes/` and `success_pack/` — you download the file and
drop it in here; nothing here is fetched by the build.

| File | Where it shows |
|---|---|
| `attribution_badge.png` | Small logo mark next to the "Powered by GIPHY" text, shown on the roast overlay only when the meme box actually rendered a live-fetched GIPHY GIF |

GIPHY's API Terms of Service require every app using the API to display
"Powered by GIPHY" text **and** the official GIPHY logo wherever fetched
content shows — this is a hard requirement, not a nice-to-have. Get the
official mark from GIPHY's developer/brand-guidelines pages
(developers.giphy.com) when you set up the API key.

Fallback: the text half of the attribution row always renders regardless
of whether this file exists — a missing logo hides that one element
(`RoastOverlayController.loadDrawable` returns null, same
graceful-hide-on-missing-asset pattern as every other image in this app),
it never blocks the roast or shows a broken-image icon. Still, don't ship
to real users without the logo in place — the text alone doesn't satisfy
GIPHY's ToS.
