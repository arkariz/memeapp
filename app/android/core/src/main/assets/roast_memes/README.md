# Roast overlay memes (user-sourced)

Real meme GIFs for the native roast overlay and its extension screens.
Same deal as `app/assets/onboarding/` — you download and drop the files
in; nothing here is fetched by the build. `RoastOverlayController` loads
them via `ImageDecoder`, so multi-frame GIFs animate automatically.

| File | Where it shows | Suggested meme |
|---|---|---|
| `<assetRef>.gif` (e.g. `side_eye.gif`, `unimpressed_cat.gif`, `confused_math.gif`, `philosoraptor.gif`, `surprised.gif`, `stonks.gif`, `this_is_fine.gif`, `waiting.gif`) | The roast screen's meme box — matched per-roast by the template's asset/mood id from `roast_pack.json` | Monkey Puppet works for `side_eye`/`surprised`; anything matching the mood |
| `negotiation.gif` | The "TYPE IT OUT." typed-phrase screen (extension #2) | Waiting Skeleton |
| `waiting.gif` | "THE WAITING ROOM." screen (extension #3+) — note this doubles as the `waiting` mood above | Waiting Skeleton / "still waiting" |

Fallback chain per slot:
1. `roast_memes/<name>.gif` (this folder — animated)
2. Roast screen only: the bundled `roast_pack_v1/<assetRef>.webp` mascot
3. Section hidden entirely (extension screens) — a missing meme never
   blocks the unlock flow

Landscape / roughly 3:2 reads best; the box center-crops at a fixed
height, it doesn't stretch. Licensing note: same exposure reasoning as
the onboarding memes (see `app/assets/onboarding/README.md`) — but the
roast overlay IS the screen users will organically screenshot, so
revisit before any production release.
