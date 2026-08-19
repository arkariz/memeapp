# memeapp v1 — Meme Asset Production Brief (T-005)

**Status:** Draft v1 · **Date:** 2026-08-19
**Companions:** [PRD](memeapp-v1-prd.md) · [Tech plan §7](memeapp-v1-tech-plan.md#7-content-pipeline-templates-memes-gifs) · [Roast pack](../assets/roast_pack_v1/roast_pack.json) · [Tone guide](../assets/roast_pack_v1/TONE_GUIDE.md) · [Figma](https://www.figma.com/design/oYbpumicFw6I9f94SlqKiS)

---

## 0. Recommendation — read this before the brief

**Build one original illustrated mascot in flat brutalist style, not photography.** Three reasons, in order of how much they should move you:

1. **The current Figma build already answers this question.** The mock screens use found meme photos (Monkey Puppet, Success Kid, Waiting Skeleton) as stand-ins, and they visually fight the UI around them — photographic texture and real-world lighting sitting inside a flat-color, hard-shadow, Anton-type vector system. An illustrated character built in the *same* visual language (flat fills, thick ink outlines, the exact paper/ink/yellow/red/green token palette) would look like it was designed alongside the app instead of pasted into it.
2. **It's the cheaper, faster, lower-risk path**, not just the prettier one: no models, no shoot, no location, no usage-rights negotiation, no risk of a "wait, is that template actually copyrighted" question resurfacing after launch (it is, always — see §3).
3. **It's the only route that produces brand equity.** A recurring mascot across 9 expressions is a character people recognize — the Duolingo-owl mechanism the PRD's own growth thesis leans on. A grab-bag of stock reaction photos never becomes "that meme app's thing" the way a consistent character can.

The brief below is written for that route. §5 covers the two alternatives (photography, licensed stock) for comparison if you want to weigh in before committing budget — that's a real decision with cost and timeline attached, so it's flagged as a question at the end rather than decided unilaterally here.

## 1. Character Concept

One mascot, nine expressions/poses. Keep the *form* constant (same silhouette, same proportions, same construction) and vary only pose, expression, and props — that repetition is what makes it read as a character rather than nine unrelated drawings.

**Construction:** simple geometric base (circle or rounded-blob head/body, minimal limbs) — deliberately closer to an emoji or a simple mark than to a detailed character-sheet illustration. It needs to read at ~180px wide on a phone screen and survive a screenshot compression pass.

**Line & fill:** thick ink outline (heavier than the UI's 3px strokes — think 6–8px at this scale, so the character holds its own next to bold Anton type), flat color fills, **no gradients, no photorealistic shading, no drop-shadow-as-depth** (the UI's hard offset shadow is the only shadow language in this product — apply it to the *frame*, not inside the illustration).

**Palette — reuse the existing tokens, nothing new:**
| Token | Hex | Use |
|---|---|---|
| `color/paper` | `#FFFFFF` | base / highlights |
| `color/ink` | `#0D0D0D` | outlines, all linework |
| `color/yellow` | `#FFE600` | primary accent (skin/body fill on default state) |
| `color/red` | `#FF2E00` | frustration/heat accents (tier 3, "this_is_fine" flames) |
| `color/win` | `#16DB65` | win asset only — never appears in a roast asset |
| `color/gray` | `#6B6B6B` | secondary/muted details only |

No new colors. If a pose needs a tenth color, that's a signal the brief needs revising, not the palette.

## 2. Asset List — Roast Set (8)

These map 1:1 to the `asset` refs already committed in `roast_pack.json` — filenames must match exactly. Composition briefs describe the *character's* pose/expression only; there is no background scene needed (frames sit inside bordered slots the app already draws — see §4).

| id → filename | Used in (tier) | Emotional job | Composition |
|---|---|---|---|
| `side_eye.webp` | 1 (t1_01, 04, 05, 10) | Caught-in-the-act suspicion, no malice — the default/workhorse expression | Character turned 3/4, eyes cut sideways toward viewer, one brow raised, mouth flat. Not scowling — *noticing*, not judging. |
| `unimpressed_cat` → `unimpressed_cat.webp` | 1–2 | Flat deadpan disbelief | Face-on, half-lidded eyes, perfectly flat mouth line, arms crossed. The "really?" look, held a beat too long. |
| `confused_math.webp` | 1–3 | The numbers don't add up | Character tilted head, one hand pointing at a scribbled "?" or a lopsided equals sign, brow furrowed in mild bewilderment — not distress. |
| `philosoraptor.webp` | 1–3 | Overthinking a question that has an obvious answer | Classic "thinker" pose — chin resting on fist, eyebrow arched, gazing into middle distance as if this deserves real consideration. |
| `surprised.webp` | 1 | Mild, genuine shock at the number | Eyes wide (simple circles), small "o" mouth, hands raised slightly at shoulder height. Startled, not scared. |
| `stonks.webp` | 1–2 | Absurdist mock-corporate triumph | Character in a tiny ill-fitting tie, deadpan confident stare direct to camera, one thumbs-up, small upward-arrow prop beside them. The joke is the mismatch between the pose's confidence and what it's celebrating. |
| `this_is_fine.webp` | 1–3 | Calm acceptance in the middle of a mess | Seated, holding a simple mug, small content closed-eye smile, a couple of minimal flame or scribble shapes in the background (red accent) — character itself stays completely serene. |
| `waiting.webp` | 2–3 | Patient, indefinite waiting — the negotiation/extension mood | Seated or slouched, arms loosely crossed, one foot slightly forward as if tapping, gaze somewhere off-frame. Tired, not annoyed. |

## 3. Asset List — Win Set (1, v1 scope)

**Not part of `roast_pack.json`** — this lives outside the roast schema entirely, as a directly-referenced bundled asset for the success-card renderer (T-202). Don't add it to the roast pack's `assets` map; it isn't tier- or slot-selected, it's a fixed asset for one screen.

| id → filename | Used in | Emotional job | Composition |
|---|---|---|---|
| `win_default.webp` | Success card (estimate beaten) | Pure, uncomplicated pride — zero irony, the one moment this product is allowed to be sincere | Mid fist-pump or double thumbs-up, big open grin, eyes closed or crinkled in genuine joy. This is the only asset that gets `color/win` green as a prop/accent (e.g. a small badge or star). |

**Explicitly out of v1 scope:** a streak-milestone variant and a weekly-report-card asset are real ideas but belong to T-303 (P1, weekly report-card meme) — don't produce them now. Nine assets is the full v1 brief; resist the urge to round up to a full set "while we're at it."

## 4. Technical Spec

- **Format delivered:** static WebP with **transparent background**. The app's existing bordered slot frame (already built in Figma/production UI) supplies the surface color per context — this matters because the same character needs to read correctly on a black background (roast overlay) *and* a yellow background (success card), which a baked-in background color would break.
- **Canvas:** 3:2 landscape, 960×640px source, exported so the character has consistent breathing room and isn't cropped tight to any edge (frames vary slightly by screen; don't compose right up to the canvas boundary).
- **Line weight & contrast:** thick enough ink outlines that the character silhouette reads correctly against both a white/paper surface and a dark surface without a background swap — test both before sign-off.
- **File size:** target well under 300KB per static asset (the pack cap in tech plan §7 is 2MB/asset — static illustration should use a fraction of that, leaving headroom if any asset later gets an animated version).
- **Naming:** exact match to the `asset`/`file` values already in `roast_pack.json` — `side_eye.webp`, `unimpressed_cat.webp`, etc. Any mismatch fails the pack's hash-validation loader silently falls back to... nothing, since this *is* the bundled pack. Get the names right the first time.
- **Animation (P1, not v1):** the pipeline (tech plan §7) already treats animated WebP and static WebP identically — an animated version of any asset is a drop-in file replacement with zero app-code change, whenever there's budget for it. Don't build animation into v1's scope or timeline; note it as a natural upgrade path only.

