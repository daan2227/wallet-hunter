---
name: Hunter Suite
description: Security print. One measured figure over a guilloche rosette, with fine hairlines carrying the provenance.
colors:
  ground: "#0F172A"
  surface: "#222735"
  surface-raised: "#272F42"
  key: "#252B3B"
  border: "#334155"
  ink: "#F8FAFC"
  ink-secondary: "#B0BBCC"
  ink-muted: "#8D9BB0"
  stop-wash: "#3A1F2A"
  vault-gold: "#F59E0B"
  on-gold: "#0F172A"
  rosette-violet: "#A78BFA"
  state-red: "#F87171"
  state-warn: "#FB923C"
  state-info: "#60A5FA"
  state-ok: "#4ADE80"
  hairline-gold: "#F59E0B59"
  hairline: "#F8FAFC12"
  day-ground: "#F8FAFC"
  day-surface: "#FFFFFF"
  day-surface-raised: "#F1F5F9"
  day-key: "#E2E8F0"
  day-border: "#CBD5E1"
  day-ink: "#0F172A"
  day-ink-secondary: "#475569"
  day-ink-muted: "#56657A"
  day-stop-wash: "#FEE2E2"
  day-gold: "#B45309"
  day-on-gold: "#FFFFFF"
  day-rosette-violet: "#7C3AED"
  day-state-red: "#B91C1C"
  day-state-warn: "#C2410C"
  day-state-info: "#2563EB"
  day-state-ok: "#15803D"
  day-hairline-gold: "#B4530959"
  day-hairline: "#0F172A12"
typography:
  hero-figure:
    fontFamily: "IBM Plex Sans Bold, sans-serif-black"
    fontSize: "76sp"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "-0.04em"
    fontFeature: "tnum"
  hero-figure-compact:
    fontFamily: "IBM Plex Sans Bold, sans-serif-black"
    fontSize: "64sp"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "-0.04em"
    fontFeature: "tnum"
  display:
    fontFamily: "IBM Plex Sans Bold, sans-serif-black"
    fontSize: "44sp"
    fontWeight: 700
    letterSpacing: "-0.03em"
    fontFeature: "tnum"
  page:
    fontFamily: "IBM Plex Sans Bold, sans-serif"
    fontSize: "28sp"
    fontWeight: 700
    letterSpacing: "-0.02em"
  figure:
    fontFamily: "IBM Plex Sans SemiBold, sans-serif"
    fontSize: "22sp"
    fontWeight: 600
  title:
    fontFamily: "IBM Plex Sans SemiBold, sans-serif"
    fontSize: "17sp"
    fontWeight: 600
  body:
    fontFamily: "IBM Plex Sans Regular, sans-serif"
    fontSize: "15sp"
    fontWeight: 400
  data-value:
    fontFamily: "IBM Plex Sans SemiBold, sans-serif"
    fontSize: "15sp"
    fontWeight: 600
  caption:
    fontFamily: "IBM Plex Sans Medium, sans-serif-medium"
    fontSize: "13sp"
    fontWeight: 500
  label:
    fontFamily: "IBM Plex Sans Medium, sans-serif-medium"
    fontSize: "12sp"
    fontWeight: 500
  mono:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "12sp"
    fontWeight: 400
  microprint:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "9sp"
    fontWeight: 400
    letterSpacing: "0.12em"
rounded:
  chip: "8dp"
  inner: "12dp"
  card: "16dp"
  key: "16dp"
  pill: "999dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  gap: "12dp"
  group: "16dp"
  side: "20dp"
  page-top: "24dp"
components:
  button-primary:
    backgroundColor: "{colors.vault-gold}"
    textColor: "{colors.on-gold}"
    typography: "{typography.title}"
    rounded: "{rounded.key}"
    height: "54dp"
  button-primary-day:
    backgroundColor: "{colors.day-gold}"
    textColor: "{colors.day-on-gold}"
    typography: "{typography.title}"
    rounded: "{rounded.key}"
    height: "54dp"
  button-secondary:
    backgroundColor: "{colors.key}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.inner}"
    height: "48dp"
  button-stop:
    backgroundColor: "{colors.stop-wash}"
    textColor: "{colors.state-red}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    height: "54dp"
  hero-figure:
    textColor: "{colors.vault-gold}"
    typography: "{typography.hero-figure}"
    height: "250dp"
  microprint-band:
    textColor: "{colors.ink-muted}"
    typography: "{typography.microprint}"
    padding: "3dp 0"
  data-row:
    textColor: "{colors.ink}"
    typography: "{typography.data-value}"
    height: "54dp"
  data-row-label:
    textColor: "{colors.ink-secondary}"
    typography: "{typography.body}"
  tab:
    textColor: "{colors.ink-secondary}"
    typography: "{typography.caption}"
    height: "48dp"
  tab-active:
    textColor: "{colors.ink}"
    typography: "{typography.title}"
    height: "48dp"
  input:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.inner}"
    padding: "12dp 16dp"
    height: "48dp"
  nav-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-secondary}"
    typography: "{typography.label}"
    padding: "10dp 0 12dp"
  nav-item-active:
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.card}"
    size: "56dp x 32dp"
