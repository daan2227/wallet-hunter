---
version: 1
slug: "app-src-main-java-com-hunter-btc-mainactivity-kt"
primary_target: "app/src/main/java/com/hunter/btc/MainActivity.kt"
related_targets: ["app/src/main/java/com/hunter/btc/WalletActivity.kt","app/src/main/java/com/hunter/btc/MineroActivity.kt"]
---

# App shell redesign (all main screens)

Mode: Operate. Scope: the main navigation and the Hunt (Scanner, Puzzle, Weak, Recovery), Wallet and Miner screens, in both themes (Vault Gold dark and Day). Build path: code-led. The approved composition is the HTML mockups on the design canvas; they are built later in Kotlin Views.

## Direction contract

THESIS: Hunter Suite reads like security printing, where fine work exists to prove a figure is genuine. The app refuses the category default of stacked same-size cards with a small figure in each.

OWN-WORLD:
- Palette: Vault Gold slate `#0F172A` as the ground, gold `#F59E0B` for the figure and the action, violet `#A78BFA` only in the rosette, and green/amber/red only as states.
- Type: IBM Plex Sans, with figures at display size in tabular numerals. JetBrains Mono for microprint and data.
- Fine gold hairlines replace card boxes. Data sits in rows divided by hairlines.

STORY: The user glances from arm's length and reads the one figure that matters (speed, balance, hashrate) and whether it is running. Below it, the provenance rows say why the figure is true. The user then acts with one gold button.

FIRST VIEWPORT:
- App bar with the title.
- Section tabs.
- A hero zone of about 260 dp: the figure at 76 sp centred over the guilloche rosette, with its unit, its qualifier and a running state with a dot.
- A microprint band of gold hairlines carrying the real parameters.
- About four provenance rows.
- A full-width gold primary action above a 5-item navigation bar: Hunt, Wallet, Miner, Core, More.

Signature interaction: the rosette is drawn as parametric hypotrochoids on a Canvas and rotates slowly while a job runs (its speed tied to the rate). It stops dead when idle, and is static when the system "remove animations" setting is on.

FORM: candidate 6 of 7 (guilloche security print). seed:785d84ed66d3c203

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
