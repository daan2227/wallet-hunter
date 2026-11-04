# Wallet screen (`WalletActivity`)

This page overrides `../MASTER.md` only where it says so. Everything else (tokens, type, icons, the two themes, the Security print grammar) comes from the master file.

## Structure

1. **Header**: back, wallet name, a `Testnet` chip on testnet, scanner, and "more". Every icon is a 48 dp target with a `contentDescription`.
2. **Tabs**: Balance, History, Send and Receive are **underlined text tabs**, the same as Hunt's:
   - a hairline under the row;
   - the active tab has a 2 dp gold underline and bold `TXT_PRI` text;
   - each tab is 48 dp tall.
3. **Content** uses the side margin `PAD_SIDE`.
4. **Action bar** (only on Balance and History):
   - Send and Lightning are secondary, on `BG_KEY`;
   - Receive is primary, in solid gold with `ON_ACCENT` text;
   - buttons are 54 dp tall.

## Balance

- **Hero** (`Ui.heroe` + `Roseton`):
  - the figure is centred in gold and auto-sizes from 20 to 46 sp on one line, with tabular figures and a decimal point;
  - the fiat value is below the figure, and "Total balance" is the qualifier under that;
  - the privacy eye has its own 48 dp slot, with a matching spacer on the left so the figure stays centred;
  - the rosette turns while the chain is being queried ("Checking the chain…") and stops when the balance is real.
- **Microprint**: the paths, the number of addresses, the network and the sources.
- "Search for addresses again" is a 48 dp link.
- Notices use `aviso()`: an icon and a polite live region.
- **Lightning** is a row between two hairlines, not a card.
- **Addresses** are rows split by hairlines, with no card. Each row shows:
  - the label;
  - the address, with a middle ellipsis;
  - the amount and its fiat value on the right.

  Tapping a row copies the address.

## History

- A summary/status line that is a live region.
- "Tax report" and "CSV" are links in one row.
- **Each row shows:**
  - a 40 dp round badge: `ic_receive` in green for incoming, `ic_send` in a neutral colour for outgoing, `ic_clock` in `WARN` while pending;
  - the title;
  - the date, or "Pending";
  - the amount and its fiat value on the right.
- **Empty state:** `Ui.estadoVacio` plus a "Receive bitcoin" action.

## Receive

- The QR always sits on **pure white**, in both themes.
- Address-type pills are 48 dp tall. The active pill is solid gold with `ON_ACCENT` text.
- **Amount field**:
  - background `BG_ELEV`, minimum height 48 dp;
  - decimal keyboard;
  - the fiat value is shown below.
- Copy is the primary button (gold) and Share is secondary.

## Send

- The "All" chip is at least 48 dp tall and the fee pills at least 56 dp.
- The chosen fee has an `ACCENT` edge.
- Every field in a dialog (custom fee, name an address, add output) uses `Ui.input`.

## Rules that must not be broken

- Text on gold is always `ON_ACCENT`.
- No touch target is under 48 dp.
- Status changes are announced through a live region.
- One decimal separator, the point (`Locale.US`), for every figure.