---

# Design System: Hunter Suite

## Overview

**Creative North Star: "Security Print"**

Hunter Suite is printed like a banknote or a share certificate, where the fine work exists to prove that a figure is genuine. Each main screen gives one measured figure (keys per second, hashrate, balance, recovery cost) at display size, centred over a guilloche rosette drawn on the phone. Under it, a microprint band of gold hairlines records the fixed parameters the figure was measured with. Then come hairline-divided rows of provenance and a single gold action. The user reads the figure from arm's length, checks that it is running, and acts.

The ground is flat slate with no gradients and no glow. Separation comes from hairlines, not boxes: groups sit between two hairlines and line up with the 20 dp screen margin. Gold means the figure, the primary action and the current selection. Violet appears only inside the rosette. Green, amber and red appear only as states. The system ships in two themes, Vault Gold (dark, the default) and Vault Gold · Day. Both use the same grammar. Day swaps to a darker gold (`day-gold`) and darker state colours so that each one still reads on white.

The build rejects the category default of stacked same-size cards with a small figure in each. The code records this refusal wherever it replaced a card (`Ui.card`, `Ui.section`, the Scanner settings, Weak's mini stats, Send's From row).

**Key Characteristics:**
- One figure per screen, in gold Plex Sans Bold with tabular numerals, at 76 sp (Scanner, Miner) or 64 sp (Puzzle, Weak), centred over the rosette.
- The guilloche rosette turns only while a job runs, at a log-scaled rate. It is still when idle and when system animations are off.
- Microprint band: 9 sp JetBrains Mono, uppercase, between two gold hairlines. It carries fixed facts only.
- Hairline data rows, 54 dp tall, with the label left and the semibold value right. A row never repeats a figure shown elsewhere on screen.
- One gold primary per viewport. Every other button is secondary.
- Underlined text tabs, controls tinted gold, a flat ground, and no shadows.

## Colors

The palette is cold slate with a single Bitcoin gold. Violet lives only in the rosette, and the state colours are used only for state.

### Primary
- **Vault Gold** (`vault-gold`; Day: `day-gold`). Used for the hero figure, the primary button fill, the 2 dp underline of the active tab, the pill behind the active nav icon (gold at 18 %), every checked Switch, CheckBox and SeekBar, the "key found" state, and the "All" chip text in Send. Day uses the darker gold because the dark-theme gold on white reads at only 2.1:1.
- **On Gold** (`on-gold`; Day: `day-on-gold`). Text and icons placed on a gold fill.
- **Gold Hairline** (`hairline-gold`; Day: `day-hairline-gold`). Gold at 35 %. Used only for the two hairlines that frame the microprint band.

### Secondary
- **Rosette Violet** (`rosette-violet`; Day: `day-rosette-violet`). Used for one of the rosette's three hypotrochoid layers. Nowhere else: the avatar mark lost its violet gradient for this reason.

### Tertiary (states)
- **State OK** (`state-ok` / `day-state-ok`). The status dot while a job runs.
- **State Warn** (`state-warn` / `day-state-warn`). Warnings, and the status dot when the miner is throttled by heat. Always paired with text, never shown as colour alone.
- **State Red** (`state-red` / `day-state-red`). Errors and the Stop label, which sits on **Stop Wash** (`stop-wash` / `day-stop-wash`).
- **State Info** (`state-info` / `day-state-info`). Informational notes.

### Neutral
- **Slate Ground** (`ground` / `day-ground`). Every screen background, painted flat. Also the status bar colour.
- **Surface** (`surface` / `day-surface`). Panels, dialogs and the bottom bar.
- **Raised Surface** (`surface-raised` / `day-surface-raised`). Inputs, segmented tracks and the selected fee option.
- **Key** (`key` / `day-key`). Secondary buttons and the active segment.
- **Border** (`border` / `day-border`). The 1 px dividers inside a settings group.
- **Hairline** (`hairline` / `day-hairline`). Ink at 7 %. Used for group rules, row rules, the tab-strip baseline and the top edge of the nav bar.
- **Ink / Ink Secondary / Ink Muted** (`ink`, `ink-secondary`, `ink-muted` and their `day-` pairs). Primary text, row labels and qualifiers, and hints, idle dots and microprint, in that order. On the dark card they measure 14.2:1, 5.8:1 and 5.3:1.

