# Wallet Hunter — Design System (MASTER)

Source of truth for the app's look. Code: `app/src/main/java/com/hunter/btc/AppTheme.kt` (tokens) and
`Ui.kt` (components). Built with the `ui-ux-pro-max` skill (`.claude/skills/ui-ux-pro-max`).

## Product profile (verified match)
`"crypto wallet fintech" --domain product` → **Fintech/Crypto**: primary style *Glassmorphism + Dark Mode (OLED)*,
palette focus *dark tech colors + trust + vibrant accents*. Platform: Android, programmatic Views (no Compose).

Not used: the `--design-system` aggregate for this query returned a web landing pattern
("FAQ/Documentation Landing"), which does not fit a native wallet, so it was not persisted.

## Color — default theme "Vault Gold" (`Modo.OSCURO`)
From `"fintech crypto" --domain color` (Fintech/Crypto: "gold trust + purple tech"), adjusted for contrast.

| Token | Dark | Light | Notes |
|---|---|---|---|
| BG_DEEP | `#0F172A` | `#F8FAFC` | screen background |
| BG_CARD / BG_PANEL | `#222735` | `#FFFFFF` | cards |
| BG_ELEV | `#272F42` | `#F1F5F9` | inputs, dialogs |
| BG_KEY | `#252B3B` | `#E2E8F0` | secondary buttons |
| BORDER_C | `#334155` | `#CBD5E1` | dividers |
| TXT_PRI | `#F8FAFC` | `#0F172A` | 14.2:1 / 17.9:1 on card |
| TXT_SEC | `#B0BBCC` | `#475569` | ≥ 5.8:1 / 7.6:1 |
| TXT_MUTED | `#8D9BB0` | `#56657A` | 5.3:1 / 5.9:1 (was 3.2:1) |
| ACCENT | `#F59E0B` | `#B45309` | action · balance · running. Light uses the darker gold (2.1:1 → 5.0:1) |
| ON_ACCENT | `#0F172A` | `#FFFFFF` | text on the gold button: 8.3:1 / 5.0:1 |
| ACCENT2 | `#A78BFA` | `#7C3AED` | gradients only, no meaning |
| RED | `#F87171` | `#B91C1C` | error / destructive |
| WARN | `#FB923C` | `#C2410C` | warning, distinct from the gold accent; always with "⚠" text |
| BLUE | `#60A5FA` | `#2563EB` | info |
| OK | `#4ADE80` | `#15803D` | "this is fine" (security score). Not the accent |

Rule kept from before: **one accent, one meaning** (action / positive / running).

**Only two themes:** Vault Gold (dark, `Modo.OSCURO`) and **Vault Gold · Day** (light, `Modo.CLARO`).
Mint, Midnight, Neon, Exchange, Synthwave and Auto were removed; a saved one maps to Vault Gold.
The skill has no light palette for Fintech/Crypto (it marks light as not recommended for that style), so the
day version follows its light-mode rules instead (`"light mode contrast surface" --domain ux`): dark text on
light, ≥ 4.5:1, no gray-on-gray, visible borders; and the same gold, darkened to #B45309 for contrast.

## Typography
`"fintech banking trust" --domain typography` → **Financial Trust: IBM Plex Sans** (SIL OFL 1.1).
- Display Bold · Title SemiBold · Body Regular · Medium · Buttons SemiBold. Data: JetBrains Mono.
- Plex digits are tabular by default (all 600 units): amounts don't jitter.
- Scale (sp): Hero 58 · Display 44 · Page 28 · Figure 22 · Title 17 · **Body 15** · Caption 13 · Micro 12
  (body/caption/micro +1; micro was 11, below the 12sp minimum).

## Visual direction: "Security print" (impeccable redesign)

Chosen by the owner from three directions on the design canvas. The contract is in `.impeccable/surfaces/` and the product truth in `PRODUCT.md`. In short: fine work exists to prove a figure is genuine, the way guilloche does on a banknote. Instead of stacked cards, each screen has one figure that matters, and hairlines carry the rest.

