# Wallet screen (`WalletActivity`)

This page overrides `../MASTER.md` only where it says so. Everything else (tokens, type, icons, the two themes) comes from the master file.

## Structure

1. **Header**:
   - back, wallet name, a `Testnet` chip on testnet, scanner, and "more";
   - every icon is a 48 dp target with a `contentDescription`.
2. **Tabs**:
   - a segmented control on `BG_ELEV`;
   - the active tab is a `BG_CARD` card inset 4 dp, in bold `TXT_PRI`, with `isSelected`;
   - each tab is 48 dp tall;
   - gold is kept for actions, not for "where you are".
3. **Content** with the side margin `PAD_SIDE`.
4. **Action bar** (only on Balance and History):
   - Send and Lightning are secondary, on `BG_KEY` with an edge;
   - Receive is primary, on gold with `ON_ACCENT` text;
   - buttons are 54 dp tall.

## Balance

- **Hero card**:
  - "Total balance" label;
  - the figure auto-sizes from 20 to 40 sp, on one line;
  - the privacy eye sits next to it;
  - the fiat value goes below;
  - while the chain is queried, a spinner and "Checking the chain…"; pending funds show in `WARN`.
- "Search for addresses again" is a 48 dp link.
- Notices use `aviso()`: an icon and a polite live region.
- **Addresses**:
  - all of them in ONE card, as rows split by a hairline;
  - each row has the label, the address with a middle ellipsis, the amount on the right, and the fiat value;
  - tapping a row copies the address.

## History

- A summary/status line that is a live region.
- "Tax report" and "CSV" appear as links in one row.
- **Rows**, inside cards of up to 50 rows:
  - a 40 dp round badge: `ic_receive` in green when incoming, `ic_send` neutral when outgoing, `ic_clock` in `WARN` when pending;
  - the title (tx label or Received/Sent);
  - the date, or "Pending";
  - the amount and fiat on the right.
- **Empty state**: `Ui.estadoVacio` plus a "Receive bitcoin" action.

## Receive

- The QR always sits on **pure white**, in both themes. In the day theme, `TXT_PRI` is navy and many readers failed to scan it.
- Address-type pills are 48 dp. The active one uses `botonAccento` with `ON_ACCENT` text; the others use `BG_KEY` with an edge.
- **Amount field**:
  - background `BG_ELEV`, minimum height 48;
  - decimal number keyboard;
  - the fiat equivalent below.
- Copy is primary (gold) and Share is secondary; both are 52 dp tall.
- Captions use `SP_CAPTION`. `SP_MICRO` is only for dense data such as addresses and hex.

## Send

- The "All" chip and the fee pills are at least 48 dp and 56 dp tall.
- The chosen fee gets an `ACCENT` edge as well as the background change.

## Rules that must not be broken

- Never use `BG_DEEP` as a text color on gold. It must be `ON_ACCENT`, because in the day theme `BG_DEEP` is nearly white.
- No touch target under 48 dp.
- Status changes (loading, errors, "unused/used") are announced through a live region.