### Named Rules
**The One Gold Rule.** Gold means one of four things: the figure, the action, the current selection, or "found". If gold appears anywhere else on a screen, it is a defect.

**The Violet-In-The-Rosette Rule.** Violet is drawn only by the rosette. It never colours text, buttons, gradients or icons.

**The States Are Not Decoration Rule.** Green, amber and red mean running, warning and error. A running job shows a green dot, not gold. Gold is kept for the figure and the action.

## Typography

**Display Font:** IBM Plex Sans (Bold, SemiBold, Medium, Regular), bundled in `res/font`, with the system sans as a load-failure fallback only.
**Label/Mono Font:** JetBrains Mono, used for microprint, keys, addresses and hashes.

**Character:** Plex is a sober financial sans, and its figures are fixed-width, so a changing figure does not shift. JetBrains Mono gives hex and addresses the same shape on every phone.

### Hierarchy
- **Hero figure** (Bold, 76 sp, tracking −0.04, no font padding): the Scanner and Miner figure. **Compact** (64 sp): Puzzle and Weak. The unit sits on the same baseline at **Figure** size (SemiBold 22 sp, ink). The qualifier line below the figure is Body in ink-secondary.
- **Display** (Bold, 44 sp, tracking −0.03, `tnum`): the balance figure in the Wallet tab. In Wallet it auto-sizes from 20 to 46 sp on one line. It shows an ink "—" while the chain is being checked and turns gold once the balance has loaded.
- **Page** (Bold, 28 sp, tracking −0.02): page titles. The Hunt header title is SemiBold 22 sp.
- **Title** (SemiBold, 17 sp): primary button labels and section titles. Dialog titles are 19 sp SemiBold, set in `styles.xml`, with up to two lines.
- **Body** (Regular, 15 sp): row labels and normal text. **Data value** (SemiBold, 15 sp): the right-hand value of a row.
- **Caption** (Medium, 13 sp): group labels, the status text, and the "Total balance" qualifier under the figure.
- **Label** (Medium/SemiBold, 12 sp): bottom-bar labels. The 12 sp floor is the minimum for anything a user is meant to read.
- **Microprint** (JetBrains Mono, 9 sp, uppercase, tracking 0.12): only inside the microprint band. It is decorative texture, and TalkBack skips it.

### Named Rules
**The One Figure Rule.** Each screen has one figure at hero or display size. Everything else drops to Body or Caption.

**The Qualifier-Below Rule.** The words that name a figure ("keys per second", "Total balance") sit under the figure in ink-secondary. They never sit above it as a label.

**The Microprint Rule.** Microprint is the only text allowed below 12 sp. It prints facts that are fixed when the screen is built (dataset, derivation paths, engine, pool, network), repeated three times as texture. It never carries a value that changes, and it is never the only place a fact the user needs appears.

## Layout

The layout is a single column on a 4/8 dp rhythm. Content keeps a **20 dp** side margin (`side`). Groups have **16 dp** vertical padding inside their hairlines (`group`) and **12 dp** between siblings (`gap`). Page titles sit 24 dp from the top.

First viewport, top to bottom:
1. App bar or Hunt header: "Hunt" in SemiBold 22 sp with the avatar mark on the right.
2. Underlined text tabs. Hunt has Scanner · Puzzle · Weak · Recovery.
3. The hero: 250 dp in Scanner and Wallet, 240 dp in Miner, 210 dp in Puzzle and Weak, 170 dp in Recovery (kept short so "Start recovery" stays in the first viewport). The 320 dp rosette is centred and allowed to overflow the hero vertically.
4. The microprint band.
5. About three or four data rows.
6. The gold primary, placed directly under the rows rather than after the settings.

Settings follow below the first viewport. Pages reserve 80 dp of bottom padding above the bar.

Navigation is a 5-item, edge-to-edge bottom bar: Hunt, Wallet, Miner, Core, More. Recovery, Debug and the theme setting live in More.

## Elevation & Depth

The system is flat. There are no shadows: buttons set `stateListAnimator = null` to remove Material's lift. The screen ground is a flat colour, with the earlier radial glow removed. The primary button fill is flat gold, with the earlier diagonal gradient removed. Depth comes from tone in two places only: raised surfaces such as inputs and segmented tracks sit one step above the ground, and dialogs use `surface`. Hairlines do the rest of the separating.