**Grammar (every main screen):**
1. **Hero** (`Ui.heroe`):
   - the one figure that matters, in gold, Plex Bold, 56 to 76 sp, tabular, with a point as the decimal separator;
   - centred over the **guilloche rosette** (`Roseton`: Canvas hypotrochoids, 0.6 dp stroke, gold plus one violet layer);
   - a qualifier line under the figure, and a status dot (`Ui.puntoEstado`: green while running, grey when idle, amber when hot).
2. **Microprint band** (`Ui.microimpresion`): 9 sp mono uppercase between two gold hairlines, printing real, *fixed* parameters. Never a value that changes after the screen is built.
3. **Data rows** (`Ui.filaDato`): label on the left, semibold value on the right, 54 dp tall, a hairline below. A row never repeats a figure that is already in the hero or in another row.
4. **One gold primary button** in the first viewport. Secondary buttons are `BG_KEY` or outlined. Only one gold button is visible at a time.
5. **Groups** (`Ui.card`, `Ui.section`, the groups in More): content between two hairlines (`Ui.lineas` + `Ui.filete`), aligned to the screen margin. No filled rounded cards, and no gradients.

**Signature interaction:** the rosette turns only while a job runs, at a speed that follows the work rate (`ritmo`: keys/s, op/s, H/s on a log scale). It stops dead when idle and stays still when the system "remove animations" setting is on. TalkBack skips it.

**Navigation:**
- a 5-item bottom bar: Hunt · Wallet · Miner · Core · More, with a gold pill on the active icon;
- Hunt groups Scanner / Puzzle / Weak / Recovery under underlined text tabs, the same kind of tabs Wallet uses.

**Controls:**
- every Switch, CheckBox and SeekBar is tinted gold (`Ui.tintarInterruptor`, `Ui.tintarDeslizador`, plus `colorAccent`/`colorControl*` in `styles.xml` for any control left untinted);
- progress bars are solid gold;
- selection is shown with a 2 dp gold underline, not a filled chip.

**Day theme:** the same grammar, using the darker gold `#B45309`. The status bar uses the ground colour with dark icons.

## Shape & spacing (4/8 dp rhythm)
R_CARD 16 · R_INNER 12 · R_CHIP 8 · R_KEY 16 · PAD_SIDE 20 · PAD_CARD 16 · GAP 12.
Cards get a 1 px border of TXT_PRI at 8 % in dark themes (glass hint for OLED).

## Interaction & accessibility (pro-rules checklist)
- Touch targets ≥ 48 dp (segmented pills and inputs raised from ~40/46 dp).
- Pressed feedback (ripple) on primary, ghost and segmented controls.
- System "remove animations" respected: status-dot pulse, press pulse, section expand.
- Collapsible sections announce Expanded/Collapsed; decorative icons hidden from TalkBack;
  segmented options announce checked state.
- Security audit colors come from tokens (OK/WARN/RED), never hardcoded; severity is also written.

## Status symbols → vector icons
`IconosTexto.en(ctx, text)` draws ✓ ✗ ⚠ 🔒 🔓 ❄ as icons from the app's stroke set (24 grid, 1.8 stroke,
round caps), tinted with their semantic token and centered on the line. New icons: `ic_check`, `ic_x`,
`ic_unlock`, `ic_snow` (with the existing `ic_warning`, `ic_lock`).

| Symbol | Icon | Tint |
|---|---|---|
| ✓ | ic_check | OK |
| ✗ | ic_x | RED |
| ⚠ | ic_warning | WARN |
| 🔒 / 🔓 | ic_lock / ic_unlock | WARN / OK |
| ❄ | ic_snow | BLUE |

The character stays under the icon, so TalkBack still reads it and copying gives the same text. Used by
Tools result rows, wallet dialogs (send review, tax report, consolidation, coin control), external wallet,
security audit, Core and recovery. Plain-text logs (Weak terminal, miner) use words instead.

## Pending
- Visual check on device in both themes, smallest phone and landscape, largest font size.
