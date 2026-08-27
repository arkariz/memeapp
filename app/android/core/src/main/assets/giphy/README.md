# GIPHY attribution badge

| File | Where it shows |
|---|---|
| `attribution_badge.png` | GIPHY's official "Powered by GIPHY" lockup (icon + wordmark, one image), shown on the roast overlay only when the meme box actually rendered a live-fetched GIPHY GIF |

GIPHY's API Terms of Service require every app using the API to display
"Powered by GIPHY" text **and** the official GIPHY logo wherever fetched
content shows — this is a hard requirement, not a nice-to-have.

**Sourced 2026-08-24** from the user's own GIPHY Brandfolder download (the
"Static Logos" pack — Brandfolder is login-gated to the account tied to
the GIPHY API key, so this had to be a manual download, not something
fetched automatically). Specifically:
`Large/Dark Backgrounds/Poweredby_640px-Black_HorizLogo.png`, cropped to
its content bounding box (transparent background, white "POWERED BY"
text + the full-color GIPHY glyph + wordmark — despite the "Black"
filename, the rendered content is white, which is the variant meant for
placement on a dark background; the app's roast overlay uses a dark ink
background, so this is the correct pick over the "Light Backgrounds"
folder's black-chip-on-white variant).

Using the single official composite badge — rather than recreating
"Powered by GIPHY" as separate hand-built text + a small logo mark, which
is what this file used to document — is both simpler code
(`RoastOverlayController.giphyAttributionRow()` is now one `ImageView`)
and the ToS-safest choice: it's their exact approved artwork at the exact
proportions they specify, not our approximation of it.

Fallback: if this file is ever missing (e.g. a stale checkout before this
commit), `giphyAttributionRow()` falls back to a plain "Powered by GIPHY"
text line — same graceful-hide-on-missing-asset pattern as every other
image in this app, so a build never crashes or shows a broken-image icon.
Text-only is not fully ToS-compliant on its own (the logo is required
too), so that fallback exists purely as a non-crashing safety net, not as
an acceptable shipping state.