### Named Rules
**The Hairline-Not-Box Rule.** A group is content between two hairlines (`hairline`, 1 px), aligned to the screen margin, with no fill, no radius and no shadow. `Ui.card` and `Ui.section` both draw this way.

**The Flat Ground Rule.** No gradients anywhere: not on the ground, not on buttons, not on avatars.

## Shapes

Corners are restrained, "more financial than toy". Use **12 dp** (`inner`) for inputs, secondary buttons and segmented tracks. Use **16 dp** (`key`/`card`) for primary buttons and the Stop button. Use **8 dp** (`chip`) for chips and active segments. Use a full pill (`pill`) only for the status chip and the 16 dp-radius nav pill. Hairlines are exactly 1 px (not 1 dp). The active-tab underline is 2 dp. The rosette's strokes are 0.6 dp.

## Components

### Hero over the rosette (signature)
`Ui.heroe(roseton, contenido, alto)`. A frame of the hero height with the 320 dp `Roseton` centred behind and the figure column centred in front. Children are not clipped. The figure column, top to bottom:
- the figure and its unit on one baseline;
- the qualifier line, padded 10 dp above;
- the status row, padded 14 dp above: `Ui.puntoEstado`, an 8 dp dot plus a Caption Medium status.

The status dot is green while running, warn-coloured when the miner is hot, muted when idle, and gold when a key is found. The status text is ink while running and ink-secondary when idle. Live examples: Scanner, Puzzle, Weak (while running), Recovery, Miner, Wallet tab, Wallet balance.

**The rosette.** Three closed hypotrochoids: two gold layers at alpha 0x38/0x24 (Day 0x30/0x20) and one violet layer at 0x30 (Day 0x28), drawn with a 0.6 dp stroke. It reads as texture, not as a drawing.

**Motion.** The rosette turns only while its `activo()` lambda says a job runs. The base speed is 4°/s, one turn every 90 s. That speed is multiplied by `ritmo()`, clamped to 0.25–4:
- `log10(rate) / 4` for keys/s (Scanner, Puzzle);
- `log10(H/s) / 5` for the miner.

Even layers turn forward and odd layers turn backward at 0.6×, so the moiré visibly moves. When idle the rosette is still and only checks every 500 ms whether the job has restarted. With system animations off (`Ui.animar()` false) it never turns. It is marked not important for accessibility.

### Microprint band
`Ui.microimpresion(texto)`. One horizontally scrolling, unellipsised line of 9 sp mono uppercase, repeated as `T · T · T`, with 3 dp vertical padding between two gold hairlines. It holds fixed facts only, for example engine and dataset, derivation paths and address count, pool protocol and hash, or BIP39 parameters. It is hidden from TalkBack.

### Data rows
`Ui.filaDato(etiqueta, ultima)`. Minimum height 54 dp. The label is Body in ink-secondary, filling the width, with a 12 dp end margin. The value is Data value in ink, end-aligned, up to two lines, and starts as "—". Each row has a hairline below it, except the last. The whole row is focusable, so TalkBack reads "label, value" together. A row never repeats a figure that the hero or another row already shows. Weak dropped its key-number and budget-percentage repeat, and the Weak chart and progress ring were taken off-screen for this reason.

### Buttons
- **Primary:** flat gold fill (`Ui.botonAccento` or `Ui.primary`), on-gold text in SemiBold 16–17 sp, 16 dp radius, 54 dp tall (48–56 range), full width inside the 20 dp margins. Pressing it gives haptic feedback and a 0.96→1 scale pulse.
- **Secondary:** `key` fill with ink text in Medium Body, 12 dp radius, 48–52 dp tall. On Weak, the BSGS button is secondary because the audit button above it is already gold.
- **Stop:** the Scanner's Stop swaps to a stop-wash fill with a state-red label. It is destructive, not primary, so it does not get a solid red block.
- **Dialog buttons** (`styles.xml`): no all-caps, 15 sp SemiBold. The positive button takes gold from `colorAccent`. Negative and neutral buttons take the secondary text colour.

**The One Gold Primary Rule.** Exactly one gold button is visible per viewport. When a second job takes over, for example Kangaroo running, its Stop replaces the Start and does not appear next to it.

### Tabs
`Ui.subpestanas`. Equal-width text tabs, 48 dp tall, over a hairline baseline. Inactive tabs are Medium in ink-secondary. The active tab is SemiBold in ink with a 2 dp gold underline and `isSelected` set. The same grammar is used for Hunt's four tools, Wallet's Balance · History · Send · Receive, and Recovery's 12 / 24 words. There are no boxed segmented controls for navigation.

