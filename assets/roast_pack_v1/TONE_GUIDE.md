# Roast Tone Guide — the line between roast and shame

Every template in `roast_pack.json` must pass this before it ships. This is the concrete lexicon rule the PRD's open question asked for (P0-3, "tone style guide enforced").

## The test

**A roast targets the *action*. Shame targets the *person*.**

The user chose to keep scrolling — that choice is fair game, funny, and exactly what they installed the app to hear about. Who they are, what that says about them, or what they should feel is never fair game.

Ask: *"Would this line still make sense mocking a stranger's one-time slip, or does it only land because it's implying a pattern / a flaw / a character judgment?"* If it needs the second reading to land, cut it.

## Banned lexicon (hard no — reject on sight)

- Identity/character words: **addict, pathetic, loser, weak-willed, lazy, disgusting, gross, sad**
- Totalizing language: **always, never, every time, you always do this** (implies a permanent trait, not one session)
- Life-scale claims: **wasted your life, ruined your day, failure, waste of a human**
- Comparison-to-others shame: **everyone else can, normal people, other people don't need this**
- Anything that would read differently at 1am to someone anxious or lonely than it does at 2pm to someone bored. If a line only works for the confident, ironic-installer mood, it fails for the other 30% of moments this screen will actually appear in.

## What's allowed and encouraged

- **Mocking the specific numbers.** Math is neutral and funny: "47 minutes ago," "9x your estimate."
- **Quoting the user's own words back at them.** Their intent text is the best material in the pack — it's self-authored, so it can never feel like the app is judging from outside.
- **Absurdist non-sequiturs** that don't reference the user's character at all (Waiting Skeleton, "the universe remains unimpressed").
- **Deadpan understatement** — drier is safer than harder. Tier 3 should feel *tired*, not *angrier*.
- **Second-person address about the moment**, never about the person: "you said 5" (fair — describes an action) vs. "you can't stick to anything" (banned — describes a trait).

## Escalation shape (why tiers get *drier*, not *meaner*)

Per the PRD, extensions should get less rewarding, not more hostile — the negotiation is supposed to feel like diminishing returns, not punishment.

- **Tier 1** (first roast): full comedic energy, warmest, most personality. This is the moment that gets screenshotted.
- **Tier 2** (after 1 extension): drier, a little more "I told you so," slightly less delighted to see you.
- **Tier 3** (2+ extensions): flattest, most bureaucratic-deadpan. The joke is that the app has stopped being amused, not that it's gotten cruel. Never louder — quieter.

## Duration-only vs. intent-text templates

Every user story requires the loop to work without free-text input (PRD edge case 7). Roughly a third of each tier's templates must render fully from `{granted_min}`/`{actual_min}` alone (`requires: []`); the rest quote `{intent}` and only get selected when intent text exists.

## iOS degradation rule

Every template's `degrade` field is the two-line version for a copy-only surface (Apple shield, notification, etc.) — see PRD non-goal on iOS portability. Degradations must pass the same lexicon test independently; don't assume the full version's context carries over.