## 5. Licensing & Rights (non-negotiable)

- **100% original artwork**, illustrated or vector-built specifically for this brief. No tracing, referencing-too-closely, or "inspired by" a specific existing meme template's actual copyrighted image — the composition briefs above describe an *emotional beat*, not a template to reproduce. (This is exactly the exposure the PRD flagged as an open question — this brief closes it.)
- No real recognizable people, no likeness of any public figure, no third-party trademarks, logos, or characters.
- If produced by a hired illustrator: get a written work-for-hire or full IP-transfer agreement before payment, not after delivery.
- If produced with an AI image tool: confirm the specific tool's output-ownership terms before committing — these vary by provider and change over time; verify at time of production, not from this document.

## 6. Alternatives Considered (if you want to reconsider §0)

| Route | Pro | Con |
|---|---|---|
| **Illustrated mascot (recommended)** | Matches existing UI language, zero licensing risk, cheapest, builds brand equity | Requires an illustrator or vector-design time; less "instantly meme-familiar" than a photo at first glance |
| Commissioned photography (real models) | Photographic memes are the format people recognize as "meme" | Needs models + shoot + signed releases; visually clashes with the current flat-vector UI; a real human face reacting can read more personally judgmental than a mascot, working against the tone guide's "roast the action, not the person" rule |
| Licensed stock photography | Fastest to acquire | Weak brand differentiation, ongoing licensing terms to track, same UI-clash problem as photography generally, risk the stock image itself becomes a recognizable "oh that's a stock photo" moment |

---

**Next step once a route is confirmed:** commission/produce the 9 assets against this brief, drop them into `assets/roast_pack_v1/` with the exact filenames above, and fill in the real `sha256` values in `roast_pack.json`'s `assets` map (currently `"TBD_T-005"` placeholders) — that hash is what the pack loader validates against, so it's the actual close-out signal for this task.