### Controls
**The Gold Controls Rule.** Every Switch, CheckBox, RadioButton and SeekBar is tinted:
- `Ui.tintarInterruptor`: gold thumb when checked, muted when not; gold at 40 % / muted at 33 % for the track.
- `Ui.tintarDeslizador`: gold progress and thumb on a muted track.

`colorControlActivated` (gold) and `colorControlNormal` (muted) in `styles.xml` cover any control left untinted. Indeterminate progress spinners are gold.

### Inputs / Fields
`Ui.input`. A raised-surface fill with no stroke, 12 dp radius, 12/16 dp padding, and a 48 dp minimum height. Text is ink and hints are muted. Mono inputs (keys, hex) drop to 13 sp JetBrains Mono. The Send amount is a borderless 40 sp Bold field, so the figure is typed where it is read.

### Segmented selector (settings only)
`Ui.segmented`. A raised-surface track with 4 dp inset. The active option has a `key` fill at 8 dp radius in SemiBold ink. Inactive options are Medium in ink-secondary. Each option is at least 48 dp tall and is exposed to TalkBack as checkable. Use it for choosing a setting, never for navigation.

### Collapsible group
`Ui.section`. A hairline group whose header is a 16 dp-padded row:
- a decorative 20 dp stroke icon;
- the title in SemiBold Body;
- a muted chevron that rotates 90° over 180 ms.

The body expands with a 180 ms decelerating auto-transition. On API 30+, TalkBack hears "Expanded" or "Collapsed".

### Navigation
`BottomBar`. Edge-to-edge, `surface` fill with a 1 px hairline on top, and 10/12 dp vertical padding. It has five cells of equal weight, each at least 56 dp tall:
- a 22 dp icon in a 56 × 32 dp pill;
- a 12 sp label below it, with a 4 dp gap.

The active cell has a gold-at-18 % pill with radius 16 dp, a gold icon, and an ink SemiBold label. Inactive cells have an ink-secondary icon and a Medium label. The icon and label are hidden from TalkBack. The cell carries the content description and `isSelected`, so TalkBack says "Hunt, selected". Switching pages fades the new page in over 150 ms.

### Accessibility (enforced in code)
- **48 dp targets:** tabs, segmented options, inputs, the "All" chip and secondary buttons. Nav cells are 56 dp.
- **Live regions** (`POLITE`): the status text in `puntoEstado`, the Wallet total balance, and Wallet status lines. Changes are announced without stealing focus.
- **Decoration is silent:** the rosette, the microprint, the avatar mark, section icons, chevrons, empty-state icons and button icons are all marked not important for accessibility. The adjacent text says the same thing.
- **No motion when animations are off:** `Ui.animar()` gates the rosette, the press pulse, section transitions, chevron rotation and the status-chip pulse.

## Do's and Don'ts

### Do:
- **Do** put the screen's one figure in the hero, in gold Plex Bold at 76 sp (64 sp in compact heroes), with the unit on its baseline and the qualifier below.
- **Do** drive the rosette from a real "job running" signal and a log-scaled rate. Leave it still otherwise.
- **Do** frame fixed parameters in a microprint band directly under the hero.
- **Do** lay provenance out as 54 dp hairline rows, label left and value right.
- **Do** group content between two hairlines aligned to the 20 dp margin.
- **Do** place the single gold primary in the first viewport, under the rows.
- **Do** use underlined text tabs for sections and tint every control gold.
- **Do** pair every state colour with words, and give every target at least 48 dp.
- **Do** use the `day-` tokens as a set in the Day theme. Never mix a dark-theme gold or state colour onto white.

### Don't:
- **Don't** stack filled, rounded cards each holding a small figure. That is the category default this system replaces.
- **Don't** show a second gold button in the same viewport.
- **Don't** repeat a figure in a row, a chart or a ring when the hero or another row already shows it.
- **Don't** put a changing value into microprint, or set any user-facing text below 12 sp.
- **Don't** use violet outside the rosette, or gold for running state. Running is green.
- **Don't** add gradients, glows or shadows to the ground or to buttons.
- **Don't** spin the rosette as an idle ornament, or ignore the system remove-animations setting.

### Known gaps (out of scope at ship)
- The dark theme has not been recaptured since build 1174. The Day theme was verified at 1196/1198.
- Send's fee options (Slow / Normal / Fast) are still filled cards with a gold stroke when selected. The Send Amount figure is ink (slate), not gold.
- Weak has no hero while idle. Its hero and microprint appear only once a run starts.
