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
Other themes (Mint = previous default, Midnight, Neon, Exchange, Synthwave) keep their colors; their muted
text was raised to ≥ 4.5:1 on inputs.

## Typography
`"fintech banking trust" --domain typography` → **Financial Trust: IBM Plex Sans** (SIL OFL 1.1).
- Display Bold · Title SemiBold · Body Regular · Medium · Buttons SemiBold. Data: JetBrains Mono.
- Plex digits are tabular by default (all 600 units): amounts don't jitter.
- Scale (sp): Hero 58 · Display 44 · Page 28 · Figure 22 · Title 17 · **Body 15** · Caption 13 · Micro 12
  (body/caption/micro +1; micro was 11, below the 12sp minimum).

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

## Pending (not done in this pass)
- ~40 emoji/symbols used inline in texts (⚠ 🔒 ✓ …): fine as text, but structural ones should become
  vector icons from the app's stroke set.
- Visual check on device in both themes, smallest phone and landscape, largest font size.
